package ru.rim.dd.core.dlms

import java.time.LocalDate
import java.time.LocalTime

/**
 * Один элемент буфера: OBIS-код + значение (rawValue × 10^scaler, unit) либо, вместо числа,
 * сырые байты ([octetValue]), текст ([textValue]) или готовый float ([floatValue]).
 */
data class ObisReading(
    val obisCode: String,
    val rawValue: Long,
    val scaler: Int,
    /** Код единицы по IEC 62056-6-2 (30 = Wh, 35 = V, 44 = Hz, ...), null — не найден. */
    val unit: Int?,
    /** Вместо rawValue/scaler/unit для OctetString (напр. дата/время прибора). */
    val octetValue: ByteArray? = null,
    /**
     * Вместо rawValue/scaler/unit для текстовых полей буфера (версия ПО и т.п.):
     * VisibleString (тег 0x0A) либо OctetString с печатным ASCII.
     */
    val textValue: String? = null,
    /**
     * Вместо rawValue/scaler для значения, пришедшего готовым float (теги 0x17/0x18, без пары
     * {scaler, unit}) — так отдаёт AKROS. Хранится как есть, [value] отдаёт ему предпочтение.
     */
    val floatValue: Double? = null,
) {
    /** rawValue * 10^scaler — значение в базовой единице (Вт, В, А, Гц, °C, Вт·ч, вар·ч...). */
    val value: Double get() = floatValue ?: (rawValue * Math.pow(10.0, scaler.toDouble()))

    /** value / 1000 — удобно для энергии (Вт·ч → кВт·ч, вар·ч → квар·ч) и мощности (Вт → кВт). */
    val valueKwh: Double get() = value / 1000.0

    /** Номер тарифа — 5-я группа OBIS-кода (A.B.C.D.E.F), 0 — суммарное значение. */
    val tariff: Int? get() = obisCode.split(".").getOrNull(4)?.toIntOrNull()?.takeIf { it != 0 }

    /** Текстовое обозначение единицы измерения (для показа на экране), null — неизвестна/нет. */
    val unitLabel: String? get() = unitLabelOf(unit)
}

private const val UNIT_WH = 30

/**
 * control_state размыкателя (класс 70 Disconnect Control, атрибут 3) — Enum, позиционное поле [1]
 * буфера индикации 0.0.21.0.2.255 сразу после статуса часов. Отдельным GET не читается (ответ 0x0D).
 * Позиция совпадает у РиМ 040.40 и 189.40.
 */
const val RELAY_CONTROL_STATE_OBIS = "0.0.96.3.10.255"

/**
 * Статус пломб, магнитного поля, СВЧ, батареи и лимита мощности («Значки ДД») — UInt32,
 * позиционное поле [4] буфера, после текущего тарифа. OBIS паспортный, для GET не используется.
 *
 * Раскладка (прошивка пульта РиМ 040.40, read_status(); байт 1 — второй от старшего,
 * напр. 0x00410100 → 0x41): бит 0 Tamper_Cover (замок 2), бит 1 Tamper_Meter (замок 1),
 * бит 2 Tamper_Magnet, бит 3 Tamper_Battery (замок 3), бит 4 СВЧ, бит 5 статус диагностики,
 * биты 6-7 версия ЖКИ. Байт 2 — те же биты в смысле «было, сейчас не активно»; бит 7 —
 * превышение лимита мощности. Подробнее — TamperState.decode().
 */
const val TAMPER_STATUS_OBIS = "0.0.96.50.170.255"

/** Текущий тариф (1..N) — OctetString(1), позиционное поле [3] буфера, перед [TAMPER_STATUS_OBIS]. */
const val CURRENT_TARIFF_OBIS = "0.0.96.14.0.255"

/**
 * «Отображение фаз ДД» — Int8, позиционное поле [2] буфера, после control_state. Смысл не
 * расшифрован (предположительно маска отображаемых фаз у многофазных приборов), хранится как есть.
 */
const val DD_PHASE_DISPLAY_OBIS = "1.0.96.50.0.255"

