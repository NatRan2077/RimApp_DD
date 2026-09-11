package ru.rim.dd.core.dlms

import java.time.LocalDate
import java.time.LocalTime

/**
 * Один разобранный элемент ответа: OBIS-код + значение с учётом scaler/unit (если был),
 * либо (для служебных полей вроде часов/даты устройства) — «сырые» байты OctetString.
 *
 * Реальный буфер (см. историю диагностики — сравнение с логом пульта РиМ 040.40)
 * содержит вперемешку: накопленную энергию (D=8, unit=Wh/varh), мгновенные величины
 * (D=7: мощность/напряжение/ток/частота) и статусные OBIS 0.0.96.x (температура,
 * резервное питание) — единая структура ObisReading используется для всех них,
 * а MeterRepositoryImpl уже раскладывает их по нужным экранам.
 */
data class ObisReading(
    val obisCode: String,
    val rawValue: Long,
    val scaler: Int,
    /** Код единицы измерения по IEC 62056-6-2 (30 = Wh, 35 = V, 44 = Hz, ...), null — не найден. */
    val unit: Int?,
    /** Заполнено вместо rawValue/scaler/unit для OctetString-значений (напр. дата/время прибора). */
    val octetValue: ByteArray? = null,
    /**
     * [Android-патч] Заполнено вместо rawValue/scaler/unit, если значение атрибута — DLMS
     * VisibleString (тег 0x0A), напр. версия ПО или другое текстовое служебное поле. Нужно
     * для работы с РАЗНЫМИ счётчиками серии: пользователь обнаружил, что на другом приборе
     * (не РиМ 040.40, на который были рассчитаны все предыдущие фиксы) первый сегмент буфера
     * 0.0.21.0.2.255 БОЛЬШЕ и, судя по всему, содержит доп. поля вроде версии ПО прямо внутри
     * "публичного" буфера индикации — то есть без отдельного GET и без ассоциации. Раньше
     * collectReadings() ниже умел распознавать в паре {OBIS, значение} только число или
     * OctetString — VisibleString (текст) просто терялся молча. Теперь ловится и он, поэтому
     * ЛЮБОЙ счётчик с текстовым полем прямо в этом буфере подхватится сам, без хардкода под
     * конкретную модель.
     */
    val textValue: String? = null,
) {
    /** rawValue * 10^scaler — значение в базовой единице (Вт, В, А, Гц, °C, Вт·ч, вар·ч...). */
    val value: Double get() = rawValue * Math.pow(10.0, scaler.toDouble())

    /** value / 1000 — удобно для энергии (Вт·ч → кВт·ч, вар·ч → квар·ч) и мощности (Вт → кВт). */
    val valueKwh: Double get() = value / 1000.0

    /** Номер тарифа — 5-я группа OBIS-кода (A.B.C.D.E.F), 0 — суммарное значение. */
    val tariff: Int? get() = obisCode.split(".").getOrNull(4)?.toIntOrNull()?.takeIf { it != 0 }

    /** Текстовое обозначение единицы измерения (для показа на экране), null — неизвестна/нет. */
    val unitLabel: String? get() = unitLabelOf(unit)
}

private const val UNIT_WH = 30

/** Подмножество таблицы единиц измерения IEC 62056-6-2, реально встречающееся у этого счётчика. */
fun unitLabelOf(unit: Int?): String? = when (unit) {
    4 -> "сут"
    5 -> "ч"
    6 -> "мин"
    7 -> "с"
    8 -> "°"
    9 -> "°C"
    27 -> "Вт"
    28 -> "ВА"
    29 -> "вар"
    30 -> "Вт·ч"
    31 -> "ВА·ч"
    32 -> "вар·ч"
    33 -> "А"
    35 -> "В"
    44 -> "Гц"
    56 -> "%"
    else -> null
}

