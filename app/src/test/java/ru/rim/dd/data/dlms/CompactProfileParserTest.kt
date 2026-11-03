package ru.rim.dd.core.dlms

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.rim.dd.testutil.DlmsFixtures

/**
 * «Компактный» буфер AKROS: значения идут подряд без OBIS, смысл колонок и единицы читаются у
 * прибора отдельно. Ошибка в сопоставлении колонок дала бы правдоподобные, но чужие числа.
 */
class CompactProfileParserTest {

    private fun decode(raw: ByteArray) = decodeDlmsValue(raw)

    private fun captureList(vararg columns: ByteArray) = decode(DlmsFixtures.array(columns.toList()))

    private fun row(vararg items: ByteArray) = decode(DlmsFixtures.array(listOf(DlmsFixtures.struct(*items))))

    private val energy = CaptureColumn(3, "1.0.1.8.0.255", 2)
    private val voltage = CaptureColumn(3, "1.0.32.7.0.255", 2)

    // ---------- capture_objects ----------

    @Test
    fun captureColumns_parsedInOrder() {
        val columns = captureColumnsOf(
            captureList(DlmsFixtures.captureObject(3, "1.0.1.8.0.255"), DlmsFixtures.captureObject(8, "0.0.1.0.0.255")),
        )
        assertEquals(listOf(energy, CaptureColumn(8, "0.0.1.0.0.255", 2)), columns)
    }

    @Test
    fun captureColumns_unparsableRowKeepsItsPosition_asNull() {
        // Если выбросить нечитаемую колонку, следующие сдвинутся и значения разъедутся по чужим OBIS.
        val columns = captureColumnsOf(
            captureList(
                DlmsFixtures.captureObject(3, "1.0.1.8.0.255"),
                DlmsFixtures.u16(5),                                             // не структура
                DlmsFixtures.struct(DlmsFixtures.u16(3), DlmsFixtures.octets(byteArrayOf(1, 2, 3))),   // OBIS не из 6 байт
                DlmsFixtures.captureObject(3, "1.0.32.7.0.255"),
            ),
        )
        assertEquals(listOf(energy, null, null, voltage), columns)
    }

    @Test
    fun captureColumns_notACollection_isEmpty() {
        assertTrue(captureColumnsOf(DlmsValue.U8(1)).isEmpty())
    }

    // ---------- scaler_unit ----------

    @Test
    fun scalerUnit_parsed_andRejectsOtherShapes() {
        assertEquals(ScalerUnit(-1, 35), scalerUnitOf(decode(DlmsFixtures.scalerUnit(-1, 35))))
        assertEquals(ScalerUnit(3, 30), scalerUnitOf(decode(DlmsFixtures.scalerUnit(3, 30))))
        assertNull(scalerUnitOf(DlmsValue.U8(1)))
        assertNull(scalerUnitOf(decode(DlmsFixtures.struct(DlmsFixtures.i8(1)))))
    }

    @Test
    fun scalerUnitAttribute_dependsOnClass() {
        assertEquals(3, ScalerUnit.attributeFor(3))      // Register
        assertEquals(3, ScalerUnit.attributeFor(4))      // Extended Register
        assertEquals(4, ScalerUnit.attributeFor(5))      // Demand Register
        assertNull(ScalerUnit.attributeFor(1))           // Data — единицы нет
        assertNull(ScalerUnit.attributeFor(8))           // Clock
        assertNull(ScalerUnit.attributeFor(70))          // Disconnect Control
    }

    // ---------- значения буфера ----------

    @Test
    fun compactReadings_mapValuesToColumns_withScalerAndUnit() {
        val readings = compactReadingsOf(
            row(DlmsFixtures.float32(12345f), DlmsFixtures.float32(2301f)),
            listOf(energy, voltage),
            mapOf("1.0.1.8.0.255" to ScalerUnit(0, 30), "1.0.32.7.0.255" to ScalerUnit(-1, 35)),
        )
        assertEquals(2, readings.size)
        assertEquals("1.0.1.8.0.255", readings[0].obisCode)
        assertEquals(12345.0, readings[0].value, 1e-6)
        assertEquals(30, readings[0].unit)
        assertEquals("1.0.32.7.0.255", readings[1].obisCode)
        assertEquals("множитель применяется и к вещественным значениям", 230.1, readings[1].value, 1e-3)
        assertEquals(35, readings[1].unit)
    }

    @Test
    fun compactReadings_withoutScalerUnit_valueIsShownAsIs_withoutUnit() {
        val r = compactReadingsOf(row(DlmsFixtures.float32(2301f)), listOf(voltage)).single()
        assertEquals(2301.0, r.value, 1e-6)
        assertNull(r.unit)
    }

    @Test
    fun compactReadings_nullColumnSkipsItsValue_butKeepsOthersAligned() {
        val readings = compactReadingsOf(
            row(DlmsFixtures.float32(1f), DlmsFixtures.float32(2f), DlmsFixtures.float32(3f)),
            listOf(energy, null, voltage),
        )
        assertEquals(listOf("1.0.1.8.0.255", "1.0.32.7.0.255"), readings.map { it.obisCode })
        assertEquals(listOf(1.0, 3.0), readings.map { it.value })
    }

    @Test
    fun compactReadings_valuesBeyondColumns_areDropped() {
        val readings = compactReadingsOf(row(DlmsFixtures.float32(1f), DlmsFixtures.float32(2f)), listOf(energy))
        assertEquals(1, readings.size)
    }

    @Test
    fun compactReadings_usesLastRecordOfBuffer() {
        val value = decode(
            DlmsFixtures.array(
                listOf(
                    DlmsFixtures.struct(DlmsFixtures.float32(1f)),
                    DlmsFixtures.struct(DlmsFixtures.float32(2f)),
                ),
            ),
        )
        assertEquals(2.0, compactReadingsOf(value, listOf(energy)).single().value, 1e-9)
    }

    @Test
    fun compactReadings_integerTextAndBytesColumns() {
        val clock = CaptureColumn(8, "0.0.1.0.0.255", 2)
        val version = CaptureColumn(1, "0.0.96.1.2.255", 2)
        val tariff = CaptureColumn(1, "0.0.96.14.0.255", 2)
        val dateTime = byteArrayOf(0x07, 0xEA.toByte(), 10, 28, 3, 12, 30, 45, 0, 0, 0, 0)
        val readings = compactReadingsOf(
            row(
                DlmsFixtures.octets(dateTime),
                DlmsFixtures.visible("1.02"),
                DlmsFixtures.enumVal(2),
            ),
            listOf(clock, version, tariff),
        )
        assertArrayEquals(dateTime, readings[0].octetValue)
        assertEquals("1.02", readings[1].textValue)
        assertEquals(2L, readings[2].rawValue)
    }

    @Test
    fun compactReadings_notABuffer_isEmpty() {
        assertTrue(compactReadingsOf(DlmsValue.U8(1), listOf(energy)).isEmpty())
        assertTrue(compactReadingsOf(DlmsValue.Arr(emptyList()), listOf(energy)).isEmpty())
        assertNotNull(compactReadingsOf(row(DlmsFixtures.float32(1f)), emptyList()))
    }
}