/**
 * Статус часов (Clock, класс 8, атрибут 4) — позиционное поле [0] буфера. Биты как в Clock.status:
 * часы недостоверны, если установлен любой из битов 0 (invalid value), 1 (doubtful value) или
 * 3 (invalid clock status); бит 2 (другая база времени) прошивка ДД ошибкой не считает.
 */
const val CLOCK_STATUS_OBIS = "0.0.1.0.0.255"

/** true — часы прибора достоверны (ни один из битов маски 0x0B не установлен). */
fun isClockStatusValid(rawStatus: Long): Boolean = (rawStatus and 0x0B) == 0L

/**
 * Один блок ответа, разбитого прибором на части (get-response-with-datablock: «C4 02», флаг
 * последнего блока, номер блока, данные). Нужен, когда ответ не влезает в HDLC-кадр: AKROS
 * ограничивает информационное поле 128 байтами, а capture_objects (14 записей по 18 байт) больше.
 */
data class GetResponseBlock(
    /** true — последний блок. */
    val lastBlock: Boolean,
    /** Номер блока; указывается в запросе следующего. */
    val blockNumber: Int,
    /** Кусок данных; куски всех блоков склеиваются и разбираются целиком. */
    val data: ByteArray,
)

/**
 * Разбирает get-response-with-datablock. null — ответ не блочный (разбирать через
 * [parseGetResponseValue]) или некорректный.
 */
fun parseGetResponseBlock(rawFrame: ByteArray): GetResponseBlock? {
    val llcOffset = findLlcHeader(rawFrame) ?: return null
    var pos = llcOffset + 3
    // service-id (0xC4) + choice: нас интересует только choice = 2 (with-datablock)
    if (rawFrame.getOrNull(pos)?.toInt()?.and(0xFF) != 0xC4) return null
    if (rawFrame.getOrNull(pos + 1)?.toInt()?.and(0xFF) != 0x02) return null
    pos += 3 // service-id, choice, invoke-id-and-priority

    val lastBlock = (rawFrame.getOrNull(pos)?.toInt()?.and(0xFF) ?: return null) != 0
    pos += 1
    if (pos + 4 > rawFrame.size) return null
    var blockNumber = 0
    for (i in 0 until 4) blockNumber = (blockNumber shl 8) or (rawFrame[pos + i].toInt() and 0xFF)
    pos += 4

    // result-choice: 0 — дальше «сырые» данные, иначе код ошибки
    if ((rawFrame.getOrNull(pos)?.toInt()?.and(0xFF) ?: return null) != 0) return null
    pos += 1

    // Длина данных в A-XDR: до 0x80 — само число, иначе младшие биты — число байт длины
    val lengthByte = rawFrame.getOrNull(pos)?.toInt()?.and(0xFF) ?: return null
    pos += 1
    var length = lengthByte
    if (lengthByte > 0x80) {
        val lengthBytes = lengthByte and 0x7F
        if (pos + lengthBytes > rawFrame.size) return null
        length = 0
        for (i in 0 until lengthBytes) length = (length shl 8) or (rawFrame[pos + i].toInt() and 0xFF)
        pos += lengthBytes
    }

    // Последние 3 байта кадра (CRC + флаг) в данные не входят; при завышенной длине берём что есть.
    val available = (rawFrame.size - 3 - pos).coerceAtLeast(0)
    val take = minOf(length, available)
    return GetResponseBlock(
        lastBlock = lastBlock,
        blockNumber = blockNumber,
        data = rawFrame.copyOfRange(pos, pos + take),
    )
}

/** Разбирает DLMS-значение из «голых» байт (без HDLC и APDU) — для склеенных данных блоков. */
fun decodeDlmsValue(data: ByteArray): DlmsValue = DlmsDecoder(data).decodeAt(0).first

/**
 * Как [unitLabelOf], но для величин, показываемых делёнными на 1000 ([ObisReading.valueKwh]):
 * «Вт·ч» → «кВт·ч». Неизвестный код показывается как «ед.N», а не подменяется догадкой.
 */
