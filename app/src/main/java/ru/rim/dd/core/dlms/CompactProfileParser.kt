package ru.rim.dd.core.dlms

/**
 * [Android-патч] Разбор «компактного» буфера индикации — того, что присылает счётчик AKROS.
 *
 * ЧЕМ ОН ОТЛИЧАЕТСЯ ОТ БУФЕРА РиМ. У приборов РиМ значения приходят тройками
 * {OBIS-код, значение, scaler+unit} — то есть каждый элемент сам себя называет и сам сообщает
 * свою единицу измерения, и его можно разобрать, ничего заранее не зная (этим занимается
 * collectReadings в GetResponseParser). AKROS отдаёт то же самое иначе: массив из одной
 * структуры, где лежат ТОЛЬКО значения, подряд, без OBIS-кодов и без единиц:
 *
 *     Array(1) → Structure(14): Float32, Float32, … , OctetString(12) дата-время, Enum, Enum,
 *                               OctetString(4) "1.02"
 *
 * Именно поэтому старый разбор возвращал «0 элементов»: он искал OctetString длиной 6 (признак
 * OBIS-кода) и не находил ни одного, хотя данные в ответе были.
 *
 * ОТКУДА БЕРЁТСЯ СМЫСЛ КОЛОНОК И ЕДИНИЦЫ. Оба вопроса решаются штатными средствами DLMS/COSEM,
 * без единой догадки с нашей стороны:
 *
 *  - у объекта Profile Generic (класс 7) атрибут 2 — сам буфер, а атрибут 3 — capture_objects,
 *    список описаний колонок {class_id, OBIS-код, номер атрибута, индекс данных}: прибор сам
 *    говорит, что в какой колонке лежит (см. [parseCaptureObjects]);
 *  - у объектов Register (класс 3) и Extended Register (4) единица и множитель лежат в атрибуте
 *    scaler_unit — структуре {scaler, unit}: прибор сам говорит, в чём измеряется значение и на
 *    какую степень десятки его умножить (см. [parseScalerUnit]).
 *
 * Поэтому здесь нет ни одного зашитого соответствия «колонка N — это напряжение в вольтах»:
 * и смысл, и единица спрашиваются у прибора по отдельности и кэшируются на сеанс.
 */

/**
 * Описание одной колонки буфера из capture_objects.
 *
 * [classId] нужен не для красоты: именно по нему видно, есть ли у объекта единица измерения.
 * У Register (3) и Extended Register (4) она лежит в атрибуте 3, у Demand Register (5) — в
 * атрибуте 4, а у Data (1), Clock (8) или Disconnect Control (70) её нет вовсе, и спрашивать
 * её — значит зря гонять запросы и получать отказ доступа.
 */
data class CaptureColumn(
    val classId: Int,
    val obisCode: String,
    val attributeIndex: Int,
)

/** Множитель и единица измерения объекта — содержимое атрибута scaler_unit. */
data class ScalerUnit(
    /** Степень десятки: физическая величина = значение × 10^scaler. */
    val scaler: Int,
    /** Код единицы по IEC 62056-6-2 (30 = Вт·ч, 35 = В, 44 = Гц…), см. unitLabelOf(). */
    val unit: Int,
) {
    /** Номер атрибута, в котором лежит scaler_unit, для объекта класса [classId]. null — его нет. */
    companion object {
        fun attributeFor(classId: Int): Int? = when (classId) {
            3, 4 -> 3   // Register, Extended Register
            5 -> 4      // Demand Register — у него атрибут 3 занят под last_average_value
            else -> null
        }
    }
}

/**
 * Разбирает ответ на чтение capture_objects (атрибут 3 объекта Profile Generic) и возвращает
 * описания колонок В ТОМ ЖЕ ПОРЯДКЕ, в котором значения придут в буфере.
 *
 * Каждый элемент ответа — структура {class_id, logical_name, attribute_index, data_index}.
 * Элементы, которые разобрать не удалось, дают null НА СВОЁМ МЕСТЕ, а не выбрасываются: иначе
 * сдвинулись бы все последующие колонки и значения разъехались бы по чужим OBIS-кодам — это
 * куда хуже, чем потерять одну колонку.
 */
fun parseCaptureObjects(rawFrame: ByteArray): List<CaptureColumn?> =
    captureColumnsOf(parseGetResponseValue(rawFrame))

/**
 * То же, что [parseCaptureObjects], но из УЖЕ разобранного значения.
 *
 * Отдельный вход нужен из-за блочной передачи: описание колонок не помещается в один кадр,
 * приходит несколькими блоками (см. GetResponseBlock), и разбирать его приходится не из кадра,
 * а из склеенных данных всех блоков.
 */
