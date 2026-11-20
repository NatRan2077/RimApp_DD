package ru.rim.dd.core.dlms

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import ru.rim.dd.testutil.DlmsFixtures
import ru.rim.dd.testutil.RealMeterBuffer
import java.time.LocalDate
import java.time.LocalTime


/**
 * Разбор ответов прибора: APDU GET-response и ACTION-response, блоки, единицы измерения, дата и
 * время COSEM и раскладка буфера индикации в показания (OBIS + значение × 10^scaler + единица).
 */
class GetResponseParserTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private fun assertDecodeFails(frame: ByteArray, messagePart: String? = null) {
        try {
            parseGetResponseValue(frame)
            fail("ожидалось DlmsDecodeException")
        } catch (e: DlmsDecodeException) {
            if (messagePart != null) assertTrue(e.message, e.message!!.contains(messagePart))
        }
    }

    private fun readingsFrom(vararg entries: ByteArray): List<ObisReading> {
        val root = decodeDlmsValue(DlmsFixtures.struct(*entries))
        return readingsOf(root)
    }

    // GET-response

    @Test
    fun getResponse_returnsValue() {
        val value = parseGetResponseValue(bytes(0xE6, 0xE7, 0x00, 0xC4, 0x01, 0xC1, 0x00, 0x11, 0x2A))
        assertEquals(DlmsValue.U8(42), value)
    }

    @Test
    fun getResponse_llcHeaderIsFoundByContent_notByOffset() {
        // Перед LLC — заголовок HDLC произвольной длины.
        val frame = bytes(0x7E, 0xA0, 0x0B, 0x03, 0x43, 0x03, 0xF4, 0x4F, 0xD9) +
                bytes(0xE6, 0xE7, 0x00, 0xC4, 0x01, 0xAC, 0x00, 0x12, 0x01, 0x00)
        assertEquals(DlmsValue.U16(256), parseGetResponseValue(frame))
    }

    @Test
    fun getResponse_dataAccessError_throwsWithErrorCode() {
        assertDecodeFails(DlmsFixtures.getResponseError(code = 0x0D), "0x0D")
    }

    @Test
    fun getResponse_notAGetResponse_orNoLlc_orTooShort_throws() {
        assertDecodeFails(bytes(0xE6, 0xE7, 0x00, 0xC7, 0x01, 0xC1, 0x00, 0x00), "0xC4")   // ACTION-response
        assertDecodeFails(bytes(1, 2, 3, 4, 5), "LLC")
        assertDecodeFails(bytes(0xE6, 0xE7, 0x00, 0xC4), "коротк")
        assertDecodeFails(ByteArray(0))
    }

    //  блоки

    @Test
    fun block_parsedFromWireFormat() {
        val block = parseGetResponseBlock(DlmsFixtures.block(bytes(0xAA, 0xBB, 0xCC), blockNumber = 2, last = false))
        assertNotNull(block)
        assertFalse(block!!.lastBlock)
        assertEquals(2, block.blockNumber)
        assertArrayEquals(bytes(0xAA, 0xBB, 0xCC), block.data)
    }

    @Test
    fun block_lastFlagAndLongLength() {
        val data = ByteArray(300) { it.toByte() }
        val block = parseGetResponseBlock(DlmsFixtures.block(data, blockNumber = 7, last = true))!!
        assertTrue(block.lastBlock)
        assertEquals(7, block.blockNumber)
        assertArrayEquals(data, block.data)
    }

    @Test
    fun block_notABlockResponse_returnsNull() {
        // обычный GET-response (choice = 1) - не блочный
        assertNull(parseGetResponseBlock(DlmsFixtures.getResponse(DlmsFixtures.u16(5))))
        assertNull(parseGetResponseBlock(bytes(1, 2, 3)))
    }

    @Test
    fun block_truncatedFrame_returnsNull() {
        assertNull(parseGetResponseBlock(bytes(0xE6, 0xE7, 0x00, 0xC4, 0x02, 0xC1)))
        assertNull(parseGetResponseBlock(bytes(0xE6, 0xE7, 0x00, 0xC4, 0x02, 0xC1, 0x01, 0x00, 0x00)))
        // результат-ошибка вместо данных
        assertNull(parseGetResponseBlock(bytes(0xE6, 0xE7, 0x00, 0xC4, 0x02, 0xC1, 0x01, 0, 0, 0, 1, 0x01, 0x0D, 0, 0, 0x7E)))
    }

    @Test
    fun block_declaredLengthLargerThanFrame_takesOnlyWhatIsThere() {
        // длина 50, а данных 2 + 3 служебных байта в конце кадра
        val frame = bytes(0xE6, 0xE7, 0x00, 0xC4, 0x02, 0xC1, 0x01, 0, 0, 0, 1, 0x00, 50, 0x11, 0x07, 0, 0, 0x7E)
        assertArrayEquals(bytes(0x11, 0x07), parseGetResponseBlock(frame)!!.data)
    }

    //  ACTION-response

    @Test
    fun actionResponse_successIsZero_errorsKeepTheirCode() {
        assertEquals(0, parseActionResponseResult(DlmsFixtures.actionResponse(0)))
        assertEquals(0x0D, parseActionResponseResult(DlmsFixtures.actionResponse(0x0D)))
    }

    @Test
    fun actionResponse_otherServiceOrBrokenFrame_returnsNull() {
        assertNull(parseActionResponseResult(DlmsFixtures.getResponse(DlmsFixtures.u16(1))))
        assertNull(parseActionResponseResult(bytes(0xE6, 0xE7, 0x00, 0xC7)))
        assertNull(parseActionResponseResult(bytes(1, 2, 3, 4, 5, 6)))
        assertNull(parseActionResponseResult(ByteArray(0)))
    }

    // единицы

    @Test
    fun unitLabels() {
        assertEquals("В", unitLabelOf(35))
        assertEquals("Гц", unitLabelOf(44))
        assertEquals("А", unitLabelOf(33))
        assertEquals("Вт·ч", unitLabelOf(30))
        assertEquals("вар·ч", unitLabelOf(32))
        assertEquals("°C", unitLabelOf(9))
        assertNull(unitLabelOf(null))
        assertNull(unitLabelOf(999))
    }

    @Test
    fun kiloUnitLabels_forValuesShownDividedBy1000() {
        assertEquals("кВт·ч", kiloUnitLabelOf(30))
        assertEquals("квар·ч", kiloUnitLabelOf(32))
        assertEquals("кВт", kiloUnitLabelOf(27))
        assertEquals("В", kiloUnitLabelOf(35))
        assertEquals("ед.99", kiloUnitLabelOf(99))     // неизвестное не подменяется догадкой
        assertNull(kiloUnitLabelOf(null))
    }

    // статус часов

    @Test
    fun clockStatus_invalidWhenBit0_1_or3IsSet() {
        assertTrue(isClockStatusValid(0))
        assertFalse(isClockStatusValid(0x01))   // invalid value
        assertFalse(isClockStatusValid(0x02))   // doubtful value
        assertFalse(isClockStatusValid(0x08))   // invalid clock status
        assertTrue("другая база времени ошибкой не считается", isClockStatusValid(0x04))
        assertTrue(isClockStatusValid(0x80))
    }

    //дата и время COSEM

    @Test
    fun cosemDate_knownBytes_andNotSpecified() {
        assertEquals(LocalDate.of(2026, 10, 28), decodeCosemDate(bytes(0x07, 0xEA, 10, 28, 3)))
        assertNull("«не задано» (0xFF)", decodeCosemDate(bytes(0xFF, 0xFF, 0xFF, 0xFF, 0xFF)))
        assertNull(decodeCosemDate(bytes(0x07, 0xEA, 13, 1, 1)))      // месяца 13 нет
        assertNull(decodeCosemDate(bytes(0x07, 0xEA)))                  // слишком коротко
    }

    @Test
    fun cosemTime_knownBytes_andNotSpecified() {
        assertEquals(LocalTime.of(12, 30, 45), decodeCosemTime(bytes(12, 30, 45, 0xFF)))
        assertNull(decodeCosemTime(bytes(0xFF, 0xFF, 0xFF, 0xFF)))
        assertNull(decodeCosemTime(bytes(25, 0, 0, 0)))
        assertNull(decodeCosemTime(bytes(12, 30)))
    }

    // раскладка буфера в показания

    private fun triple(obis: String, value: ByteArray, scaler: Int, unit: Int) =
        DlmsFixtures.struct(DlmsFixtures.obisValue(obis), value, DlmsFixtures.scalerUnit(scaler, unit))

    @Test
    fun readings_valueTimesTenToScaler_withUnit() {
        // 174 × 10^1 Вт·ч = 1740 Вт·ч = 1,74 кВт·ч
        val r = readingsOf(decodeDlmsValue(DlmsFixtures.array(listOf(
            DlmsFixtures.struct(
                DlmsFixtures.obisValue("1.0.1.8.1.255"), byteArrayOf(0x06, 0, 0, 0, 174.toByte()), DlmsFixtures.scalerUnit(1, 30),
            ),
        )))).single()
        assertEquals("1.0.1.8.1.255", r.obisCode)
        assertEquals(174L, r.rawValue)
        assertEquals(1, r.scaler)
        assertEquals(30, r.unit)
        assertEquals(1740.0, r.value, 1e-9)
        assertEquals(1.74, r.valueKwh, 1e-9)
        assertEquals(1, r.tariff)
    }

    @Test
    fun readings_negativeScaler_andNegativeValue() {
        val r = readingsFrom(triple("0.0.96.9.0.255", byteArrayOf(0x10, 0x0B, 0x12), -2, 9)).single()   // 2834 × 10^-2 °C
        assertEquals(28.34, r.value, 1e-9)
        assertEquals("°C", r.unitLabel)

        val neg = readingsFrom(triple("1.0.1.7.0.255", byteArrayOf(0x10, 0xFF.toByte(), 0x38), 0, 27)).single()
        assertEquals(-200.0, neg.value, 1e-9)
    }

    @Test
    fun readings_textPair_visibleAndAsciiOctets() {
        val r = readingsFrom(
            DlmsFixtures.textEntry("0.0.96.1.0.255", "04314652"),
            DlmsFixtures.struct(DlmsFixtures.obisValue("0.0.96.1.2.255"), DlmsFixtures.octets("1.58".toByteArray())),
        )
        assertEquals("04314652", r[0].textValue)
        assertEquals("1.58", r[1].textValue)
    }

    @Test
    fun readings_nonPrintableOctets_stayBytes_notText() {
        val dateTime = byteArrayOf(0x07, 0xEA.toByte(), 10, 28, 3, 12, 30, 45, 0, 0, 0, 0)
        val r = readingsFrom(DlmsFixtures.struct(DlmsFixtures.obisValue("0.0.1.0.0.255"), DlmsFixtures.octets(dateTime))).single()
        assertNull(r.textValue)
        assertArrayEquals(dateTime, r.octetValue)
    }

    @Test
    fun readings_emptyOrForeignValue_givesNoReadings() {
        assertTrue(readingsOf(DlmsValue.U8(1)).isEmpty())
        assertTrue(readingsOf(DlmsValue.Arr(emptyList())).isEmpty())
    }

    @Test
    fun readings_realMeterResponse_knownAnswers() {
        val all = RealMeterBuffer.readings().associateBy { it.obisCode }
        assertEquals(22, all.size)

        fun check(obis: String, raw: Long, scaler: Int, unit: Int?) {
            val r = all.getValue(obis)
            assertEquals("$obis raw", raw, r.rawValue)
            assertEquals("$obis scaler", scaler, r.scaler)
            assertEquals("$obis unit", unit, r.unit)
        }
        check("1.0.1.8.1.255", 174, 1, 30)       // активная энергия, тариф 1
        check("1.0.1.8.0.255", 174, 1, 30)       // сумма по тарифам
        check("1.0.4.8.0.255", 41, 1, 32)        // реактивная
        check("0.0.96.9.0.255", 2834, -2, 9)     // температура 28,34 °C
        check("0.0.96.6.3.255", 3637, -3, 35)    // батарейка 3,637 В
        check("0.0.96.3.10.255", 2, 0, null)     // control_state реле (позиционное поле)
        check("0.0.96.50.170.255", 826871552, 0, null)   // «Значки ДД»
    }

    //  ДОПОЛНЕНИЯ

    // байт длины 0x80

    @Test
    fun block_lengthByte0x80_returnsNull() {
        // В A-XDR 0x80 - маркер длинной формы, а не «длина 0». Сейчас код трактует
        // его как длину 0 (условие > 0x80 ложно). По решению разработчика это БАГ:
        // некорректный блок - null.
        val frame = bytes(0xE6, 0xE7, 0x00, 0xC4, 0x02, 0x00, 0, 0, 0, 1, 0x00, 0x00, 0x80) +
                bytes(0x11, 0x22, 0x00, 0x00, 0x7E)
        assertNull(parseGetResponseBlock(frame))
    }

    @Test
    fun getResponse_octetString_length0x80_throws() {
        // В декодере 0x80 в поле длины - DlmsDecodeException (не «длина 0»).
        assertDecodeFails(bytes(0xE6, 0xE7, 0x00, 0xC4, 0x01, 0xC1, 0x00, 0x09, 0x80))
    }

    //  обрыв внутри массива  ЗАДУМКА

    @Test
    fun getResponse_arrayBrokenElement_partialResult() {
        // Задумка: толерантность к обрезанным кадрам приборов. 3 элемента: U8(1), U8(2),
        // затем неизвестный тег 0x42 - возвращаются первые 2 без исключения.
        val v = parseGetResponseValue(bytes(0xE6, 0xE7, 0x00, 0xC4, 0x01, 0xC1, 0x00, 0x01, 0x03, 0x11, 0x01, 0x11, 0x02, 0x42))
        assertEquals(2, (v as DlmsValue.Arr).items.size)
    }

    @Test
    fun getResponse_arrayCountBiggerThanActual_partialResult() {
        // Задумка: заявлено 3, реально 2 - вернутся 2 (сколько есть).
        val v = parseGetResponseValue(bytes(0xE6, 0xE7, 0x00, 0xC4, 0x01, 0xC1, 0x00, 0x01, 0x03, 0x11, 0x01, 0x11, 0x02))
        assertEquals(2, (v as DlmsValue.Arr).items.size)
    }

    //  RealMeterBuffer - полный список OBIS и текстовые поля

    @Test
    fun realMeter_fullObisList() {
        // Эталон полного списка 22 OBIS. Тест должен падать при изменении раскладки.
        val expected = setOf(
            "0.0.1.0.0.255",        // статус часов
            "0.0.96.3.10.255",      // control_state реле
            "1.0.96.50.0.255",      // статус фаз ДД
            "0.0.96.14.0.255",      // текущий тариф
            "0.0.96.50.170.255",    // «Значки ДД»
            "1.0.1.8.0.255", "1.0.1.8.1.255", "1.0.1.8.2.255", "1.0.1.8.3.255", "1.0.1.8.4.255",
            "1.0.2.8.0.255", "1.0.2.8.1.255", "1.0.2.8.2.255", "1.0.2.8.3.255", "1.0.2.8.4.255",
            "1.0.4.8.0.255",
            "0.0.96.9.0.255",       // температура
            "0.0.96.6.3.255",       // батарейка
            "0.0.96.1.0.255",       // серийный номер
            "0.0.96.1.2.255",       // версия ПО
            "1.0.32.7.0.255",       // напряжение
            "1.0.52.7.0.255"        // ток
        )
        assertEquals(expected, RealMeterBuffer.readings().map { it.obisCode }.toSet())
    }

    @Test
    fun realMeter_textAndOctetFields() {
        val all = RealMeterBuffer.readings().associateBy { it.obisCode }
        assertEquals("1.58", all.getValue("0.0.96.1.2.255").textValue)
        val clock = all.getValue("0.0.1.0.0.255")
        assertNotNull(clock.octetValue)
        assertNull(clock.textValue)
    }

    //  ACTION-response - граница длины

    @Test
    fun actionResponse_serviceId0xC7_butTooShortForResult_returnsNull() {
        // service-id = 0xC7, но кадр обрывается до Action-Result (pos + 4 > size)
        assertNull(parseActionResponseResult(bytes(0xE6, 0xE7, 0x00, 0xC7, 0x01, 0x00)))
    }

    //Scaler и unit: неправильная структура не должна давать Wh

    @Test
    fun readings_invalidScalerUnit_doesNotAssumeWh() {
        val entry = DlmsFixtures.struct(
            DlmsFixtures.obisValue("1.0.1.8.0.255"),
            DlmsFixtures.i8(5),
            DlmsFixtures.u16(30) // вместо Structure(2)
        )

        val reading = readingsFrom(entry).single()

        assertEquals(5L, reading.rawValue)
        assertEquals(0, reading.scaler)
        assertNull(reading.unit)
    }

    @Test
    fun readings_scalerUnitWithWrongScalerType_hasNoUnit() {
        val entry = DlmsFixtures.struct(
            DlmsFixtures.obisValue("1.0.1.8.0.255"),
            DlmsFixtures.i8(5),
            DlmsFixtures.struct(
                DlmsFixtures.enumVal(1),
                DlmsFixtures.enumVal(30)
            )
        )

        val reading = readingsFrom(entry).single()

        assertEquals(5L, reading.rawValue)
        assertEquals(0, reading.scaler)
        assertNull(reading.unit)
    }

    @Test
    fun readings_scalerUnitWithWrongUnitType_hasNoUnit() {
        val entry = DlmsFixtures.struct(
            DlmsFixtures.obisValue("1.0.1.8.0.255"),
            DlmsFixtures.i8(5),
            DlmsFixtures.struct(
                DlmsFixtures.i8(1),
                DlmsFixtures.u16(30)
            )
        )

        val reading = readingsFrom(entry).single()

        assertEquals(5L, reading.rawValue)
        assertEquals(1, reading.scaler)
        assertNull(reading.unit)
    }

