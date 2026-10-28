package ru.rim.dd.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.rim.dd.core.model.RelaySource
import ru.rim.dd.testutil.DlmsFixtures
import ru.rim.dd.testutil.SessionHarness

/** Управление размыкателем: команда уходит только при живом сеансе, состояние меняется только после Action-Result = 0. */
class RelayRepositoryImplTest {

    private val h = SessionHarness()
    private val repository = RelayRepositoryImpl(h.manager, h.dlms, h.state, h.dispatchers)

    private fun actions() = h.native.calls.filter { it.startsWith("action") }

    @Test
    fun noSession_commandIsNotSent_andStateIsUntouched() {
        val before = h.state.relay.value

        h.dispatcher.run { repository.turnRelayOn() }
        h.dispatcher.run { repository.turnRelayOff() }

        assertTrue(actions().isEmpty())
        assertEquals(before, h.state.relay.value)
    }

    @Test
    fun turnOn_sendsMethod2_andMarksRelayOnByRemoteControl() {
        h.connectRim()
        h.native.onAction = { DlmsFixtures.actionResponse(result = 0) }

        h.dispatcher.run { repository.turnRelayOn() }

        assertEquals(listOf("action 70 0.0.96.3.10.255 method=2"), actions())
        assertTrue(h.state.relay.value.isOn)
        assertEquals(RelaySource.REMOTE_CONTROL, h.state.relay.value.source)
    }

    @Test
    fun turnOff_sendsMethod1_andMarksRelayOff() {
        h.connectRim()
        h.native.onAction = { DlmsFixtures.actionResponse(result = 0) }
        h.dispatcher.run { repository.turnRelayOn() }

        h.dispatcher.run { repository.turnRelayOff() }

        assertEquals(
            listOf("action 70 0.0.96.3.10.255 method=2", "action 70 0.0.96.3.10.255 method=1"),
            actions(),
        )
        assertFalse(h.state.relay.value.isOn)
    }

    @Test
    fun deviceRefuses_stateStaysAsItWas() {
        h.connectRim()
        val before = h.state.relay.value
        h.native.onAction = { DlmsFixtures.actionResponse(result = 0x0D) }

        h.dispatcher.run { repository.turnRelayOn() }

        assertEquals(1, actions().size)
        assertEquals(before, h.state.relay.value)
    }

    @Test
    fun noAnswer_stateStaysAsItWas() {
        h.connectRim()
        val before = h.state.relay.value
        h.native.onAction = { null }

        h.dispatcher.run { repository.turnRelayOff() }

        assertEquals(before, h.state.relay.value)
    }

    @Test
    fun relayCommand_waitsForRunningRead_insteadOfInterleavingWithIt() {
        h.connectRim()
        h.native.onAction = { DlmsFixtures.actionResponse(result = 0) }
        val order = mutableListOf<String>()

        // Чтение буфера занимает протокольный лок на 100 мс; команда реле обязана подождать.
        h.dispatcher.launch { h.manager.exclusive { order += "read-start"; kotlinx.coroutines.delay(100); order += "read-end" } }
        h.dispatcher.runCurrent()
        val relayJob = h.dispatcher.launch { repository.turnRelayOn(); order += "relay-done" }
        h.advance(50)
        assertFalse("команда не должна вклиниться в чтение", relayJob.isCompleted)
        assertTrue(actions().isEmpty())

        h.advance(100)
        assertTrue(relayJob.isCompleted)
        assertEquals(listOf("read-start", "read-end", "relay-done"), order)
        assertEquals(1, actions().size)
    }
}
