package ru.rim.dd.core.coroutines

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Источник диспетчеров корутин. Классы сеанса, BLE и репозиториев берут диспетчеры отсюда, а не
 * обращаются к [Dispatchers] напрямую, — поэтому в unit-тестах их подменяют на один виртуальный
 * диспетчер, и паузы по 5 секунд проходят мгновенно (см. testutil/VirtualTimeDispatcher).
 */
interface DispatcherProvider {
    /** Блокирующая работа: нативный клиент, файлы журнала, ожидание GATT. */
    val io: CoroutineDispatcher

    /** Фоновые циклы: автообновление, «насос» байтов, переподключение. */
    val default: CoroutineDispatcher
}

/** Боевая реализация: обычные [Dispatchers.IO] и [Dispatchers.Default]. */
@Singleton
class DefaultDispatcherProvider @Inject constructor() : DispatcherProvider {
    override val io: CoroutineDispatcher = Dispatchers.IO
    override val default: CoroutineDispatcher = Dispatchers.Default
}
