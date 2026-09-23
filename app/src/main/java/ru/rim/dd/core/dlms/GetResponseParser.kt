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
    /**
     * [Android-патч] Заполнено вместо rawValue/scaler, если прибор прислал значение напрямую
     * числом с плавающей точкой (тег 0x17/0x18). Так отдаёт данные счётчик AKROS: в его буфере
     * индикации каждое значение — готовый Float32, без пары {scaler, unit}, которой пользуются
     * приборы РиМ. Раскладывать такой float обратно на rawValue+scaler значило бы терять
     * точность на ровном месте, поэтому он хранится как есть, а [value] ниже отдаёт
     * предпочтение ему.
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
 * [Android-патч] см. collectReadings() — control_state размыкателя (0.0.96.3.10.255, класс 70
 * Disconnect Control, атрибут 3) НЕ читается отдельным GET (счётчик отвечает 0x0D — см. историю
 * диагностики readDisconnectControlAttribute() в MeterRepositoryImpl), а приходит ВНУТРИ обычного
 * буфера индикации (0.0.21.0.2.255), который мы и так опрашиваем каждый цикл. Подтверждено двумя
 * независимыми способами: (1) диагностика capture_objects этого буфера (лог "2_8") явно показала
 * элемент [1] = Struct(класс=70, OBIS=0.0.96.3.10.255, атрибут=3) — сразу после элемента [0] =
 * Struct(класс=8 Clock, атрибут=4 "status"); (2) байт-перечисление (Enum) на этой позиции реально
 * меняет значение в момент подтверждённой команды включения реле (лог Text_Document_13: 0x02
 * стабильно во всех циклах ДО команды → 0x01 сразу ПОСЛЕ подтверждённого ACTION-Result=0). Позиция
 * подтверждена на ДВУХ разных моделях счётчика (РиМ 040.40 и 189.40) — совпадает, поэтому не
 * хардкод под одну модель.
 */
const val RELAY_CONTROL_STATE_OBIS = "0.0.96.3.10.255"

/**
 * [Android-патч] см. TamperState.kt и collectReadings() — состояние пломб (корпуса, клеммника),
 * магнитного поля, СВЧ, батареи и превышения лимита мощности НЕ читается отдельными GET на
 * объекты СТО 34.01-5.1-006 (0.0.96.51.0/1/3/4/5.255 — на этом приборе без DLMS-ассоциации это
 * почти наверняка отказ доступа, как и всё остальное, кроме буфера индикации), а приходит
 * ВНУТРИ ТОГО ЖЕ буфера 0.0.21.0.2.255, третьим по счёту позиционным (без обёртки в OBIS) полем
 * ПОСЛЕ control_state размыкателя. OBIS 0.0.96.50.170.255 в этой константе — не код для GET (сам
 * элемент не читается отдельно), а просто "паспортный" адрес этого же значения для справки: имя
 * "Значки ДД" и OBIS подтверждены паспортом объектов прибора (файл-выгрузка объектов, лист
 * Sheet1: строка "Data | 0.0.96.50.170.255 | 0 | Значки ДД"). Битовая раскладка ПОЛНОСТЬЮ
 * расшифрована из исходников прошивки самого пульта РиМ 040.40 — thread_dataRequest.c,
 * функция read_status() (проект RiM_040_40_v1.1.41, ветка "версия ЖКИ 1", реально
 * наблюдаемая в логах: byte1 бит6 = 1 → (byte1&0xC0)>>6 = версия 1). Комментарий прошивки
 * дословно: "байт 1: сборка бит - статус пломб + версия ЖКИ: бит 0 Tamper_Cover.State (замок 2),
 * бит 1 Tamper_Meter.State (замок 1), бит 2 Tamper_Magnet.State, бит 3 Tamper_Battery.State
 * (замок 3), бит 4 СВЧ, бит 5 статус диагностики, биты 6-7 версия ЖКИ; байт 2: те же биты —
 * но "было, сейчас не активно" (FLAG), бит 7 — превышение лимита мощности". Подробный разбор —
 * см. TamperState.decode(). Подтверждено на реальном логе (Text_Document_13, тот же сеанс, что
 * и для control_state реле): сырое значение поля было 0x00410100 → байт1=0x41 → бит0=1
 * (Tamper_Cover=ACTIVE) и биты6-7=01 (версия ЖКИ=1) — правдоподобно для стендового испытания
 * с не до конца закрытым/опломбированным корпусом.
 */
const val TAMPER_STATUS_OBIS = "0.0.96.50.170.255"

/**
 * [Android-патч] Текущий тариф (1..N) — тоже позиционное (без обёртки в OBIS) поле буфера,
 * третье по счёту после control_state реле (см. collectReadings()), ИДЁТ ПЕРЕД
 * TAMPER_STATUS_OBIS. Название и OBIS подтверждены паспортом объектов прибора ("Data |
 * 0.0.96.14.0.255 | 0 | Текущий тариф"). До этого патча значение молча отбрасывалось (общий
 * цикл лишь использовал его форму — OctetString не 6 байт — чтобы не спутать с OBIS-кодом, и
 * пропускал дальше).
 */
