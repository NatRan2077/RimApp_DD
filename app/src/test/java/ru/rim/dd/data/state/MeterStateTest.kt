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
}
