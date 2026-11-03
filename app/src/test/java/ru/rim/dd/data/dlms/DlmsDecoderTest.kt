package ru.rim.dd.core.dlms

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Разбор A-XDR-значений DLMS: каждый тип данных, длины, вложенность и поведение на обрезанных
 * или неизвестных данных. Ошибка здесь означала бы неверные числа на экране.
 */
class DlmsDecoderTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private fun decode(vararg v: Int) = DlmsDecoder(bytes(*v)).decodeAt(0)

    private fun assertValue(expected: DlmsValue, nextPos: Int, vararg input: Int) {
        val (value, next) = decode(*input)
        assertEquals(expected, value)
        assertEquals("позиция сразу после значения", nextPos, next)
    }

    private fun assertFails(vararg input: Int) {
        try {
            decode(*input)
            fail("ожидалось DlmsDecodeException для ${input.joinToString(" ") { "%02X".format(it) }}")
        } catch (e: DlmsDecodeException) {
            // так и должно быть
        }
    }

    @Test
    fun unsignedIntegers() {
        assertValue(DlmsValue.U8(42), 2, 0x11, 0x2A)
        assertValue(DlmsValue.U8(255), 2, 0x11, 0xFF)
        assertValue(DlmsValue.U16(256), 3, 0x12, 0x01, 0x00)
        assertValue(DlmsValue.U32(826871552L), 5, 0x06, 0x31, 0x49, 0x0F, 0x00)
        assertValue(DlmsValue.U32(4294967295L), 5, 0x06, 0xFF, 0xFF, 0xFF, 0xFF)
        assertValue(DlmsValue.U64(1L shl 40), 9, 0x15, 0, 0, 1, 0, 0, 0, 0, 0)
    }

    @Test
    fun signedIntegers_negativeValues() {
        assertValue(DlmsValue.I8(-2), 2, 0x0F, 0xFE)
        assertValue(DlmsValue.I8(-124), 2, 0x0F, 0x84)
        assertValue(DlmsValue.I16(-200), 3, 0x10, 0xFF, 0x38)
        assertValue(DlmsValue.I32(-1), 5, 0x05, 0xFF, 0xFF, 0xFF, 0xFF)
        assertValue(DlmsValue.I64(-1), 9, 0x14, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF)
    }

    @Test
    fun enumBooleanAndNull() {
        assertValue(DlmsValue.EnumVal(30), 2, 0x16, 0x1E)
        assertValue(DlmsValue.BoolVal(true), 2, 0x03, 0x01)
        assertValue(DlmsValue.BoolVal(false), 2, 0x03, 0x00)
        assertValue(DlmsValue.NullVal, 1, 0x00)
    }

    @Test
    fun floatingPoint() {
        assertValue(DlmsValue.Float32Val(1.5f), 5, 0x17, 0x3F, 0xC0, 0x00, 0x00)
        assertValue(DlmsValue.Float32Val(-2.0f), 5, 0x17, 0xC0, 0x00, 0x00, 0x00)
        assertValue(DlmsValue.Float64Val(1.5), 9, 0x18, 0x3F, 0xF8, 0, 0, 0, 0, 0, 0)
    }

    @Test
    fun octetString_keepsBytes() {
        val (value, next) = decode(0x09, 0x03, 0xAA, 0xBB, 0xCC)
        assertArrayEquals(bytes(0xAA, 0xBB, 0xCC), (value as DlmsValue.OctetStr).bytes)
        assertEquals(5, next)
    }

    @Test
    fun visibleString_isAscii() {
        assertValue(DlmsValue.VisibleStr("1.02"), 6, 0x0A, 0x04, 0x31, 0x2E, 0x30, 0x32)
        assertValue(DlmsValue.VisibleStr(""), 2, 0x0A, 0x00)
    }

    @Test
    fun longLengths_bothForms() {
        val oneByte = ByteArray(2 + 1 + 200).also { it[0] = 0x09; it[1] = 0x81.toByte(); it[2] = 200.toByte() }
        val (v1, n1) = DlmsDecoder(oneByte).decodeAt(0)
        assertEquals(200, (v1 as DlmsValue.OctetStr).bytes.size)
        assertEquals(203, n1)

        val twoBytes = ByteArray(1 + 3 + 300).also { it[0] = 0x09; it[1] = 0x82.toByte(); it[2] = 0x01; it[3] = 0x2C }
        val (v2, n2) = DlmsDecoder(twoBytes).decodeAt(0)
        assertEquals(300, (v2 as DlmsValue.OctetStr).bytes.size)
        assertEquals(304, n2)
    }

    @Test
    fun structure_andArray_includingNesting() {
        assertValue(
            DlmsValue.Struct(listOf(DlmsValue.U8(1), DlmsValue.U8(2))), 6,
            0x02, 0x02, 0x11, 0x01, 0x11, 0x02,
        )
        assertValue(
            DlmsValue.Arr(listOf(DlmsValue.Struct(listOf(DlmsValue.I8(-1), DlmsValue.EnumVal(35))))), 8,
            0x01, 0x01, 0x02, 0x02, 0x0F, 0xFF, 0x16, 0x23,
        )
        assertValue(DlmsValue.Arr(emptyList()), 2, 0x01, 0x00)
    }

    @Test
    fun decodeAtOffset_readsFromGivenPosition() {
        val data = bytes(0xFF, 0xFF, 0x11, 0x07, 0x11, 0x08)
        val (first, next) = DlmsDecoder(data).decodeAt(2)
        assertEquals(DlmsValue.U8(7), first)
        assertEquals(DlmsValue.U8(8), DlmsDecoder(data).decodeAt(next).first)
    }

    @Test
    fun truncatedScalar_throws() {
        assertFails(0x06, 0x00, 0x00)              // U32 без двух байт
        assertFails(0x12, 0x01)                    // U16 без байта
        assertFails(0x11)                          // U8 без значения
        assertFails(0x17, 0x3F, 0xC0)              // Float32 обрезан
    }

    @Test
    fun truncatedString_throws() {
        assertFails(0x09, 0x05, 0x01, 0x02)        // обещано 5 байт, есть 2
        assertFails(0x0A, 0x03, 0x31)
        assertFails(0x09, 0x82, 0x01)              // длина обрезана
    }

    @Test
    fun emptyInput_andUnknownTag_throw() {
        assertFails()
        assertFails(0x7F, 0x01)
        assertFails(0x13, 0x00)                    // тег, которого нет в разборе
    }

    @Test
    fun truncatedCollection_returnsWhatWasRead_insteadOfThrowing() {
        // Обещано 3 элемента, есть 1. Разбор не падает: целостность ответа проверяет сборка блоков
        // (последний ли блок), поэтому обрывок до разбора не доходит — см. DlmsSessionClientTest.
        val (value, next) = decode(0x02, 0x03, 0x11, 0x01)
        assertEquals(DlmsValue.Struct(listOf(DlmsValue.U8(1))), value)
        assertEquals(4, next)
    }

    @Test
    fun decodeDlmsValue_decodesFromStart() {
        assertEquals(DlmsValue.U16(300), decodeDlmsValue(bytes(0x12, 0x01, 0x2C)))
        assertTrue(runCatching { decodeDlmsValue(ByteArray(0)) }.exceptionOrNull() is DlmsDecodeException)
    }
}
