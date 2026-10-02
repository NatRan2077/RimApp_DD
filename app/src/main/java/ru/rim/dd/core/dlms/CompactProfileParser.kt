package ru.rim.dd.core.dlms

// Разбор «компактного» буфера индикации AKROS. В отличие от РиМ (тройки {OBIS, значение,
// scaler+unit}, см. collectReadings), AKROS отдаёт массив из одной структуры, где лежат ТОЛЬКО
// значения подряд, без OBIS-кодов и единиц:
//
//     Array(1) → Structure(14): Float32, Float32, … , OctetString(12) дата-время, Enum, Enum,
//                               OctetString(4) "1.02"
//
// Смысл колонок и единицы спрашиваются у прибора штатными средствами DLMS и кэшируются на сеанс:
//  - Profile Generic (класс 7): атрибут 3 capture_objects — описания колонок буфера (атрибут 2);
//  - Register (3) / Extended Register (4) / Demand Register (5): атрибут scaler_unit {scaler, unit}.

/**
 * Описание одной колонки буфера из capture_objects. [classId] определяет, есть ли у объекта
 * единица измерения (см. [ScalerUnit.attributeFor]): у Data (1), Clock (8), Disconnect Control (70)
 * её нет, и запрашивать её бессмысленно.
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
    companion object {
        /** Номер атрибута scaler_unit для класса [classId]; null — у класса его нет. */
        fun attributeFor(classId: Int): Int? = when (classId) {
            3, 4 -> 3   // Register, Extended Register
            5 -> 4      // Demand Register — у него атрибут 3 занят под last_average_value
            else -> null
        }
    }
}

/**
 * Описания колонок из ответа на чтение capture_objects, в том же порядке, в каком значения придут
 * в буфере. Элемент, который не удалось разобрать, даёт null НА СВОЁМ МЕСТЕ — иначе остальные
 * колонки сдвинулись бы и значения разъехались по чужим OBIS-кодам.
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
        // Поля берём «по смыслу», а не по индексу: class_id — первое целое, номер атрибута —
        // второе, лишнее поле в структуре не должно ломать разбор.
        val numbers = items.mapNotNull { longOrBoolValueOf(it)?.toInt() }
        CaptureColumn(
            classId = numbers.firstOrNull() ?: 0,
            obisCode = obis,
            attributeIndex = numbers.getOrNull(1) ?: 2,
        )
    }
}

/** Разбирает scaler_unit — структуру {scaler, unit}; null, если значение на неё не похоже. */
fun scalerUnitOf(value: DlmsValue): ScalerUnit? {
    val items = (value as? DlmsValue.Struct)?.items ?: return null
    if (items.size < 2) return null
    val scaler = longOrBoolValueOf(items[0])?.toInt() ?: return null
    val unit = longOrBoolValueOf(items[1])?.toInt() ?: return null
    return ScalerUnit(scaler = scaler, unit = unit)
}

/**
 * Сопоставляет значения буфера с колонками [columns], подставляя множитель и единицу из
 * [scalerUnits] (ключ — OBIS-код колонки). Берётся ПОСЛЕДНЯЯ запись буфера (свежая). Колонки без
 * описания (null) или без пары в [columns] пропускаются — лучше меньше значений, чем наугад.
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
 * Значение колонки → [ObisReading] с множителем и единицей из scaler_unit. Множитель по стандарту
 * применяется и к вещественным значениям. Если scaler_unit прочитать не удалось — множитель 0 и
 * неизвестная единица: значение показывается как есть, без догадок.
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
        // Целые и Enum — в rawValue вместе со scaler, как в разборе буфера РиМ
        else -> longOrBoolValueOf(item)?.let { ObisReading(obis, it, scaler, unit) }
    }
}

private fun pow10(scaler: Int): Double = Math.pow(10.0, scaler.toDouble())
