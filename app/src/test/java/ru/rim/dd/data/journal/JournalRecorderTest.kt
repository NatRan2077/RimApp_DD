package ru.rim.dd.data.journal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.rim.dd.core.dlms.ObisReading
import ru.rim.dd.core.dlms.kiloUnitLabelOf
import ru.rim.dd.core.model.NetworkParams
import ru.rim.dd.core.model.PhaseValues
import ru.rim.dd.core.model.Reading
import ru.rim.dd.data.mapper.MeterBufferMapper
import ru.rim.dd.testutil.RealMeterBuffer
import java.time.Instant
import io.mockk.mockk
import io.mockk.verify
import io.mockk.every
import ru.rim.dd.data.local.JournalWriter
import ru.rim.dd.core.model.EnergyCategory


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
            JournalRecorder.phases(
                PhaseValues(total = 230.0, l1 = 230.0, l2 = 231.5, l3 = 229.8),
                1,
                "В"
            ),
        )
        assertEquals(
            "L1 1,250 · L2 0,000 · L3 3,000 А",
            JournalRecorder.phases(
                PhaseValues(total = 1.25, l1 = 1.25, l2 = 0.0, l3 = 3.0),
                3,
                "А"
            ),
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

    //доп
    //чисто для покрытия через мок
    @Test
    fun begin_delegates_to_journal() {
        val journal = mockk<JournalWriter>(relaxed = true)
        val recorder = JournalRecorder(journal)

        recorder.begin()

        verify(exactly = 1) { journal.begin() }
    }

    @Test
    fun discard_delegates_to_journal() {
        val journal = mockk<JournalWriter>(relaxed = true)
        val recorder = JournalRecorder(journal)

        recorder.discard()

        verify(exactly = 1) { journal.discard() }
    }

    @Test
    fun end_delegates_to_journal() {
        val journal = mockk<JournalWriter>(relaxed = true)
        val recorder = JournalRecorder(journal)

        recorder.end()

        verify(exactly = 1) { journal.end() }
    }

    @Test
    fun commit_returns_value_from_journal() {
        val journal = mockk<JournalWriter>(relaxed = true)
        every { journal.commit() } returns true
        val recorder = JournalRecorder(journal)

        val result = recorder.commit()

        assertTrue(result)
        verify(exactly = 1) { journal.commit() }
    }

    @Test
    fun onIncomingBytes_delegates_to_journal() {
        val journal = mockk<JournalWriter>(relaxed = true)
        val recorder = JournalRecorder(journal)
        val chunk = byteArrayOf(1, 2, 3)

        recorder.onIncomingBytes(chunk)

        verify(exactly = 1) { journal.appendBytes(chunk) }
    }

    @Test
    fun record_calls_journal_update_with_correct_snapshot() {
        val journal = mockk<JournalWriter>(relaxed = true)
        val recorder = JournalRecorder(journal)

        val info = MeterBufferMapper.blankMeterInfo("11111111")
        val network = NetworkParams(
            voltage = PhaseValues(230.0),
            current = PhaseValues(1.0),
            power = PhaseValues(0.0)
        )

        recorder.record(info, network, energy, "22222222")

        verify(exactly = 1) {
            journal.update(
                meterType = any(),
                serialNumber = "22222222", // проверяем приоритет reportedSerial
                energy = any(),
                voltage = any(),
                current = any()
            )
        }
    }

    @Test
    fun energyLines_coversAllEnergyCategories() {
        // Создаём Reading для всех категорий, которые нас интересуют
        val readings = listOf(
            Reading("1.8.1", 1, 1.0, Instant.parse("2026-11-24T08:23:00Z"), "кВт·ч", false),
            Reading("2.8.2", null, 2.0, Instant.parse("2026-11-24T08:23:00Z"), "кВт·ч", false),
            Reading("3.8.3", 2, 3.0, Instant.parse("2026-11-24T08:23:00Z"), "квар·ч", false),
            Reading("4.8.4", null, 4.0, Instant.parse("2026-11-24T08:23:00Z"), "квар·ч", false),
            Reading("1.8.0", null, 5.0, Instant.parse("2026-11-24T08:23:00Z"), "кВт·ч", false)
        )

        val lines = JournalRecorder.energyLines(readings).map { it.label to it.value }

        assertEquals(
            listOf(
                "A+ · Т1" to "1,000 кВт·ч",
                "A− · сумма" to "2,000 кВт·ч",
                "R+ · Т2" to "3,000 квар·ч",
                "R− · сумма" to "4,000 квар·ч"
            ),
            lines
        )
    }

    // Не тестируемо
    //.filter { !it.isRdch && it.category != EnergyCategory.OTHER }
    /* @Test
    fun energyLines_coversOtherEnergyCategories() {
        // Создаём Reading для всех категорий, которые нас интересуют
        val readings = listOf(
            Reading("5.8.0",null,5.0,Instant.parse("2026-11-24T08:23:00Z"),"кВт·ч", true)
        )

        val lines = JournalRecorder.energyLines(readings).map { it.label to it.value }

        assertEquals(
            listOf(
                "Прочее" to "5,000 кВт·ч"
            ),
            lines
        )
    }
*/

    // так и должно с тру не работать?
    @Test
    fun energyLines_skipsRdch() {
        val readings = listOf(
            Reading("1.8.1", 1, 1.0, Instant.parse("2026-11-24T08:23:00Z"), "кВт·ч", true),
            Reading("1.8.1", 1, 2.0, Instant.parse("2026-11-24T08:23:00Z"), "кВт·ч", false)
        )
        val lines = JournalRecorder.energyLines(readings).map { it.label to it.value }
        assertEquals(listOf("A+ · Т1" to "2,000 кВт·ч"), lines)
    }

    //проверить
    @Test
    fun phases_threePhaseWithMissingL2_defaultsToZero() {
        val v = PhaseValues( 230.0,  230.0,  0.0, 229.0)
        assertEquals("L1 230,0 · L2 0,0 · L3 229,0 В", JournalRecorder.phases(v, 1, "В"))
    }

    @Test
    fun phases_threePhaseWithMissingL2_Null() {
        val v = PhaseValues( 230.0,  230.0,  null, 229.0)
        assertEquals("230,0 В", JournalRecorder.phases(v, 1, "В"))
    }

    @Test
    fun snapshot_allNulls_returnsEmptySnapshot() {
        val s = JournalRecorder.snapshotOf(null, null, emptyList(), null)
        assertNull(s.meterType)
        assertNull(s.serialNumber)
        assertNull(s.voltage)
        assertNull(s.current)
        assertTrue(s.energy.isEmpty())
    }

    @Test
    fun snapshot_fallsBackToBleSerialWhenReportedIsNull() {
        val info = MeterBufferMapper.blankMeterInfo("11111111")
        val s = JournalRecorder.snapshotOf(info, null, emptyList(), reportedSerial = null)
        assertEquals("11111111", s.serialNumber)
    }

    @Test
    fun energyLines_handlesNullUnitLabel() {
        val r = Reading("1.8.1", 1, 1.0, Instant.parse("2026-11-24T08:23:00Z"), null, false)
        val lines = JournalRecorder.energyLines(listOf(r)).map { it.label to it.value }
        assertEquals(listOf("A+ · Т1" to "1,000"), lines)  // без единицы
    }

    @Test
    fun energyLines_emptyList_returnsEmpty() {
        assertTrue(JournalRecorder.energyLines(emptyList()).isEmpty())
    }

    @Test
    fun snapshot_stripsAllModelPrefixVariants() {
        val cases = mapOf(
            "РиМ 489.01" to "489.01",
            "RIM 489.01" to "489.01",
            "Rim 489.01" to "489.01",
            "рим 489.01" to "489.01",
            "РиМ489.01" to "489.01",
            "  489.01  " to "489.01",  // без префикса
            "SomeOtherModel" to "SomeOtherModel",  // не должен трогать
        )
        cases.forEach { (input, expected) ->
            val info = MeterBufferMapper.blankMeterInfo("x").copy(model = input)
            val s = JournalRecorder.snapshotOf(info, null, emptyList(), null)
            assertEquals("input=$input", expected, s.meterType)
        }
    }

}