/** COSEM date-octet-string (5 байт): year_hi, year_lo, month, day, day-of-week. */
fun decodeCosemDate(bytes: ByteArray): LocalDate? {
    if (bytes.size < 4) return null
    val year = ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
    val month = bytes[2].toInt() and 0xFF
    val day = bytes[3].toInt() and 0xFF
    return try {
        LocalDate.of(year, month, day)
    } catch (ex: Exception) {
        null
    }
}

/** COSEM time-octet-string (4 байта): hour, minute, second, hundredths. */
fun decodeCosemTime(bytes: ByteArray): LocalTime? {
    if (bytes.size < 3) return null
    val hour = bytes[0].toInt() and 0xFF
    val minute = bytes[1].toInt() and 0xFF
    val second = bytes[2].toInt() and 0xFF
    return try {
        LocalTime.of(hour, minute, second)
    } catch (ex: Exception) {
        null
    }
}

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
    val root = parseGetResponseValue(rawFrame)
    val readings = mutableListOf<ObisReading>()
    collectReadings(root, readings)
    return readings
}

/**
 * Пропускает служебные байты APDU (LLC-заголовок, service-id, choice, invoke-id-and-priority,
 * result-choice) и разбирает уже само DLMS-значение — общий код для parseGetResponseTariffs()
 * (когда известно, что внутри массив/структура из троек OBIS+значение+scaler-unit) и для
 * диагностики произвольных OBIS-кодов, где заранее не известно, скаляр там или структура
 * (см. MeterRepositoryImpl.probeStandardObisCandidates()).
 *
 * @throws DlmsDecodeException если LLC-заголовок не найден, ответ короче ожидаемого,
 *         сервис не GET-response или сервер вернул data-access-result вместо данных.
 */
fun parseGetResponseValue(rawFrame: ByteArray): DlmsValue {
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
    if (resultChoice != 0x00) {
        // resultChoice=1 значит "дальше идёт код ошибки (Data-Access-Result), а не данные" —
        // сам код лежит СЛЕДУЮЩИМ байтом (см. IEC 62056-6-2: 0=success, 1=hardware-fault,
        // 4=object-undefined, 11=no-long-get-in-progress, 13=data-block-number-invalid...).
        val errorCode = rawFrame.getOrNull(pos)?.toInt()?.and(0xFF)
        throw DlmsDecodeException(
            if (errorCode != null) "Сервер вернул data-access-result, код ошибки 0x%02X (%d)".format(errorCode, errorCode)
            else "Сервер вернул data-access-result (choice=0x%02X), но код ошибки не поместился в ответ".format(resultChoice)
        )
    }

    val (root, _) = DlmsDecoder(rawFrame).decodeAt(pos)
    return root
}

/**
 * [Android-патч] Разбирает ACTION-response (см. turnRelayOn()/turnRelayOff() в
 * MeterRepositoryImpl.kt) — тот же принцип, что и у parseGetResponseValue(): ищем LLC-
 * заголовок сервера "E6 E7 00", затем идёт service-id=0xC7 (action-response-normal),
 * choice=0x01 (normal), invoke-id-and-priority, и СРАЗУ (без выбора data/data-access-result,
 * как у GET-response) — однобайтовый Action-Result (0=success, остальные коды — по тому же
 * перечислению Data-Access-Result: 1=hardware-fault, 3=read-write-denied и т.д.), а следом
 * ещё один байт-выбор "есть ли возвращаемые данные" (в подтверждённом логе — 0, данных нет,
 * этого достаточно для методов Disconnect Control). Подтверждено реальным логом пульта
 * РиМ 040.40: ответ на ACTION method 2 (remote_reconnect, 0.0.96.3.10.255) — ровно
 * "E6 E7 00 C7 01 <invoke> 00 00" (00=success, 00=без данных).
 *
 * @return код Action-Result (0 = успех) или null, если ответ короче ожидаемого/не ACTION-response.
 */