//Вложенная структура не должна повторно распознаваться как главная строка

    @Test
    fun readings_nestedStructWithMainRowSignature_doesNotDuplicatePositionalReadings() {
        val nested = DlmsFixtures.struct(
            DlmsFixtures.i8(1),
            DlmsFixtures.enumVal(2),
            DlmsFixtures.i8(3)
        )

        val root = DlmsFixtures.struct(
            DlmsFixtures.i8(0),
            DlmsFixtures.enumVal(1),
            nested
        )

        val readings = readingsOf(decodeDlmsValue(root))

        assertEquals(
            1,
            readings.count { it.obisCode == CLOCK_STATUS_OBIS }
        )
        assertEquals(
            1,
            readings.count { it.obisCode == RELAY_CONTROL_STATE_OBIS }
        )
    }

    //Проверка уникальности OBIS в реальном буфере

    @Test
    fun realMeter_obisCodesAreUnique() {
        val readings = RealMeterBuffer.readings()

        val obisCodes = readings.map { it.obisCode }

        assertEquals(
            "В буфере не должно быть повторяющихся OBIS",
            obisCodes.size,
            obisCodes.toSet().size
        )
    }



//ACTION-response: неправильный choice

    @Test
    fun actionResponse_wrongChoice_returnsNull() {
        val frame = byteArrayOf(
            0xE6.toByte(), 0xE7.toByte(), 0x00,
            0xC7.toByte(), 0x02, 0xC1.toByte(), 0x00, 0x00
        )

        assertNull(parseActionResponseResult(frame))
    }



