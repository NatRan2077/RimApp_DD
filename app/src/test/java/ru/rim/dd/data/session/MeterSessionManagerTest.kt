package ru.rim.dd.data.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import ru.rim.dd.core.ble.BleDevice
import ru.rim.dd.core.model.ConnectionState
import ru.rim.dd.testutil.DlmsFixtures
import ru.rim.dd.testutil.SessionHarness

/**
 * Жизненный цикл протокольного сеанса целиком, без телефона и прибора: подключение РиМ и AKROS,
 * сверка серийного номера, автообновление, пересборка после обрыва, принудительное и «холодное»
 * переподключение, отключение. Время виртуальное: паузы по 5 секунд проходят мгновенно.
 */
class MeterSessionManagerTest {

    private val h = SessionHarness()
    private val address = "AA:BB:CC:DD:EE:01"

    private fun forceEvents() = h.transport.events.filter { it.startsWith("forceReconnect") }

    /** Прокручивает время секундами, пока число принудительных переподключений не вырастет; false — не дождались. */
    private fun advanceUntilForce(previous: Int, limitSeconds: Int = 60): Boolean {
        repeat(limitSeconds) {
            h.advance(1_000)
            if (forceEvents().size > previous) return true
        }
        return false
    }

    // ---------- подключение ----------

    @Test
    fun connectByAddress_rim_readsBufferWithoutAssociation() {
        val meter = h.dispatcher.run { h.manager.connectByAddress(address, "RIM1894604314652") }

        assertEquals("04314652", meter.serialNumber)       // из BLE-имени: прибор номер не сообщает
        assertEquals(address, meter.bleAddress)
        assertEquals(listOf("create", "flat-get 7 0.0.21.0.2.255 2"), h.native.calls)
        assertEquals(listOf("begin"), h.journal.events)
        assertTrue(h.manager.isActive)
        assertTrue("показания разложены по состоянию", h.state.readings.value.isNotEmpty())
        assertEquals("04314652", h.state.info.value?.serialNumber)
        assertFalse("опрос включается отдельно, после запоминания прибора", h.dlms.akrosAssociation)
    }

    @Test
    fun firstConnection_isNotMistakenForAutoReconnect() {
        h.connectRim()
        h.advance(4_000)                                  // до первого автообновления
        assertEquals("клиент создаётся один раз", 1, h.native.createCount)
        assertEquals(0, h.native.destroyCount)
    }

    @Test
    fun connectFailure_propagates_andLeavesNothingBehind() {
        h.transport.connectError = IllegalStateException("не найден")

        try {
            h.dispatcher.run { h.manager.connectByAddress(address, "RIM1894604314652") }
            fail("ожидалось исключение")
        } catch (e: IllegalStateException) {
            assertEquals("не найден", e.message)
        }

        assertFalse(h.manager.isActive)
        assertEquals(0, h.native.createCount)
        assertTrue("карточка журнала ещё не заводилась", h.journal.events.isEmpty())
    }

    @Test
    fun firstRequestNotSent_sessionAbortedAndJournalDiscarded() {
        h.native.flatGetSends = false
        h.native.error = "клиент не создан"

        try {
            h.dispatcher.run { h.manager.connectByAddress(address, "RIM1894604314652") }
            fail("ожидалось исключение")
        } catch (e: IllegalStateException) {
            assertEquals("клиент не создан", e.message)
        }

        assertFalse(h.manager.isActive)
        assertTrue(h.transport.events.contains("disconnect"))
        assertEquals(listOf("begin", "discard"), h.journal.events)
    }

    @Test
    fun firstAnswerEmpty_connectionStaysUp_andPollingRetries() {
        // Пустой ответ — не повод рвать связь: счётчик может отвечать с задержкой, автообновление повторит.
        h.native.onFlatGet = { _, _, _ -> null }

        h.dispatcher.run { h.manager.connectByAddress(address, "RIM1894604314652") }

        assertTrue(h.manager.isActive)
        assertEquals(listOf("begin"), h.journal.events)
    }