fun parseActionResponseResult(rawFrame: ByteArray): Int? {
    val llcOffset = findLlcHeader(rawFrame) ?: return null
    val pos = llcOffset + 3
    if (pos + 4 > rawFrame.size) return null
    val serviceId = rawFrame[pos].toInt() and 0xFF
    if (serviceId != 0xC7) return null
    return rawFrame[pos + 3].toInt() and 0xFF
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

/**
 * Рекурсивно обходит дерево в поисках троек {OBIS(6 байт), число, [scaler,unit]}, а также
 * пар {OBIS(6 байт), OctetString} — так закодированы часы/дата устройства (0.0.0.9.1.255 /
 * 0.0.0.9.2.255 в этом буфере): вместо числа там 4/5-байтовый OctetString, а следующей
 * структуры {scaler,unit} для них либо нет, либо она бессмысленна — в этом случае она просто
 * пропускается, если похожа по форме на Structure(2).
 */
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
            if (valueItem is DlmsValue.OctetStr) {
                // [Android-патч] НАЙДЕНО по логу подключения к ДРУГОМУ счётчику (модель 189.40):
                // версия ПО там лежит прямо в этом же буфере под OBIS 0.0.96.1.2.255 — ровно тем,
                // что мы уже занесли в FIRMWARE_VERSION_OBIS_CANDIDATES (MeterRepositoryImpl) — но
                // закодирована НЕ как VisibleString (тег 0x0A, ловится веткой ниже), а как OctetString
                // (тег 0x09) с ASCII-текстом внутри ("31 2E 35 38" = "1.58"). Раньше это попадало
                // только в octetValue (сырые байты) — textValue оставался null, и версия ПО не
                // подхватывалась. Печатаемый ASCII из OctetString — частый способ хранить текст в
                // DLMS (тот же приём уже применялся в textValueOf() для одиночных атрибутов) —
                // используем его и здесь, не трогая octetValue (он всё ещё нужен для НЕ-текстовых
                // OctetString, напр. даты/времени прибора — там байты непечатаемые, textValue
                // останется null, поведение для них не меняется).
                val text = valueItem.bytes.takeIf { it.isNotEmpty() && it.all { b -> (b.toInt() and 0xFF) in 0x20..0x7E } }
                    ?.let { String(it, Charsets.US_ASCII) }
                out.add(ObisReading(obisCodeOf(obisItem.bytes), 0L, 0, null, octetValue = valueItem.bytes, textValue = text))
                val maybeScalerUnit = items.getOrNull(i + 2)
                i += if (maybeScalerUnit is DlmsValue.Struct && maybeScalerUnit.items.size == 2) 3 else 2
                continue
            }
            // [Android-патч] см. textValue в ObisReading — пара {OBIS, VisibleString} встречается,
            // когда буфер конкретного счётчика включает текстовые служебные поля (напр. версию ПО)
            // прямо внутри "публичного" профиля — на разных моделях счётчиков состав буфера разный,
            // это ловит такие поля независимо от того, какой именно OBIS у них оказался.
            if (valueItem is DlmsValue.VisibleStr) {
                out.add(ObisReading(obisCodeOf(obisItem.bytes), 0L, 0, null, textValue = valueItem.text))
                val maybeScalerUnit = items.getOrNull(i + 2)
                i += if (maybeScalerUnit is DlmsValue.Struct && maybeScalerUnit.items.size == 2) 3 else 2
                continue
            }
        }
        // Не подошло под тройку/пару целиком — всё равно спускаемся внутрь на случай вложенности.
        collectReadings(obisItem, out)
        i += 1
    }
}

private fun obisCodeOf(bytes: ByteArray): String = bytes.joinToString(".") { (it.toInt() and 0xFF).toString() }

