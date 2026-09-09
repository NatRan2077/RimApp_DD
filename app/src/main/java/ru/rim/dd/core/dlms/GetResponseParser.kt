package ru.rim.dd.core.dlms

/** Один разобранный элемент ответа: OBIS-код + значение с учётом scaler/unit (если был). */
data class ObisReading(
    val obisCode: String,
    val rawValue: Long,
    val scaler: Int,
    /** Код единицы измерения по IEC 62056-6-2 (30 = Wh, 31 = VAh, ...), null — не найден. */
    val unit: Int?,
) {
    /** rawValue * 10^scaler в базовой единице (Вт·ч для unit=30), переведено в кВт·ч. */
    val valueKwh: Double get() = rawValue * Math.pow(10.0, scaler.toDouble()) / 1000.0

    /** Номер тарифа — 5-я группа OBIS-кода (A.B.C.D.E.F), 0 — суммарное значение. */
    val tariff: Int? get() = obisCode.split(".").getOrNull(4)?.toIntOrNull()?.takeIf { it != 0 }
}

private const val UNIT_WH = 30

/**
 * Разбирает "сырой" HDLC-кадр GET-response (см. SpodesClientBridge.getResponseRawBytes())
 * в список показаний. Формат этого конкретного ответа счётчика (подтверждён логом
 * реального обмена): APDU = C4(get-response-normal) 01(choice: normal) <invoke-id-and-priority>
 * 00(choice: data, не data-access-result) <DLMS-значение>, а само DLMS-значение — массив/
 * структура из повторяющихся троек {OctetString(6, OBIS-код), DoubleLongUnsigned(значение),
 * Structure(2){Integer(scaler), Enum(unit)}} — по одной на тариф.
 *
 * Не полагается на фиксированные смещения байт (в отличие от библиотечного
 * GetResponseGeneral()) — сначала ищет LLC-заголовок "E6 E7 00" (сервер→клиент), затем
 * пропускает 3 служебных байта APDU (service-id, choice, invoke-id-and-priority) и один
 * байт выбора "data"/"data-access-result", и уже оттуда запускает общий DLMS-декодер
 * (DlmsDecoder). Поэтому не критичен к однобайтовой vs двухбайтовой HDLC-адресации.
 *
 * @throws DlmsDecodeException если LLC-заголовок не найден или разбор данных не удался.
 */
fun parseGetResponseTariffs(rawFrame: ByteArray): List<ObisReading> {
    val llcOffset = findLlcHeader(rawFrame)
        ?: throw DlmsDecodeException("В ответе не найден LLC-заголовок сервера (E6 E7 00)")

    // llcOffset указывает на первый байт "E6" — далее 3 байта LLC, затем APDU.
    var pos = llcOffset + 3
    if (pos + 3 > rawFrame.size) throw DlmsDecodeException("Ответ слишком короткий для GET-response APDU")

    val serviceId = rawFrame[pos].toInt() and 0xFF
    if (serviceId != 0xC4) throw DlmsDecodeException("Ожидался GET-response (0xC4), получено 0x%02X".format(serviceId))
    pos += 1 // сместились с service-id (C4) на choice (get-response-normal = 0x01)
    pos += 1 // сместились с choice на invoke-id-and-priority (0x81 у этого счётчика)
    pos += 1 // сместились с invoke-id-and-priority на result-choice (data / data-access-result)
    val resultChoice = rawFrame[pos].toInt() and 0xFF
    pos += 1
    if (resultChoice != 0x00) throw DlmsDecodeException("Сервер вернул data-access-result вместо данных (код 0x%02X)".format(resultChoice))

    val (root, _) = DlmsDecoder(rawFrame).decodeAt(pos)
    val readings = mutableListOf<ObisReading>()
    collectReadings(root, readings)
    return readings
}

private fun findLlcHeader(frame: ByteArray): Int? {
    for (i in 0..frame.size - 3) {
        if ((frame[i].toInt() and 0xFF) == 0xE6 &&
            (frame[i + 1].toInt() and 0xFF) == 0xE7 &&
            (frame[i + 2].toInt() and 0xFF) == 0x00
        ) return i
    }
    return null
}

/** Рекурсивно обходит дерево в поисках троек {OBIS(6 байт), число, [scaler,unit]}. */
private fun collectReadings(value: DlmsValue, out: MutableList<ObisReading>) {
    val items = when (value) {
        is DlmsValue.Struct -> value.items
        is DlmsValue.Arr -> value.items
        else -> return
    }

    var i = 0
    while (i < items.size) {
        val obisItem = items[i]
        if (obisItem is DlmsValue.OctetStr && obisItem.bytes.size == 6) {
            val valueItem = items.getOrNull(i + 1)
            val rawValue = numericValueOf(valueItem)
            if (rawValue != null) {
                val scalerUnit = items.getOrNull(i + 2)
                val (scaler, unit) = scalerUnitOf(scalerUnit)
                out.add(ObisReading(obisCodeOf(obisItem.bytes), rawValue, scaler, unit))
                i += 3
                continue
            }
        }
        // Не подошло под тройку целиком — всё равно спускаемся внутрь на случай вложенности.
        collectReadings(obisItem, out)
        i += 1
    }
}

private fun obisCodeOf(bytes: ByteArray): String = bytes.joinToString(".") { (it.toInt() and 0xFF).toString() }

private fun numericValueOf(value: DlmsValue?): Long? = when (value) {
    is DlmsValue.U8 -> value.value.toLong()
    is DlmsValue.I8 -> value.value.toLong()
    is DlmsValue.U16 -> value.value.toLong()
    is DlmsValue.I16 -> value.value.toLong()
    is DlmsValue.U32 -> value.value
    is DlmsValue.I32 -> value.value
    is DlmsValue.U64 -> value.value
    is DlmsValue.I64 -> value.value
    else -> null
}

/** Structure(2){Integer scaler, Enum unit} — если формат другой, считаем scaler=0/unit=Wh по умолчанию. */
private fun scalerUnitOf(value: DlmsValue?): Pair<Int, Int?> {
    if (value is DlmsValue.Struct && value.items.size == 2) {
        val scaler = (value.items[0] as? DlmsValue.I8)?.value
        val unit = (value.items[1] as? DlmsValue.EnumVal)?.value
        if (scaler != null) return scaler to unit
    }
    return 0 to UNIT_WH
}