    @Test
    fun connectBySerialNumber_meterConfirmsSameSerial_connects() {
        h.native.onFlatGet = { _, _, _ -> DlmsFixtures.bufferWithSerial("04314652") }

        val meter = h.dispatcher.run { h.manager.connectBySerialNumber("4314652") }

        assertEquals("04314652", meter.serialNumber)
        assertEquals(h.transport.foundDevice.address, meter.bleAddress)
        assertTrue(h.manager.isActive)
        assertEquals("connectBySerialNumber 4314652", h.transport.events.first())
    }

    @Test
    fun connectBySerialNumber_meterReportsOtherSerial_connectionIsAborted() {
        // В эфире нашёлся прибор с нужным именем, но по протоколу он называет другой номер.
        h.native.onFlatGet = { _, _, _ -> DlmsFixtures.bufferWithSerial("09999999") }

        try {
            h.dispatcher.run { h.manager.connectBySerialNumber("04314652") }
            fail("к чужому прибору подключаться нельзя")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("09999999"))
            assertTrue(e.message!!.contains("04314652"))
        }

        assertFalse(h.manager.isActive)
        assertTrue(h.transport.events.contains("disconnect"))
        assertTrue(h.journal.events.contains("discard"))
        assertNull("номер чужого прибора не должен остаться активным", h.manager.activeSerialNumber)
    }

    @Test
    fun connectBySerialNumber_meterDoesNotReportSerial_fallsBackToNameFilter() {
        // Настоящий буфер РиМ номера не содержит: остаётся только совпадение номера в имени.
        val meter = h.dispatcher.run { h.manager.connectBySerialNumber("04314652") }

        assertEquals("04314652", meter.serialNumber)
        assertTrue(h.manager.isActive)
    }

    @Test
    fun connectBySerialNumber_deviceNotFound_propagates() {
        h.transport.connectError = IllegalStateException("Прибор не найден в эфире")

        try {
            h.dispatcher.run { h.manager.connectBySerialNumber("04314652") }
            fail("ожидалось исключение")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("не найден"))
        }
        assertEquals(0, h.native.createCount)
    }

    // ---------- AKROS ----------

    @Test
    fun akros_answersWithoutAssociation_usesRimPath() {
        h.transport.foundDevice = BleDevice(address, "AKROS-07200090", -55)

        h.dispatcher.run { h.manager.connectByAddress(address, "AKROS-07200090") }

        assertFalse(h.dlms.akrosAssociation)
        assertEquals(0, h.native.count("snrm"))
        assertTrue(h.manager.isActive)
    }

    @Test
    fun akros_silentWithoutAssociation_fallsBackToSnrmAarqAndCompactBuffer() {
        h.native.onFlatGet = { _, _, _ -> null }              // AKROS на путь пульта не отвечает
        h.native.onGet = DlmsFixtures.akrosGetResponder()

        val meter = h.dispatcher.run { h.manager.connectByAddress(address, "AKROS-07200090") }

        assertEquals("07200090", meter.serialNumber)
        assertTrue("выбранный путь запоминается для переподключений", h.dlms.akrosAssociation)
        assertEquals(
            listOf(
                "create",
                "flat-get 7 0.0.21.0.2.255 2",                // попытка без ассоциации
                "destroy", "create",                          // клиент пересоздан: нумерация кадров с нуля
                "snrm 32/1/16", "ua", "aarq 1 Reader",
                "get 7 0.0.21.0.2.255 3",                     // capture_objects
                "get 3 1.0.1.8.0.255 3", "get 3 1.0.32.7.0.255 3",
                "get 7 0.0.21.0.2.255 2",                     // сам буфер, стандартной адресацией
            ),
            h.native.calls,
        )
        assertEquals(2, h.state.readings.value.size.coerceAtLeast(2))
        assertTrue(h.manager.isActive)
    }

    @Test
    fun akros_fallbackAssociationRefused_connectionFails() {
        h.native.onFlatGet = { _, _, _ -> null }
        h.native.uaResult = 2                                  // прибор ответил DM

        try {
            h.dispatcher.run { h.manager.connectByAddress(address, "AKROS-07200090") }
            fail("ожидалось исключение")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("DM"))
        }

        assertFalse(h.manager.isActive)
        assertTrue(h.transport.events.contains("disconnect"))
        assertTrue(h.journal.events.contains("discard"))
    }

    // ---------- автообновление ----------

    @Test
    fun autoRefresh_readsBufferEveryFiveSeconds_andAsksRssi() {
        h.connectRim()
        val readsAfterConnect = h.mainBufferRequests()
        val rssiAfterConnect = h.transport.rssiRequests
        assertEquals(1, readsAfterConnect)

        h.advance(4_999)
        assertEquals(readsAfterConnect, h.mainBufferRequests())
        h.advance(1)
        assertEquals(readsAfterConnect + 1, h.mainBufferRequests())
        h.advance(10_000)
        assertEquals(readsAfterConnect + 3, h.mainBufferRequests())
        assertTrue(h.transport.rssiRequests >= rssiAfterConnect + 3)
        assertTrue("журнал получает снимки", h.journal.updates >= 1)
    }

    @Test
    fun refresh_readsOnlyWithActiveSession() {
        h.dispatcher.run { h.manager.refresh() }
        assertEquals(0, h.mainBufferRequests())

        h.connectRim()
        h.dispatcher.run { h.manager.refresh() }
        assertEquals(2, h.mainBufferRequests())
    }

    @Test
    fun exclusive_serializesProtocolWork() {
        val order = mutableListOf<String>()
        h.dispatcher.launch {
            h.manager.exclusive { order += "A+"; kotlinx.coroutines.delay(100); order += "A-" }
        }
        h.dispatcher.launch {
            h.manager.exclusive { order += "B+"; kotlinx.coroutines.delay(100); order += "B-" }
        }
        h.advance(500)
        assertEquals(listOf("A+", "A-", "B+", "B-"), order)
    }

    // ---------- сбои протокола при живом GATT ----------

    @Test
    fun threeFailedCycles_forceReconnect_andPollingStops() {
        h.connectRim()
        h.native.onFlatGet = { _, _, _ -> null }              // прибор считает сессию закрытой

        h.advance(14_999)
        assertTrue("за два цикла реконнекта ещё нет", forceEvents().isEmpty())
        h.advance(1)
        assertEquals(listOf("forceReconnect cold=false"), forceEvents())

        val reads = h.mainBufferRequests()
        h.advance(30_000)
        assertEquals("после принудительного разрыва опрос не продолжается", reads, h.mainBufferRequests())
    }

    @Test
    fun singleFailure_doesNotForceReconnect() {
        h.connectRim()
        var calls = 0
        h.native.onFlatGet = { _, _, _ -> if (calls++ == 0) null else RealMeterBuffer_raw() }

        h.advance(60_000)

        assertTrue(forceEvents().isEmpty())
    }

    @Test
    fun repeatedForcedReconnectsWithoutData_becomeCold_thenCounterRestarts() {
        h.connectRim()
        h.native.onFlatGet = { _, _, _ -> null }

        val results = mutableListOf<String>()
        repeat(4) {
            assertTrue("цикл ${it + 1}: ждали принудительное переподключение", advanceUntilForce(forceEvents().size))
            results += forceEvents().last()
            // GATT «переподключился»: сеанс пересобирается и снова запускает автообновление
            h.transport.simulateUnexpectedDisconnect()
            h.advance(100)
            h.transport.simulateReconnected()
            h.advance(2_000)
        }

        assertEquals(
            listOf("forceReconnect cold=false", "forceReconnect cold=false", "forceReconnect cold=true", "forceReconnect cold=false"),
            results,
        )
    }

    @Test
    fun successfulRead_resetsFailureCounters() {
        h.connectRim()
        var failing = true
        h.native.onFlatGet = { _, _, _ -> if (failing) null else RealMeterBuffer_raw() }

        h.advance(10_000)                                      // два неудачных цикла
        failing = false
        h.advance(5_000)                                       // успех обнуляет счётчик
        failing = true
        h.advance(10_000)                                      // снова два неудачных — порога нет

        assertTrue(forceEvents().isEmpty())
    }

    // ---------- обрыв связи и автопереподключение ----------

    @Test
    fun unexpectedDisconnect_stopsExchange_untilConnectionReturns() {
        h.connectRim()
        h.advance(5_000)
        val reads = h.mainBufferRequests()

        h.transport.simulateUnexpectedDisconnect()
        h.advance(30_000)

        assertFalse(h.manager.isActive)
        assertEquals("во время обрыва GET не отправляются", reads, h.mainBufferRequests())
    }

    @Test
    fun reconnected_sessionIsRebuiltAfterSettlePause_andPollingResumes() {
        h.connectRim()
        h.transport.simulateUnexpectedDisconnect()
        h.advance(500)
        val created = h.native.createCount
        val reads = h.mainBufferRequests()

        h.transport.simulateReconnected()
        h.advance(1_499)
        assertEquals("счётчику даём 1,5 с прийти в себя", created, h.native.createCount)

        h.advance(1)
        assertEquals("клиент пересоздан", created + 1, h.native.createCount)
        assertEquals(1, h.native.destroyCount)
        assertTrue(h.manager.isActive)
        assertEquals("первый GET нового сеанса", reads + 1, h.mainBufferRequests())

        h.advance(5_000)
        assertEquals("автообновление возобновилось", reads + 2, h.mainBufferRequests())
    }

    @Test
    fun reconnected_butFirstReadFails_pollingStillRestartsAndRetries() {
        h.connectRim()
        h.transport.simulateUnexpectedDisconnect()
        h.advance(100)
        h.native.onFlatGet = { _, _, _ -> null }
        h.transport.simulateReconnected()
        h.advance(1_600)
        val reads = h.mainBufferRequests()

        h.advance(5_000)

        assertEquals("автообновление запущено и повторяет попытки", reads + 1, h.mainBufferRequests())
    }

    @Test
    fun akrosAssociationPath_isReusedAfterReconnect() {
        h.native.onFlatGet = { _, _, _ -> null }
        h.native.onGet = DlmsFixtures.akrosGetResponder()
        h.dispatcher.run { h.manager.connectByAddress(address, "AKROS-07200090") }
        h.manager.startPolling()
        assertTrue(h.dlms.akrosAssociation)
        h.native.calls.clear()

        h.transport.simulateUnexpectedDisconnect()
        h.advance(100)
        h.transport.simulateReconnected("AKROS-07200090")
        h.advance(2_000)

        assertEquals("destroy", h.native.calls[0])
        assertEquals("create", h.native.calls[1])
        assertTrue("ассоциация AKROS поднимается сразу, без пробы пути пульта", h.native.calls.contains("aarq 1 Reader"))
        assertEquals(0, h.native.count("flat-get"))
    }

    // ---------- отключение ----------

    @Test
    fun disconnect_closesEverythingAndForgetsMeter() {
        h.connectRim()

        h.dispatcher.run { h.manager.disconnect() }

        assertFalse(h.manager.isActive)
        assertFalse("нативный клиент уничтожен", h.native.clientAlive)
        assertTrue(h.transport.events.contains("disconnect"))
        assertEquals("end", h.journal.events.last())
        assertNull(h.manager.activeSerialNumber)
        assertTrue("данные отключённого прибора не достаются следующему", h.state.readings.value.isEmpty())
        assertFalse(h.dlms.akrosAssociation)
    }

    @Test
    fun afterDisconnect_gattReturning_doesNotRebuildSession() {
        h.connectRim()
        h.dispatcher.run { h.manager.disconnect() }
        val created = h.native.createCount
        val reads = h.mainBufferRequests()

        h.transport.simulateReconnected()
        h.advance(20_000)

        assertEquals(created, h.native.createCount)
        assertEquals(reads, h.mainBufferRequests())
        assertFalse(h.manager.isActive)
    }

    @Test
    fun connectingNextMeter_startsFromCleanState() {
        h.connectRim()
        assertNotNull(h.state.info.value)

        h.native.flatGetSends = false
        try {
            h.dispatcher.run { h.manager.connectByAddress("AA:BB:CC:DD:EE:02", "RIM4894604664030") }
            fail("ожидалось исключение")
        } catch (e: IllegalStateException) {
            // новый прибор не ответил
        }

        assertTrue("показания прошлого прибора сброшены", h.state.readings.value.isEmpty())
        assertEquals(ConnectionState.Idle, h.transport.connectionState.value)
    }

    @Suppress("FunctionName")
    private fun RealMeterBuffer_raw(): ByteArray = ru.rim.dd.testutil.RealMeterBuffer.rawResponse()
}
