package ru.rim.dd.core.ble

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import ru.rim.dd.core.log.AppLog

/** Паузы и таймауты переподключения. Вынесены из клиента, чтобы тесты проверяли их без Bluetooth. */
internal object ReconnectTiming {
    /** Пауза между попытками: 1 с, удваивается до [MAX_DELAY_MS], чтобы не долбить недоступный счётчик. */
    const val INITIAL_DELAY_MS = 1_000L
    const val MAX_DELAY_MS = 20_000L

    /** Таймаут одной попытки connect(); по истечении цикл переходит к следующей. */
    const val ATTEMPT_TIMEOUT_MS = 10_000L

    /** Пауза «холодного» переподключения — заметно больше [INITIAL_DELAY_MS] (см. MeterBleClient.forceReconnect). */
    const val COLD_COOLDOWN_MS = 5_000L

    /** Следующая пауза после неудачной попытки: удвоение, но не больше [MAX_DELAY_MS]. */
    fun nextDelay(current: Long): Long = (current * 2).coerceAtMost(MAX_DELAY_MS)
}

/**
 * Цикл переподключения после неожиданного разрыва: прямой connect() к тому же устройству с растущей
 * паузой (1 → 2 → 4 … 20 с), пока не получится, пока пользователь не нажмёт «Отключиться»
 * ([isStopped]) или пока корутину не отменят. Никакого Bluetooth внутри нет — сама попытка
 * передаётся лямбдой [connectOnce], поэтому цикл проверяется обычным unit-тестом на виртуальном времени.
 *
 * @param deviceLabel как назвать устройство в логе.
 * @param extraDelayMs пауза перед ПЕРВОЙ попыткой («холодное» переподключение, см. [ReconnectTiming.COLD_COOLDOWN_MS]).
 * @param isStopped true — пользователь отключился сам; цикл тихо завершается.
 * @param connectOnce одна попытка с номером [attempt] (с 1): true — подключились; false — подключать
 *   уже нечего (клиент отключён); исключение — попытка не удалась, будет повтор.
 */
internal suspend fun reconnectWithBackoff(
    deviceLabel: String,
    extraDelayMs: Long,
    isStopped: () -> Boolean,
    connectOnce: suspend (attempt: Int) -> Boolean,
) {
    if (extraDelayMs > 0) {
        AppLog.w(
            "MeterBleClient",
            "«Холодное» переподключение — ждём ${extraDelayMs}мс перед повторным connect() к " +
                    "$deviceLabel (даём пульту время заметить реальную потерю связи и пересобрать " +
                    "радиосессию со счётчиком, см. forceReconnect(cold=true))",
        )
        delay(extraDelayMs)
    }
    var delayMs = ReconnectTiming.INITIAL_DELAY_MS
    var attempt = 0
    while (currentCoroutineContext().isActive && !isStopped()) {
        attempt++
        try {
            AppLog.d("MeterBleClient", "Попытка переподключения #$attempt к $deviceLabel")
            if (connectOnce(attempt)) {
                AppLog.i("MeterBleClient", "Переподключение к $deviceLabel успешно (попытка #$attempt)")
            }
            return
        } catch (ex: Exception) {
            if (isStopped()) return
            AppLog.w(
                "MeterBleClient",
                "Попытка переподключения #$attempt не удалась (${ex.message}) — повтор через ${delayMs}мс",
            )
            delay(delayMs)
            delayMs = ReconnectTiming.nextDelay(delayMs)
        }
    }
}
