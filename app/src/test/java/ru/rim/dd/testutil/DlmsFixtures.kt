package ru.rim.dd.testutil

/**
 * Сборка DLMS-ответов из байтов — для проверки протокольного уровня без прибора. Формат ответов
 * (LLC E6 E7 00, GET-response C4, блоки, ACTION-response C7) такой же, как приходит от нативного
 * клиента в getResponseRawBytes().
 */
object DlmsFixtures {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    /** GET-response-normal с готовым значением: «E6 E7 00 C4 01 <invoke> 00» + значение. */
    fun getResponse(value: ByteArray, invoke: Int = 0xC1): ByteArray =
        bytes(0xE6, 0xE7, 0x00, 0xC4, 0x01, invoke, 0x00) + value

    /** GET-response с ошибкой доступа (data-access-result): «… 01 <код>». */
    fun getResponseError(code: Int, invoke: Int = 0xC1): ByteArray =
        bytes(0xE6, 0xE7, 0x00, 0xC4, 0x01, invoke, 0x01, code)

    /** Блок get-response-with-datablock; в конце три «хвостовых» байта (CRC + флаг), как в кадре. */
    fun block(data: ByteArray, blockNumber: Int, last: Boolean, invoke: Int = 0xC1): ByteArray =
        bytes(0xE6, 0xE7, 0x00, 0xC4, 0x02, invoke, if (last) 1 else 0) +
            bytes(blockNumber ushr 24, blockNumber ushr 16, blockNumber ushr 8, blockNumber) +
            bytes(0x00) + length(data.size) + data + bytes(0x00, 0x00, 0x7E)

    /** Ответ нормального GET делится на блоки по [blockSize] байт; последний помечается флагом. */
    fun splitIntoBlocks(getResponse: ByteArray, blockSize: Int): List<ByteArray> {
        val value = getResponse.copyOfRange(7, getResponse.size)
        val parts = value.toList().chunked(blockSize).map { it.toByteArray() }
        return parts.mapIndexed { i, part -> block(part, blockNumber = i + 1, last = i == parts.lastIndex) }
    }

    /** ACTION-response: «E6 E7 00 C7 01 <invoke> <result> 00». */
    fun actionResponse(result: Int, invoke: Int = 0xC1): ByteArray =
        bytes(0xE6, 0xE7, 0x00, 0xC7, 0x01, invoke, result, 0x00)

    // --- значения A-XDR ---

    private fun length(n: Int): ByteArray = when {
        n < 0x80 -> bytes(n)
        n < 0x100 -> bytes(0x81, n)
        else -> bytes(0x82, n ushr 8, n)
    }

    fun array(items: List<ByteArray>): ByteArray = bytes(0x01) + length(items.size) + items.fold(ByteArray(0)) { a, b -> a + b }

    fun struct(vararg items: ByteArray): ByteArray = bytes(0x02) + length(items.size) + items.fold(ByteArray(0)) { a, b -> a + b }

    fun octets(value: ByteArray): ByteArray = bytes(0x09) + length(value.size) + value

    fun visible(text: String): ByteArray = text.toByteArray(Charsets.US_ASCII).let { bytes(0x0A) + length(it.size) + it }

    fun u16(v: Int): ByteArray = bytes(0x12, v ushr 8, v)

    fun i8(v: Int): ByteArray = bytes(0x0F, v)

    fun enumVal(v: Int): ByteArray = bytes(0x16, v)

    fun float32(v: Float): ByteArray {
        val bits = java.lang.Float.floatToIntBits(v)
        return bytes(0x17, bits ushr 24, bits ushr 16, bits ushr 8, bits)
    }

    /** OBIS «1.0.1.8.0.255» → 6 байт. */
    fun obis(code: String): ByteArray = code.split(".").map { it.toInt() }.let { p -> ByteArray(p.size) { p[it].toByte() } }

    fun obisValue(code: String): ByteArray = octets(obis(code))

    /** Элемент буфера РиМ: пара {OBIS, текст}. */
    fun textEntry(code: String, text: String): ByteArray = struct(obisValue(code), visible(text))

    /** Запись capture_objects: {класс, OBIS, атрибут, индекс данных}. */
    fun captureObject(classId: Int, code: String): ByteArray = struct(u16(classId), obisValue(code), i8(2), u16(0))

    /** scaler_unit: {множитель, единица}. */
    fun scalerUnit(scaler: Int, unit: Int): ByteArray = struct(i8(scaler), enumVal(unit))

    /**
     * Ответы AKROS на запросы со стандартной адресацией: capture_objects (две колонки Register),
     * scaler_unit каждой колонки и компактный буфер с двумя значениями: 12345 Вт·ч и 2301 × 10^-1 В.
     */
    fun akrosGetResponder(): (classId: Int, obis: String, attribute: Int) -> ByteArray? {
        val captured = array(listOf(captureObject(3, "1.0.1.8.0.255"), captureObject(3, "1.0.32.7.0.255")))
        val buffer = array(listOf(struct(float32(12345f), float32(2301f))))
        return { classId, obis, attribute ->
            when {
                classId == 7 && attribute == 3 -> getResponse(captured)
                classId == 7 && attribute == 2 -> getResponse(buffer)
                obis == "1.0.1.8.0.255" -> getResponse(scalerUnit(0, 30))
                obis == "1.0.32.7.0.255" -> getResponse(scalerUnit(-1, 35))
                else -> null
            }
        }
    }

    /** Буфер, в котором прибор называет свой серийный номер (0.0.96.1.0.255). */
    fun bufferWithSerial(serial: String): ByteArray =
        getResponse(array(listOf(textEntry("0.0.96.1.0.255", serial))))
}