fun kiloUnitLabelOf(unit: Int?): String? = when (unit) {
    null -> null
    27 -> "кВт"
    28 -> "кВА"
    29 -> "квар"
    30 -> "кВт·ч"
    31 -> "кВА·ч"
    32 -> "квар·ч"
    else -> unitLabelOf(unit) ?: "ед.$unit"
}

/** Подмножество таблицы единиц IEC 62056-6-2, реально встречающееся у счётчиков. */
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
 * Разбирает «сырой» HDLC-кадр GET-response в список показаний. Значение — массив/структура
 * из повторяющихся троек {OctetString(6) OBIS, число, Structure(2){Integer scaler, Enum unit}},
 * по одной на тариф.
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
 * Пропускает LLC и заголовок APDU GET-response и разбирает DLMS-значение. LLC-заголовок
 * "E6 E7 00" ищется по содержимому, а не по фиксированному смещению, поэтому длина
 * HDLC-адреса не важна.
 *
 * @throws DlmsDecodeException если LLC-заголовок не найден, ответ короткий, сервис не
 *         GET-response или сервер вернул data-access-result вместо данных.
 */
fun parseGetResponseValue(rawFrame: ByteArray): DlmsValue {
    val llcOffset = findLlcHeader(rawFrame)
        ?: throw DlmsDecodeException("В ответе не найден LLC-заголовок сервера (E6 E7 00)")

    // llcOffset указывает на "E6" — далее 3 байта LLC, затем APDU
    var pos = llcOffset + 3
    if (pos + 3 > rawFrame.size) throw DlmsDecodeException("Ответ слишком короткий для GET-response APDU")

    val serviceId = rawFrame[pos].toInt() and 0xFF
    if (serviceId != 0xC4) throw DlmsDecodeException("Ожидался GET-response (0xC4), получено 0x%02X".format(serviceId))
    pos += 3 // service-id, choice (get-response-normal = 0x01), invoke-id-and-priority
    val resultChoice = rawFrame[pos].toInt() and 0xFF
    pos += 1
    if (resultChoice != 0x00) {
        // resultChoice=1: следом код Data-Access-Result (IEC 62056-6-2: 0=success, 1=hardware-fault,
        // 4=object-undefined, 11=no-long-get-in-progress, 13=data-block-number-invalid...)
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
 * Разбирает ACTION-response: «E6 E7 00 C7 01 <invoke-id-and-priority> <Action-Result> <есть ли данные>».
 * Action-Result: 0 = успех, остальное — коды Data-Access-Result. Для РиМ 040.40 ответ на method 2
 * (remote_reconnect) — «E6 E7 00 C7 01 <invoke> 00 00».
 *
 * @return Action-Result или null, если ответ короткий или это не ACTION-response.
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
 * Рекурсивно собирает тройки {OBIS(6 байт), число, [scaler, unit]} и пары {OBIS, OctetString |
 * VisibleString} (дата/время устройства, текстовые поля). Следующая за значением Structure(2),
 * если есть, пропускается как scaler-unit.
 */
private fun collectReadings(value: DlmsValue, out: MutableList<ObisReading>) {
    val items = when (value) {
        is DlmsValue.Struct -> value.items
        is DlmsValue.Arr -> value.items
        else -> return
    }

    var i = 0
    // Главная строка буфера индикации (Struct ~90 элементов; items.size > 2 отличает её от
    // scaler-unit-структур размером 2) начинается позиционными полями БЕЗ обёртки OBIS:
    // [0] Int8/U8 — статус часов, [1] Enum — control_state размыкателя.
    if (items.size > 2 && (items[0] is DlmsValue.I8 || items[0] is DlmsValue.U8) && items.getOrNull(1) is DlmsValue.EnumVal) {
        val clockStatus = numericValueOf(items[0])
        if (clockStatus != null) out.add(ObisReading(CLOCK_STATUS_OBIS, clockStatus, 0, null))
        val controlState = (items[1] as DlmsValue.EnumVal).value
        out.add(ObisReading(RELAY_CONTROL_STATE_OBIS, controlState.toLong(), 0, null))
        i = 2
        // Далее ещё три позиционных поля: [2] Int8 — статус фаз ДД, [3] OctetString не из 6 байт
        // (чтобы не спутать с OBIS) — текущий тариф, [4] UInt32 — статус пломб. Переходим к i=5
        // только если форма совпала целиком; иначе (другая модель/прошивка) остаёмся на i=2, и
        // общий цикл сам досинхронизируется, пропуская неопознанные элементы по одному.
        val phaseItem = items.getOrNull(2)
        val tariffItem = items.getOrNull(3)
        val statusItem = items.getOrNull(4)
        if ((phaseItem is DlmsValue.I8 || phaseItem is DlmsValue.U8) &&
            tariffItem is DlmsValue.OctetStr && tariffItem.bytes.size != 6 &&
            statusItem is DlmsValue.U32
        ) {
            val phaseValue = numericValueOf(phaseItem)
            if (phaseValue != null) out.add(ObisReading(DD_PHASE_DISPLAY_OBIS, phaseValue, 0, null))
            val tariffValue = tariffItem.bytes.firstOrNull()?.let { (it.toInt() and 0xFF).toLong() }
            if (tariffValue != null) {
                out.add(ObisReading(CURRENT_TARIFF_OBIS, tariffValue, 0, null, octetValue = tariffItem.bytes))
            }
            out.add(ObisReading(TAMPER_STATUS_OBIS, statusItem.value, 0, null))
            i = 5
        }
    }
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
                // Печатный ASCII в OctetString — тоже текст (напр. версия ПО 0.0.96.1.2.255 у 189.40:
                // «31 2E 35 38» = «1.58»). octetValue остаётся для нетекстовых (дата/время).
                val text = valueItem.bytes.takeIf { it.isNotEmpty() && it.all { b -> (b.toInt() and 0xFF) in 0x20..0x7E } }
                    ?.let { String(it, Charsets.US_ASCII) }
                out.add(ObisReading(obisCodeOf(obisItem.bytes), 0L, 0, null, octetValue = valueItem.bytes, textValue = text))
                val maybeScalerUnit = items.getOrNull(i + 2)
                i += if (maybeScalerUnit is DlmsValue.Struct && maybeScalerUnit.items.size == 2) 3 else 2
                continue
            }
            // Текстовое служебное поле (напр. версия ПО) прямо в буфере
            if (valueItem is DlmsValue.VisibleStr) {
                out.add(ObisReading(obisCodeOf(obisItem.bytes), 0L, 0, null, textValue = valueItem.text))
                val maybeScalerUnit = items.getOrNull(i + 2)
                i += if (maybeScalerUnit is DlmsValue.Struct && maybeScalerUnit.items.size == 2) 3 else 2
                continue
            }
        }
        // Не тройка/пара — спускаемся внутрь на случай вложенности
        collectReadings(obisItem, out)
        i += 1
    }
}

internal fun obisCodeOf(bytes: ByteArray): String = bytes.joinToString(".") { (it.toInt() and 0xFF).toString() }

/** Компактное представление для логов; OctetStr(6) печатается как OBIS-код. */
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
 * Текстовое представление одиночного значения (напр. версия ПО 0.0.96.1.2.255): VisibleString —
 * как есть; OctetString — ASCII, если все байты печатаемые, иначе HEX; числа — toString();
 * остальное — [describeDlmsValue].
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
 * Числовое значение одиночного атрибута, включая Enum и Bool (напр. размыкатель
 * 0.0.96.3.10.255: Boolean output_state или Enum control_state). null — не число.
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

/** Structure(2){Integer scaler, Enum unit}; при другом формате — scaler=0, unit=Wh. */
private fun scalerUnitOf(value: DlmsValue?): Pair<Int, Int?> {
    if (value is DlmsValue.Struct && value.items.size == 2) {
        val scaler = (value.items[0] as? DlmsValue.I8)?.value
        val unit = (value.items[1] as? DlmsValue.EnumVal)?.value
        if (scaler != null) return scaler to unit
    }
    return 0 to UNIT_WH
}
