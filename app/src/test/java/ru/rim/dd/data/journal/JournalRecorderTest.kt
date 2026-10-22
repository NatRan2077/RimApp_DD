package ru.rim.dd.data.journal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.rim.dd.core.dlms.ObisReading
import ru.rim.dd.core.dlms.kiloUnitLabelOf
import ru.rim.dd.core.model.NetworkParams
import ru.rim.dd.core.model.PhaseValues
import ru.rim.dd.core.model.Reading
import ru.rim.dd.data.mapper.MeterBufferMapper
import ru.rim.dd.testutil.RealMeterBuffer
import java.time.Instant

/** Формат карточки журнала на реальном буфере прибора. */
class JournalRecorderTest {

    private val now: Instant = Instant.parse("2026-10-15T12:00:00Z")
    private val buffer: List<ObisReading> = RealMeterBuffer.readings()
    private val energy: List<Reading> = MeterBufferMapper.energyReadings(buffer, now)!!
    private val kwh: String? = kiloUnitLabelOf(30)
    private val kvarh: String? = kiloUnitLabelOf(32)

    @Test
    fun energyLines_skipRdchAndSortByCategoryThenTariff() {
        val lines = JournalRecorder.energyLines(energy).map { it.label to it.value }
        assertEquals(
            listOf(
                "A+ · сумма" to "1,740 $kwh",
                "A+ · Т1" to "1,740 $kwh",
                "A+ · Т2" to "0,000 $kwh",
                "A+ · Т3" to "0,000 $kwh",
                "R− · сумма" to "0,410 $kvarh",
            ),
            lines,
        )
    }

    @Test
    fun phases_singleAndThreePhase() {
        assertEquals("230,0 В", JournalRecorder.phases(PhaseValues(total = 230.0), 1, "В"))
        assertEquals(
            "L1 230,0 · L2 231,5 · L3 229,8 В",
            JournalRecorder.phases(PhaseValues(total = 230.0, l1 = 230.0, l2 = 231.5, l3 = 229.8), 1, "В"),
        )
        assertEquals(
            "L1 1,250 · L2 0,000 · L3 3,000 А",
            JournalRecorder.phases(PhaseValues(total = 1.25, l1 = 1.25, l2 = 0.0, l3 = 3.0), 3, "А"),
        )
    }

    @Test
    fun snapshot_realBuffer() {
        val info = MeterBufferMapper.meterInfo(buffer, null, "04314652", null, now)!!.info
            .copy(model = "РиМ 489.01")
        val network = MeterBufferMapper.networkParams(buffer)
        val s = JournalRecorder.snapshotOf(info, network, energy, reportedSerial = null)
        // префикс «РиМ» в типе отбрасывается, номер — из BLE-имени (прибор свой не сообщил)
        assertEquals("489.01", s.meterType)
        assertEquals("04314652", s.serialNumber)
        assertEquals(5, s.energy.size)
        assertEquals("0,0 В", s.voltage)
        assertEquals("0,000 А", s.current)
    }

    @Test
    fun snapshot_reportedSerialWinsOverBleName() {
        val info = MeterBufferMapper.blankMeterInfo("11111111")
        val s = JournalRecorder.snapshotOf(info, null, emptyList(), reportedSerial = "22222222")
        assertEquals("22222222", s.serialNumber)
        assertNull(s.voltage)
        assertNull(s.current)
    }

    @Test
    fun snapshot_threePhaseFromNetworkParams() {
        val net = NetworkParams(
            voltage = PhaseValues(230.0, 230.0, 231.0, 229.0),
            current = PhaseValues(1.0, 1.0, 2.0, 3.0),
            power = PhaseValues(0.0),
        )
        val s = JournalRecorder.snapshotOf(null, net, emptyList(), null)
        assertNull(s.meterType)
        assertEquals("L1 230,0 · L2 231,0 · L3 229,0 В", s.voltage)
        assertEquals("L1 1,000 · L2 2,000 · L3 3,000 А", s.current)
    }
}