fun captureColumnsOf(value: DlmsValue): List<CaptureColumn?> {
    val rows = when (value) {
        is DlmsValue.Arr -> value.items
        is DlmsValue.Struct -> value.items
        else -> emptyList()
    }
    return rows.map { row ->
        val items = (row as? DlmsValue.Struct)?.items ?: return@map null
        val obis = items.filterIsInstance<DlmsValue.OctetStr>()
            .firstOrNull { it.bytes.size == 6 }
            ?.let { obisCodeOf(it.bytes) }
            ?: return@map null
        // class_id — первое целое в структуре, номер атрибута — следующее за OBIS-кодом.
        // Порядок полей в capture_objects фиксирован стандартом, но берём их «по смыслу»,
        // а не по жёсткому индексу: лишнее поле в структуре не должно ломать разбор.
        val numbers = items.mapNotNull { longOrBoolValueOf(it)?.toInt() }
        CaptureColumn(
            classId = numbers.firstOrNull() ?: 0,
            obisCode = obis,
            attributeIndex = numbers.getOrNull(1) ?: 2,
        )
    }
}

/**
 * Разбирает ответ на чтение scaler_unit — структуру {scaler, unit}.
 * null, если ответ не похож на неё (например, прибор отказал в доступе к атрибуту).
 */
fun parseScalerUnit(rawFrame: ByteArray): ScalerUnit? = scalerUnitOf(parseGetResponseValue(rawFrame))

/** То же, что [parseScalerUnit], но из уже разобранного значения — см. [captureColumnsOf]. */
fun scalerUnitOf(value: DlmsValue): ScalerUnit? {
    val items = (value as? DlmsValue.Struct)?.items ?: return null
    if (items.size < 2) return null
    val scaler = longOrBoolValueOf(items[0])?.toInt() ?: return null
    val unit = longOrBoolValueOf(items[1])?.toInt() ?: return null
    return ScalerUnit(scaler = scaler, unit = unit)
}

/**
 * Разбирает сам буфер (атрибут 2) и сопоставляет значения с колонками из [columns], подставляя
 * множитель и единицу из [scalerUnits] (ключ — OBIS-код колонки).
 *
 * Буфер — массив записей; берём ПОСЛЕДНЮЮ: у профиля индикации запись обычно одна, но если
 * прибор отдаст несколько, свежая всегда последняя, и показывать нужно именно её.
 *
 * Колонки без описания (null) или без пары в [columns] пропускаются — лучше отдать меньше
 * значений, чем подписать их наугад.
 */
fun parseCompactBuffer(
    rawFrame: ByteArray,
    columns: List<CaptureColumn?>,
    scalerUnits: Map<String, ScalerUnit> = emptyMap(),
): List<ObisReading> = compactReadingsOf(parseGetResponseValue(rawFrame), columns, scalerUnits)

/**
 * То же, что [parseCompactBuffer], но из УЖЕ разобранного значения — см. [captureColumnsOf].
 *
 * Сам буфер тоже приходит блоками: четырнадцать колонок с датой-временем и текстом версии ПО
 * заведомо не помещаются в согласованные 128 байт информационного поля, поэтому разбирать его
 * приходится не из кадра, а из склеенных данных всех блоков.
 */
fun compactReadingsOf(
    value: DlmsValue,
    columns: List<CaptureColumn?>,
    scalerUnits: Map<String, ScalerUnit> = emptyMap(),
): List<ObisReading> {
    val row = when (value) {
        is DlmsValue.Arr -> value.items.lastOrNull() as? DlmsValue.Struct
        is DlmsValue.Struct -> value
        else -> null
    } ?: return emptyList()

    return row.items.mapIndexedNotNull { index, item ->
        val column = columns.getOrNull(index) ?: return@mapIndexedNotNull null
        readingOf(column.obisCode, item, scalerUnits[column.obisCode])
    }
}

/**
 * Превращает одно значение колонки в [ObisReading], применяя множитель и единицу, которые
 * прибор сообщил в scaler_unit.
 *
 * Множитель применяется и к вещественным значениям тоже: по стандарту физическая величина —
 * это ВСЕГДА значение × 10^scaler, независимо от того, каким типом оно закодировано. Если
 * scaler_unit для объекта прочитать не удалось, остаётся множитель 0 и неизвестная единица —
 * значение показывается как есть, а не подменяется догадкой.
 */
private fun readingOf(obis: String, item: DlmsValue, scalerUnit: ScalerUnit?): ObisReading? {
    val scaler = scalerUnit?.scaler ?: 0
    val unit = scalerUnit?.unit
    return when (item) {
        is DlmsValue.Float32Val ->
            ObisReading(obis, 0L, 0, unit, floatValue = item.value.toDouble() * pow10(scaler))
        is DlmsValue.Float64Val ->
            ObisReading(obis, 0L, 0, unit, floatValue = item.value * pow10(scaler))
        is DlmsValue.OctetStr -> ObisReading(obis, 0L, 0, unit, octetValue = item.bytes)
        is DlmsValue.VisibleStr -> ObisReading(obis, 0L, 0, unit, textValue = item.text)
        // Целые и перечисления кладём в rawValue вместе со scaler — ровно так же, как это делает
        // разбор буфера РиМ, поэтому дальше по коду (реле, тарифы, статусы) разницы никакой.
        else -> longOrBoolValueOf(item)?.let { ObisReading(obis, it, scaler, unit) }
    }
}

private fun pow10(scaler: Int): Double = Math.pow(10.0, scaler.toDouble())
