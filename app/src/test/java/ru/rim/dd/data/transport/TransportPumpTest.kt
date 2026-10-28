package ru.rim.dd.data.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.rim.dd.testutil.FakeMeterTransport
import ru.rim.dd.testutil.FakeNativeDlmsApi
import ru.rim.dd.testutil.TestDispatchers
import ru.rim.dd.testutil.VirtualTimeDispatcher

/** «Насос» байтов между BLE и нативным клиентом: ничего не знает о протоколе, только пересылает. */
class TransportPumpTest {

    private val dispatcher = VirtualTimeDispatcher()
    private val transport = FakeMeterTransport()
    private val native = FakeNativeDlmsApi()
    private val pump = TransportPump(transport, native, TestDispatchers(dispatcher))

    @Test
    fun incomingChunks_goToJournalCallbackThenNativeClient_inOrder() {
        val journal = mutableListOf<List<Byte>>()
        pump.start(onIncoming = { journal += it.toList() })
        dispatcher.runCurrent()

        transport.incoming.tryEmit(byteArrayOf(1, 2))
        transport.incoming.tryEmit(byteArrayOf(3))
        dispatcher.runCurrent()

        assertEquals(listOf(listOf<Byte>(1, 2), listOf<Byte>(3)), journal)
        assertEquals(listOf<Byte>(1, 2, 3), native.incoming.flatMap { it.toList() })
    }

    @Test
    fun outgoingBytes_arePolledAndWrittenToBle_emptyOnesAreSkipped() {
        pump.start()
        dispatcher.advanceBy(100)
        assertTrue("пустые пакеты нельзя слать в BLE", transport.written.isEmpty())

        native.outgoing.addLast(byteArrayOf(0x7E, 0x01, 0x7E))
        dispatcher.advanceBy(40)
        assertEquals(1, transport.written.size)
        assertArrayEquals(byteArrayOf(0x7E, 0x01, 0x7E), transport.written[0])
    }

    @Test
    fun stop_halts_bothDirections() {
        pump.start()
        dispatcher.runCurrent()
        assertTrue(pump.isActive)

        pump.stop()
        assertFalse(pump.isActive)
        transport.incoming.tryEmit(byteArrayOf(9))
        native.outgoing.addLast(byteArrayOf(8))
        dispatcher.advanceBy(1_000)

        assertTrue(native.incoming.isEmpty())
        assertTrue(transport.written.isEmpty())
    }

    @Test
    fun startingTwice_replacesPreviousPump_withoutDuplicates() {
        pump.start()
        dispatcher.runCurrent()
        pump.start()
        dispatcher.runCurrent()

        transport.incoming.tryEmit(byteArrayOf(5))
        dispatcher.runCurrent()
        assertEquals("пакет должен дойти ровно один раз", listOf<Byte>(5), native.incoming.flatMap { it.toList() })
    }
}