const val CURRENT_TARIFF_OBIS = "0.0.96.14.0.255"

/**
 * [Android-патч] Второе позиционное поле буфера (сразу после control_state реле, ПЕРЕД
 * текущим тарифом) — по паспорту объектов прибора: "Data | 1.0.96.50.0.255 | 0 | Отображение
 * фаз ДД". Смысл в точности не расшифрован (не встретился в исходниках прошивки, в отличие от
 * TAMPER_STATUS_OBIS) — предположительно битовая маска "какие фазы пульт умеет/должен
 * отображать" у многофазных приборов; сохраняем как есть, чтобы не терять и разобраться позже
 * по мере накопления логов с других (многофазных) счётчиков.
 */
const val DD_PHASE_DISPLAY_OBIS = "1.0.96.50.0.255"

/**
 * [Android-патч] Статус часов реального времени прибора (Clock, class=8, attribute=4) — самое
 * первое позиционное поле буфера (см. collectReadings()), OBIS подтверждён capture_objects
 * буфера ("Struct(class=8, OBIS=0.0.1.0.0.255, attr=4)"). До этого патча значение читалось
 * ТОЛЬКО чтобы отличить "это правда начало буфера" (форма Int8/U8 + Enum), а само число никуда
 * не попадало. Смысл битов — из комментария прошивки ДД (thread_dataRequest.c) и совпадает со
 * стандартным Clock.status по DLMS: биты 0 (invalid value), 1 (doubtful value) и 3 (invalid
 * clock status) — часы недостоверны, если ЛЮБОЙ из них установлен (маска 0x0B, см.
 * isClockStatusValid() ниже). Бит 2 (другая база времени) в прошивке ДД сознательно НЕ
 * считается ошибкой.
 */
const val CLOCK_STATUS_OBIS = "0.0.1.0.0.255"

/** См. CLOCK_STATUS_OBIS — true, если часы прибора достоверны (как считает прошивка ДД). */
fun isClockStatusValid(rawStatus: Long): Boolean = (rawStatus and 0x0B) == 0L

/** Подмножество таблицы единиц измерения IEC 62056-6-2, реально встречающееся у этого счётчика. */
/**
 * [Android-патч] Один блок ответа, разбитого прибором на части (get-response-with-datablock).
 *
 * Зачем это понадобилось. Ответ на чтение большого атрибута физически не помещается в один
 * HDLC-кадр: при согласовании сеанса счётчик AKROS ограничивает информационное поле 128 байтами,
 * а описание колонок буфера (capture_objects, 14 записей по 18 байт) — это больше 250. В таких
 * случаях DLMS штатно переходит на блочную передачу: вместо обычного ответа «C4 01» прибор
 * присылает «C4 02» с флагом «последний блок», номером блока и куском данных, а клиент должен
 * запрашивать следующие блоки, пока флаг не станет единицей.
 *
 * Пока этого разбора не было, приложение принимало такой ответ за обычный, промахивалось мимо
 * границ полей на пару байт и упиралось в нулевой байт номера блока, который декодировался как
 * «пустое значение» — в логе это выглядело как «описание буфера получено, 0 колонок» при
 * полностью корректном ответе прибора.
 */
data class GetResponseBlock(
    /** true — это последний блок, больше запрашивать нечего. */
    val lastBlock: Boolean,
    /** Номер блока; его нужно указать в запросе следующего блока. */
    val blockNumber: Int,
    /** Кусок данных из этого блока — их нужно склеить с остальными и разобрать целиком. */
    val data: ByteArray,
)

/**
 * Разбирает ответ, если это get-response-with-datablock, и возвращает [GetResponseBlock].
 * null — ответ обычный (get-response-normal) и разбирать его нужно через [parseGetResponseValue].
 */
fun parseGetResponseBlock(rawFrame: ByteArray): GetResponseBlock? {
    val llcOffset = findLlcHeader(rawFrame) ?: return null
    var pos = llcOffset + 3
    // service-id (0xC4) + choice: нас интересует только choice = 2 (with-datablock)
    if (rawFrame.getOrNull(pos)?.toInt()?.and(0xFF) != 0xC4) return null
    if (rawFrame.getOrNull(pos + 1)?.toInt()?.and(0xFF) != 0x02) return null
    pos += 3 // сместились через service-id, choice и invoke-id-and-priority

    val lastBlock = (rawFrame.getOrNull(pos)?.toInt()?.and(0xFF) ?: return null) != 0
    pos += 1
    if (pos + 4 > rawFrame.size) return null
    var blockNumber = 0
    for (i in 0 until 4) blockNumber = (blockNumber shl 8) or (rawFrame[pos + i].toInt() and 0xFF)
    pos += 4

    // result-choice: 0 — дальше «сырые» данные, иначе прибор вернул код ошибки вместо данных.
    if ((rawFrame.getOrNull(pos)?.toInt()?.and(0xFF) ?: return null) != 0) return null
    pos += 1

    // Длина куска данных — в кодировке длины A-XDR: до 0x80 это само число, иначе младшие биты
    // задают, сколько БАЙТ занимает длина.
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

    // Хвост кадра — контрольная сумма и закрывающий флаг; в данные они не входят. Если
    // заявленная длина больше, чем реально осталось, берём что есть: пусть лучше разбор
    // споткнётся на неполных данных, чем мы выйдем за пределы массива.
    val available = (rawFrame.size - 3 - pos).coerceAtLeast(0)
    val take = minOf(length, available)
    return GetResponseBlock(
        lastBlock = lastBlock,
        blockNumber = blockNumber,
        data = rawFrame.copyOfRange(pos, pos + take),
    )
}