/** Компактное текстовое представление для логов — OctetStr(6) печатается как OBIS-код. */
fun describeDlmsValue(value: DlmsValue): String = when (value) {
    is DlmsValue.Struct -> "Struct(${value.items.joinToString(", ") { describeDlmsValue(it) }})"
    is DlmsValue.Arr -> "Arr(${value.items.joinToString(", ") { describeDlmsValue(it) }})"
    is DlmsValue.OctetStr -> if (value.bytes.size == 6) "OBIS(${obisCodeOf(value.bytes)})"
    else "OctetStr(${value.bytes.joinToString(" ") { "%02X".format(it) }})"
    is DlmsValue.VisibleStr -> "Str(\"${value.text}\")"
    is DlmsValue.U8 -> "U8(${value.value})"
    is DlmsValue.I8 -> "I8(${value.value})"
    is DlmsValue.U16 -> "U16(${value.value})"
    is DlmsValue.I16 -> "I16(${value.value})"
    is DlmsValue.U32 -> "U32(${value.value})"
    is DlmsValue.I32 -> "I32(${value.value})"
    is DlmsValue.U64 -> "U64(${value.value})"
    is DlmsValue.I64 -> "I64(${value.value})"
    is DlmsValue.EnumVal -> "Enum(${value.value})"
    is DlmsValue.BoolVal -> "Bool(${value.value})"
    is DlmsValue.Float32Val -> "F32(${value.value})"
    is DlmsValue.Float64Val -> "F64(${value.value})"
    DlmsValue.NullVal -> "Null"
}

/**
 * Человекочитаемое текстовое представление одиночного значения — для служебных OBIS вроде
 * версии ПО (0.0.96.1.2.255), где заранее не известно, придёт ли VisibleString, OctetString
 * (ASCII-текст или бинарные данные) или просто число. VisibleString — как есть; OctetString —
 * как ASCII, если все байты печатаемые, иначе HEX; числа — через toString(); Struct/Arr/Null —
 * через describeDlmsValue() (для диагностики, если формат окажется сложнее одиночного значения).
 */
fun textValueOf(value: DlmsValue): String = when (value) {
    is DlmsValue.VisibleStr -> value.text
    is DlmsValue.OctetStr -> {
        val bytes = value.bytes
        if (bytes.isNotEmpty() && bytes.all { (it.toInt() and 0xFF) in 0x20..0x7E }) {
            String(bytes, Charsets.US_ASCII)
        } else {
            bytes.joinToString(" ") { "%02X".format(it) }
        }
    }
    is DlmsValue.U8 -> value.value.toString()
    is DlmsValue.I8 -> value.value.toString()
    is DlmsValue.U16 -> value.value.toString()
    is DlmsValue.I16 -> value.value.toString()
    is DlmsValue.U32 -> value.value.toString()
    is DlmsValue.I32 -> value.value.toString()
    is DlmsValue.U64 -> value.value.toString()
    is DlmsValue.I64 -> value.value.toString()
    is DlmsValue.EnumVal -> value.value.toString()
    is DlmsValue.BoolVal -> value.value.toString()
    is DlmsValue.Float32Val -> value.value.toString()
    is DlmsValue.Float64Val -> value.value.toString()
    else -> describeDlmsValue(value)
}

/**
 * [Android-патч] Универсальное числовое/булево значение одиночного атрибута — для служебных
 * объектов вроде состояния размыкателя (0.0.96.3.10.255, класс Disconnect Control), где заранее
 * не известно, придёт ли Boolean (output_state) или Enum (control_state). В отличие от
 * numericValueOf() ниже (используется только для показаний энергии — там Bool/Enum не нужны),
 * эта функция публичная и покрывает ещё EnumVal и BoolVal.
 */
fun longOrBoolValueOf(value: DlmsValue): Long? = when (value) {
    is DlmsValue.U8 -> value.value.toLong()
    is DlmsValue.I8 -> value.value.toLong()
    is DlmsValue.U16 -> value.value.toLong()
    is DlmsValue.I16 -> value.value.toLong()
    is DlmsValue.U32 -> value.value
    is DlmsValue.I32 -> value.value
    is DlmsValue.U64 -> value.value
    is DlmsValue.I64 -> value.value
    is DlmsValue.EnumVal -> value.value.toLong()
    is DlmsValue.BoolVal -> if (value.value) 1L else 0L
    else -> null
}

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