//GET-response: неправильный choice

    @Test
    fun getResponse_wrongChoice_throws() {
        val frame = byteArrayOf(
            0xE6.toByte(), 0xE7.toByte(), 0x00,
            0xC4.toByte(), 0x02, 0xC1.toByte(), 0x00, 0x00
        )

        assertThrows(DlmsDecodeException::class.java) {
            parseGetResponseValue(frame)
        }
    }


//Float: проверка scaler и unit



    @Test
    fun readings_float32_appliesScalerAndUnit() {
        val value = DlmsFixtures.struct(
            DlmsFixtures.obisValue("1.0.32.7.0.255"),
            DlmsFixtures.float32(2301f),
            DlmsFixtures.scalerUnit(-1, 35)
        )

        val r = readingsFrom(value).single()

        assertEquals(230.1 , r.value, 0.001)
        assertEquals(35, r.unit)
        assertEquals("В", r.unitLabel)
    }

    @Test
    fun readings_float64_withoutScalerUnit_keepsOriginalValue() {
        val value = DlmsFixtures.struct(
            DlmsFixtures.obisValue("1.0.32.7.0.255"),
            DlmsFixtures.float32(230.123f)
        )

        val r = readingsFrom(value).single()

        assertEquals(230.123, r.value, 0.001)
        assertNull(r.unit)
    }
}