/**
 * [Android-патч] Разбирает DLMS-значение из «голых» данных — без HDLC-кадра и APDU вокруг.
 * Нужно для блочной передачи (см. [GetResponseBlock]): куски из всех блоков склеиваются, и
 * получившийся массив байт — это уже само значение, начиная с его тега.
 */
fun decodeDlmsValue(data: ByteArray): DlmsValue = DlmsDecoder(data).decodeAt(0).first

/**
 * [Android-патч] То же, что [unitLabelOf], но для величин, которые показываются делёнными на
 * 1000 (см. ObisReading.valueKwh — энергия и мощность): «Вт·ч» → «кВт·ч», «Вт» → «кВт».
 *
 * Нужно потому, что у прибора единица ВСЕГДА базовая (ватт-часы, ватты — коды 30 и 27), а на
 * экране показания принято показывать в киловаттах. Раньше подпись «кВт·ч» бралась из
 * жёсткого списка по категории энергии — то есть если бы прибор отдал величину в другой
 * единице, подпись всё равно осталась бы прежней и молча соврала. Здесь же преобразуется
 * ровно та единица, которую прибор назвал сам; неизвестный код не заменяется догадкой, а
 * показывается как есть («ед.N»), чтобы это было видно, а не потерялось.
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
    // [Android-патч] см. RELAY_CONTROL_STATE_OBIS выше — специальный случай ДО общего цикла по
    // тройкам {OBIS, значение, scaler-unit}: главная строка буфера (это Struct размером под 90
    // элементов — на порядок больше, чем у ЛЮБОЙ scaler-unit-структуры вида {Integer, Enum},
    // которая встречается ниже per-элементно и всегда размером ровно 2 — условие items.size > 2
    // отсекает именно её и не даёт спутать со строкой буфера) начинается с пары [Int8 (статус
    // часов, class=8 Clock attr=4), Enum (control_state размыкателя)] БЕЗ обёртки в OBIS-код —
    // именно поэтому раньше это значение молча терялось (общий цикл ищет только тройки/пары,
    // начинающиеся с OctetString(6)=OBIS, и просто перешагивал через оба элемента по одному).
    if (items.size > 2 && (items[0] is DlmsValue.I8 || items[0] is DlmsValue.U8) && items.getOrNull(1) is DlmsValue.EnumVal) {
        // [Android-патч] см. CLOCK_STATUS_OBIS выше — раньше items[0] проверялся только на
        // форму (Int8/U8), а само значение никуда не сохранялось.
        val clockStatus = numericValueOf(items[0])
        if (clockStatus != null) out.add(ObisReading(CLOCK_STATUS_OBIS, clockStatus, 0, null))
        val controlState = (items[1] as DlmsValue.EnumVal).value
        out.add(ObisReading(RELAY_CONTROL_STATE_OBIS, controlState.toLong(), 0, null))
        i = 2
        // [Android-патч] см. TAMPER_STATUS_OBIS выше — сразу следом идут ЕЩЁ ТРИ позиционных
        // (без OBIS-обёртки) поля: [2]=Int8 статус фаз ДД, [3]=OctetString(1 байт) текущий
        // тариф (0.0.96.14.0.255), [4]=UInt32 "Значки ДД"/статус пломб (0.0.96.50.170.255).
        // Раньше общий цикл ниже эти три значения молча "проглатывал" по одному (перешагивал,
        // не найдя в них OBIS-код), НЕ ломаясь и НЕ теряя синхронизацию с остальным буфером
        // (см. resync ниже: неопознанный элемент просто пропускается по одному) — но и не
        // извлекая ничего. Проверяем всю тройку СТРОГО по форме (Int8/U8, затем OctetString
        // длиной НЕ 6 — то есть заведомо не OBIS-код, затем ровно U32) и продвигаемся дальше
        // ТОЛЬКО если форма совпала целиком — иначе (другая модель счётчика/прошивки) просто
        // остаёмся на i=2, и общий цикл ниже сам безопасно доберёт синхронизацию как раньше.
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

internal fun obisCodeOf(bytes: ByteArray): String = bytes.joinToString(".") { (it.toInt() and 0xFF).toString() }

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
