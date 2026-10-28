package ru.rim.dd.testutil

import kotlinx.coroutines.CoroutineDispatcher
import ru.rim.dd.core.coroutines.DispatcherProvider

/** Оба диспетчера — один виртуальный: весь код сеанса выполняется последовательно и по виртуальным часам. */
class TestDispatchers(dispatcher: CoroutineDispatcher) : DispatcherProvider {
    override val io: CoroutineDispatcher = dispatcher
    override val default: CoroutineDispatcher = dispatcher
}
