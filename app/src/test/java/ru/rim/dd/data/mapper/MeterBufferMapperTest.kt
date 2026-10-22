package ru.rim.dd.data.mapper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.rim.dd.core.dlms.kiloUnitLabelOf
import ru.rim.dd.core.model.MeterInfo
import ru.rim.dd.core.model.RelaySource
import ru.rim.dd.core.model.RelayState
import ru.rim.dd.core.model.TamperState
import ru.rim.dd.testutil.RealMeterBuffer
import java.time.Instant

/**
 * Проверка разбора буфера индикации на реальном ответе прибора (3-кадровый HDLC-ответ на
 * GET 0.0.21.0.2.255, снятый с трёхфазного РиМ). Кадры собираются так же, как это делает
 * приложение: из каждого берётся информационное поле, поля склеиваются и разбираются целиком.
 */
class MeterBufferMapperTest {

    private val now = Instant.parse("2026-10-15T12:00:00Z")

    private fun realBuffer() = RealMeterBuffer.readings()

    private fun relay0() = RelayState(isOn = false, powerLimitKw = 0.0, source = RelaySource.UNKNOWN)

    @Test
    fun realBuffer_isFullyDecoded() {
        val all = realBuffer()
        assertEquals(22, all.size)
        assertTrue(all.any { it.obisCode == "1.0.1.8.0.255" })
        assertTrue(all.none { it.obisCode.startsWith("1.0.0.6.") })
    }

    @Test
    fun relayState_readsControlStateFromBuffer() {
        // control_state = 2 (ready_for_reconnection): реле выключено, но включение разрешено
        val relay = MeterBufferMapper.relayState(realBuffer(), relay0())
        assertNotNull(relay)
        assertFalse(relay!!.isOn)
        assertTrue(relay.remoteTurnOnAllowed)
        assertEquals(RelaySource.METER_READ, relay.source)
    }

    @Test
    fun relayState_nullWhenBufferHasNoField() {
        assertNull(MeterBufferMapper.relayState(emptyList(), relay0()))
    }

    @Test
    fun tamperState_decodesStatusField() {
        assertEquals(TamperState.decode(826871552L), MeterBufferMapper.tamperState(realBuffer()))
        assertNull(MeterBufferMapper.tamperState(emptyList()))
    }

    @Test
    fun energyReadings_splitCurrentAndRdch() {
        val readings = MeterBufferMapper.energyReadings(realBuffer(), now)!!
        assertEquals(9, readings.size)
        assertEquals(5, readings.count { !it.isRdch })
        assertEquals(4, readings.count { it.isRdch })

        val t1 = readings.first { it.obisId == "1.0.1.8.1.255" }
        assertEquals(1, t1.tariff)
        assertEquals(1.74, t1.valueKwh, 1e-9)
        assertFalse(t1.isRdch)
        assertEquals(kiloUnitLabelOf(30), t1.unitLabel)

        val total = readings.first { it.obisId == "1.0.1.8.0.255" }
        assertNull(total.tariff)
        assertEquals(1.74, total.valueKwh, 1e-9)

        val reactive = readings.first { it.obisId == "1.0.4.8.0.255" }
        assertEquals(0.41, reactive.valueKwh, 1e-9)
        assertEquals(now, reactive.timestamp)

        assertTrue(readings.first { it.obisId == "1.0.1.8.0.101" }.isRdch)
    }

    @Test
    fun energyReadings_nullWithoutEnergyCodes() {
        assertNull(MeterBufferMapper.energyReadings(emptyList(), now))
    }

    @Test
    fun networkParams_realBufferHasNoVoltageButHasCurrentAndPower() {
        val net = MeterBufferMapper.networkParams(realBuffer())
        assertNotNull(net)
        // 1.0.12.7 / 32.7 / 52.7 / 72.7 в этом буфере нет — напряжение остаётся нулевым
        assertEquals(0.0, net!!.voltage.total, 0.0)
        assertNull(net.voltage.l1)
        // ток пришёл по фазам L2 (51.7) и L1 (31.7)
        assertEquals(0.0, net.current.l1!!, 0.0)
        assertEquals(0.0, net.current.l2!!, 0.0)
        assertNull(net.current.l3)
        // мощности — все четыре кода (сумма и три фазы)
        assertEquals(0.0, net.power.l3!!, 0.0)
        assertNull(net.frequencyHz)
        assertNull(net.lineVoltages)
    }

    @Test
    fun networkParams_nullWhenNothingMeasured() {
        assertNull(MeterBufferMapper.networkParams(emptyList()))
    }

    @Test
    fun meterInfo_realBuffer() {
        val result = MeterBufferMapper.meterInfo(
            all = realBuffer(),
            prev = null,
            activeSerialNumber = "04314652",
            knownReportedSerial = null,
            now = now,
        )
        assertNotNull(result)
        val info = result!!.info
        assertEquals(28.34, info.temperatureC!!, 1e-9)
        assertEquals(3.637, info.backupVoltageV!!, 1e-9)
        assertEquals(1, info.currentTariff)
        assertEquals(true, info.clockValid)
        assertTrue(info.phaseStatus!!.threePhase)
        assertEquals(now, info.lastSeenAt)
        // серийный номер в буфере не пришёл — берётся номер из BLE-имени
        assertNull(result.reportedSerial)
        assertEquals("04314652", info.serialNumber)
        assertEquals("—", info.model)
    }

    @Test
    fun meterInfo_keepsPreviousValuesForMissingFields() {
        val prev = MeterBufferMapper.blankMeterInfo("04314652")
            .copy(model = "489", firmwareVersion = "1.02", operatingTime = 12.0, operatingTimeUnit = "ч")
        val info = MeterBufferMapper.meterInfo(realBuffer(), prev, "04314652", null, now)!!.info
        assertEquals("489", info.model)
        assertEquals("1.02", info.firmwareVersion)
        assertEquals(12.0, info.operatingTime!!, 0.0)
        assertEquals("ч", info.operatingTimeUnit)
    }

    @Test
    fun meterInfo_nullWhenBufferHasNoInfoFields() {
        assertNull(MeterBufferMapper.meterInfo(emptyList(), null, null, null, now))
    }

    @Test
    fun blankMeterInfo_usesActiveSerialOrDash() {
        val blank: MeterInfo = MeterBufferMapper.blankMeterInfo(null)
        assertEquals("—", blank.serialNumber)
        assertEquals("12345678", MeterBufferMapper.blankMeterInfo("12345678").serialNumber)
    }

    @Test
    fun hdlcSpeedLabel_knownAndUnknownCodes() {
        assertEquals("9600 бод", MeterBufferMapper.hdlcSpeedLabel(5))
        assertEquals("115200 бод", MeterBufferMapper.hdlcSpeedLabel(9))
        assertEquals("скорость код 42", MeterBufferMapper.hdlcSpeedLabel(42))
    }
}