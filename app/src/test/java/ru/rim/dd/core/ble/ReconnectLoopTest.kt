package ru.rim.dd.core.ble

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.rim.dd.testutil.VirtualTimeDispatcher

/**
 * Цикл переподключения после обрыва связи (UC-04): паузы растут 1 → 2 → 4 … до 20 с, цикл
 * останавливается по «Отключиться», при «холодном» переподключении ждёт лишние 5 с.
 * Время виртуальное: сутки ожидания проходят за миллисекунды.
 */
class ReconnectLoopTest {

    private val dispatcher = VirtualTimeDispatcher()

    @Test
    fun succeedsOnFirstAttempt_withoutPause() {
        val attempts = mutableListOf<Long>()
        val job = dispatcher.launch {
            reconnectWithBackoff("RIM1", extraDelayMs = 0, isStopped = { false }) {
                attempts += dispatcher.currentTime
                true
            }
        }
        dispatcher.runCurrent()
        assertTrue(job.isCompleted)
        assertEquals(listOf(0L), attempts)
    }

    @Test
    fun retriesWithDoublingPause_cappedAtTwentySeconds() {
        val attemptTimes = mutableListOf<Long>()
        val job = dispatcher.launch {
            reconnectWithBackoff("RIM1", extraDelayMs = 0, isStopped = { false }) { attempt ->
                attemptTimes += dispatcher.currentTime
                if (attempt < 7) throw IllegalStateException("нет связи") else true
            }
        }
        dispatcher.advanceBy(120_000)
        assertTrue(job.isCompleted)
        // паузы: 1, 2, 4, 8, 16 и дальше потолок 20 с
        assertEquals(listOf(0L, 1_000L, 3_000L, 7_000L, 15_000L, 31_000L, 51_000L), attemptTimes)
        assertTrue(dispatcher.uncaught.isEmpty())
    }

    @Test
    fun nextDelay_doublesAndStopsAtMaximum() {
        assertEquals(2_000L, ReconnectTiming.nextDelay(1_000L))
        assertEquals(16_000L, ReconnectTiming.nextDelay(8_000L))
        assertEquals(20_000L, ReconnectTiming.nextDelay(16_000L))
        assertEquals(20_000L, ReconnectTiming.nextDelay(20_000L))
    }

    @Test
    fun stopsWhenUserDisconnectsBetweenAttempts() {
        var stopped = false
        var attempts = 0
        val job = dispatcher.launch {
            reconnectWithBackoff("RIM1", extraDelayMs = 0, isStopped = { stopped }) {
                attempts++
                throw IllegalStateException("нет связи")
            }
        }
        dispatcher.advanceBy(1_500)           // попытки #1 (t=0) и #2 (t=1с) уже были
        assertEquals(2, attempts)
        stopped = true                        // пользователь нажал «Отключиться»
        dispatcher.advanceBy(60_000)
        assertTrue(job.isCompleted)
        assertEquals(2, attempts)             // новых попыток нет
    }

    @Test
    fun stopsImmediatelyWhenFailureHappensAfterUserStop() {
        var stopped = false
        var attempts = 0
        val job = dispatcher.launch {
            reconnectWithBackoff("RIM1", extraDelayMs = 0, isStopped = { stopped }) {
                attempts++
                stopped = true
                throw IllegalStateException("отключили во время попытки")
            }
        }
        dispatcher.runCurrent()
        assertTrue(job.isCompleted)
        assertEquals(1, attempts)
    }

    @Test
    fun doesNotStartWhenAlreadyStopped() {
        var attempts = 0
        dispatcher.launch {
            reconnectWithBackoff("RIM1", extraDelayMs = 0, isStopped = { true }) { attempts++; true }
        }
        dispatcher.advanceBy(10_000)
        assertEquals(0, attempts)
    }

    @Test
    fun noManager_attemptReturnsFalse_loopEndsWithoutRepeat() {
        var attempts = 0
        val job = dispatcher.launch {
            reconnectWithBackoff("RIM1", extraDelayMs = 0, isStopped = { false }) { attempts++; false }
        }
        dispatcher.advanceBy(60_000)
        assertTrue(job.isCompleted)
        assertEquals(1, attempts)
    }

    @Test
    fun coldReconnect_waitsExtraPauseBeforeFirstAttempt() {
        val attemptTimes = mutableListOf<Long>()
        val job = dispatcher.launch {
            reconnectWithBackoff("RIM1", ReconnectTiming.COLD_COOLDOWN_MS, { false }) {
                attemptTimes += dispatcher.currentTime
                true
            }
        }
        dispatcher.advanceBy(4_999)
        assertTrue(attemptTimes.isEmpty())
        dispatcher.advanceBy(1)
        assertEquals(listOf(5_000L), attemptTimes)
        assertTrue(job.isCompleted)
    }

    @Test
    fun cancellationDuringAttempt_endsLoop() {
        var attempts = 0
        val job = dispatcher.launch {
            reconnectWithBackoff("RIM1", extraDelayMs = 0, isStopped = { false }) {
                attempts++
                delay(ReconnectTiming.ATTEMPT_TIMEOUT_MS)   // попытка «висит»
                throw CancellationException("отмена")
            }
        }
        dispatcher.advanceBy(100)
        job.cancel()
        dispatcher.advanceBy(120_000)
        assertTrue(job.isCancelled)
        assertEquals(1, attempts)
        assertFalse(job.isActive)
    }
}
