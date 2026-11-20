package ru.rim.dd.data.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.rim.dd.core.dlms.parseGetResponseValue
import ru.rim.dd.core.dlms.readingsOf
import ru.rim.dd.core.model.RelaySource
import ru.rim.dd.core.model.TamperState
import ru.rim.dd.testutil.DlmsFixtures
import ru.rim.dd.testutil.RealMeterBuffer
import java.time.Instant

/** Состояние, которое видят экраны: настоящий буфер → реле, пломбы, сеть, сведения, энергия. */
class MeterStateTest {

    private val now = Instant.parse("2026-10-28T12:00:00Z")
    private val state = MeterState()

    @Test
    fun initialState_isEmptyAndUnknown() {
        assertFalse(state.relay.value.isOn)
        assertEquals(RelaySource.UNKNOWN, state.relay.value.source)
        assertTrue(state.tamper.value.unknown)
        assertNull(state.network.value)
        assertNull(state.info.value)
        assertTrue(state.readings.value.isEmpty())
    }

    @Test
    fun realBuffer_fillsAllStates() {
        val reported = state.applyBuffer(RealMeterBuffer.readings(), "04314652", null, now)

        assertNull("настоящий буфер РиМ серийный номер не содержит", reported)
        assertEquals(RelaySource.METER_READ, state.relay.value.source)
        assertEquals(TamperState.decode(826871552L), state.tamper.value)
        assertNotNull(state.network.value)
        assertEquals("04314652", state.info.value?.serialNumber)
        assertEquals(28.34, state.info.value!!.temperatureC!!, 1e-9)
        assertEquals(9, state.readings.value.size)
        assertEquals(1.74, state.readings.value.first { it.obisId == "1.0.1.8.1.255" }.valueKwh, 1e-9)
    }

    @Test
    fun serialReportedByMeter_isReturned() {
        val readings = readingsOf(parseGetResponseValue(DlmsFixtures.bufferWithSerial("04314652")))

        assertEquals("04314652", state.applyBuffer(readings, "04314652", null, now))
    }

    @Test
    fun emptyBuffer_doesNotWipeExistingData() {
        state.applyBuffer(RealMeterBuffer.readings(), "04314652", null, now)
        val relay = state.relay.value
        val tamper = state.tamper.value
        val network = state.network.value
        val info = state.info.value
        val readings = state.readings.value

        state.applyBuffer(emptyList(), "04314652", null, now.plusSeconds(5))

        assertEquals(relay, state.relay.value)
        assertEquals(tamper, state.tamper.value)
        assertEquals(network, state.network.value)
        assertEquals(info, state.info.value)
        assertEquals(readings, state.readings.value)
    }

    @Test
    fun nextBuffer_updatesTimestamps() {
        state.applyBuffer(RealMeterBuffer.readings(), "04314652", null, now)
        state.applyBuffer(RealMeterBuffer.readings(), "04314652", null, now.plusSeconds(5))

        assertEquals(now.plusSeconds(5), state.info.value?.lastSeenAt)
        assertTrue(state.readings.value.all { it.timestamp == now.plusSeconds(5) })
    }

    @Test
    fun reset_returnsInitialValues() {
        state.applyBuffer(RealMeterBuffer.readings(), "04314652", null, now)

        state.reset()

        assertFalse(state.relay.value.isOn)
        assertEquals(RelaySource.UNKNOWN, state.relay.value.source)
        assertTrue(state.tamper.value.unknown)
        assertNull(state.network.value)
        assertNull(state.info.value)
        assertTrue(state.readings.value.isEmpty())
    }
    //Да вроде хорошо все, просто сохранение данных. Ну можно проверить на мусор?
    // по данным из отчета принимается только 04314652, а если другое послать?
    // или любой?
    // как поняла любой, тк тут просто хранение, а проверка потом
    @Test
    fun applyBuffer_storesGivenSerialAsIs() {
        val reported = state.applyBuffer(RealMeterBuffer.readings(), "1043146523", null, now)

        assertNull("настоящий буфер РиМ серийный номер не содержит", reported)
        assertEquals(RelaySource.METER_READ, state.relay.value.source)
        assertEquals(TamperState.decode(826871552L), state.tamper.value)
        assertNotNull(state.network.value)
        assertEquals("1043146523", state.info.value?.serialNumber)
        assertEquals(28.34, state.info.value!!.temperatureC!!, 1e-9)
        assertEquals(9, state.readings.value.size)
        assertEquals(1.74, state.readings.value.first { it.obisId == "1.0.1.8.1.255" }.valueKwh, 1e-9)
    }


    @Test
    fun partialBuffer_keepsFieldsNotPresent() {
        // Задумка (комментарий к MeterState): разбор сохраняет прежнее значение для
        // не пришедших полей. Сначала полный буфер, потом- только энергия.
        state.applyBuffer(RealMeterBuffer.readings(), "04314652", null, now)
        val temperature = state.info.value!!.temperatureC
        val network = state.network.value
        val tamper = state.tamper.value

        val onlyEnergy = RealMeterBuffer.readings().filter { it.obisCode == "1.0.1.8.1.255" }
        state.applyBuffer(onlyEnergy, "04314652", null, now.plusSeconds(5))

        assertEquals("температура не пришла — прежняя", temperature, state.info.value!!.temperatureC)
        assertEquals("сеть не пришла — прежняя", network, state.network.value)
        assertEquals("пломбы не пришли — прежние", tamper, state.tamper.value)
        assertEquals("энергия обновилась", 1.74,
            state.readings.value.first { it.obisId == "1.0.1.8.1.255" }.valueKwh, 1e-9)
    }


    // reset(): не «протекают» поля прежнего прибора

    @Test
    fun resetThenApplyNewBuffer_doesNotLeakOldFields() {
        // Ради чего reset() существует: после смены прибора поля, которых новый прибор
        // не присылает, не должны показывать значения прежнего ПУ.
        state.applyBuffer(RealMeterBuffer.readings(), "04314652", null, now)
        assertNotNull("до reset температура была", state.info.value?.temperatureC)

        state.reset()
        val onlyEnergy = RealMeterBuffer.readings().filter { it.obisCode == "1.0.1.8.1.255" }
        state.applyBuffer(onlyEnergy, "04314652", null, now.plusSeconds(5))

        assertNull("температура прежнего прибора не должна протечь", state.info.value?.temperatureC)
        assertNull("сеть прежнего прибора не должна протечь", state.network.value)
    }

}
