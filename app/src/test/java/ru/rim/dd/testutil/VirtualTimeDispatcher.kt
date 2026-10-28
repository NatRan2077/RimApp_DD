package ru.rim.dd.testutil

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Delay
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.PriorityQueue
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume

/**
 * Однопоточный диспетчер с ВИРТУАЛЬНЫМ временем: delay(5000) не ждёт пять секунд, а переводит
 * внутренние часы вперёд, когда тест вызывает [advanceBy]. Благодаря этому циклы с паузами
 * (автообновление раз в 5 с, переподключение 1 → 20 с) проверяются мгновенно и детерминированно.
 *
 * Заменяет kotlinx-coroutines-test (в проект эта библиотека не подключена): нужно только то,
 * что используется в тестах — выполнение задач «сейчас», перемотка времени и запуск сценария.
 * Всё исполняется в потоке теста, поэтому гонок нет.
 */
@OptIn(InternalCoroutinesApi::class)
class VirtualTimeDispatcher : CoroutineDispatcher(), Delay {

    private class Task(val time: Long, val order: Long, val block: Runnable) {
        var cancelled = false
    }

    private val queue = PriorityQueue<Task>(compareBy<Task>({ it.time }, { it.order }))
    private var counter = 0L

    /** Исключения, не обработанные в корутинах сценария: тесты проверяют, что список пуст. */
    val uncaught = mutableListOf<Throwable>()

    /** Прошедшее виртуальное время, мс. */
    var currentTime = 0L
        private set

    /** Область для запуска сценария теста на этом диспетчере. */
    val scope: CoroutineScope = CoroutineScope(
        SupervisorJob() + this + CoroutineExceptionHandler { _, e -> uncaught += e },
    )

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        queue.add(Task(currentTime, counter++, block))
    }

    override fun scheduleResumeAfterDelay(timeMillis: Long, continuation: CancellableContinuation<Unit>) {
        val task = Task(currentTime + timeMillis.coerceAtLeast(0), counter++, Runnable { continuation.resume(Unit) })
        queue.add(task)
        continuation.invokeOnCancellation { task.cancelled = true }
    }

    override fun invokeOnTimeout(timeMillis: Long, block: Runnable, context: CoroutineContext): DisposableHandle {
        val task = Task(currentTime + timeMillis.coerceAtLeast(0), counter++, block)
        queue.add(task)
        return DisposableHandle { task.cancelled = true }
    }

    /** Выполняет всё, что должно произойти «прямо сейчас», не двигая время. */
    fun runCurrent() = advanceBy(0)

    /** Двигает время на [millis] мс, выполняя по пути все наступившие задачи (включая порождённые ими). */
    fun advanceBy(millis: Long) {
        val target = currentTime + millis
        var steps = 0
        while (true) {
            val task = queue.peek() ?: break
            if (task.time > target) break
            queue.poll()
            if (task.cancelled) continue
            if (task.time > currentTime) currentTime = task.time
            task.block.run()
            check(++steps < MAX_STEPS) { "Корутины зациклились: больше $MAX_STEPS шагов без продвижения времени" }
        }
        currentTime = target
    }

    /**
     * Выполняет всё до конца, пока очередь не опустеет. Нельзя вызывать, если где-то работает
     * бесконечный цикл с delay (автообновление, «насос»): он не даст очереди опустеть.
     */
    fun advanceUntilIdle() {
        var steps = 0
        while (true) {
            val task = queue.poll() ?: break
            if (task.cancelled) continue
            if (task.time > currentTime) currentTime = task.time
            task.block.run()
            check(++steps < MAX_STEPS) { "advanceUntilIdle: очередь не пустеет — похоже на бесконечный цикл" }
        }
    }

    /**
     * Запускает [block] как сценарий теста и прокручивает время (шагами по 10 мс, не более 10 минут
     * виртуального времени), пока сценарий не завершится. Работает и при живых фоновых циклах
     * (автообновление, «насос»), которые не дают очереди опустеть. Возвращает результат или бросает
     * исключение сценария.
     */
    fun <T> run(block: suspend CoroutineScope.() -> T): T {
        var result: Result<T>? = null
        scope.launch {
            result = runCatching { block() }
        }
        var waited = 0L
        while (result == null && waited < MAX_WAIT_MS) {
            advanceBy(STEP_MS)
            waited += STEP_MS
        }
        val outcome = checkNotNull(result) { "Сценарий не завершился за ${MAX_WAIT_MS / 1000} с виртуального времени" }
        return outcome.getOrThrow()
    }

    /** Запускает [block] в фоне и возвращает Job; время двигает сам тест через [advanceBy]. */
    fun launch(block: suspend CoroutineScope.() -> Unit): Job = scope.launch(block = block)

    private companion object {
        const val MAX_STEPS = 1_000_000
        const val STEP_MS = 10L
        const val MAX_WAIT_MS = 600_000L
    }
}
