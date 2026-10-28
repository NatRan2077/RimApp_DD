package ru.rim.dd.testutil

import ru.rim.dd.data.dlms.DlmsSessionClient
import ru.rim.dd.data.journal.JournalRecorder
import ru.rim.dd.data.session.MeterSessionManager
import ru.rim.dd.data.state.MeterState
import ru.rim.dd.data.transport.TransportPump

/**
 * Весь сеанс целиком на фейках: настоящие MeterSessionManager, DlmsSessionClient, TransportPump,
 * MeterState и JournalRecorder; подменены только транспорт, нативный клиент и файл журнала.
 * По умолчанию прибор РиМ отвечает настоящим буфером индикации.
 */
class SessionHarness {
    val dispatcher = VirtualTimeDispatcher()
    val dispatchers = TestDispatchers(dispatcher)
    val transport = FakeMeterTransport()
    val native = FakeNativeDlmsApi()
    val journal = FakeJournalWriter()
    val state = MeterState()
    val dlms = DlmsSessionClient(native)
    val pump = TransportPump(transport, native, dispatchers)
    val manager = MeterSessionManager(transport, dlms, pump, state, JournalRecorder(journal), dispatchers)

    init {
        // Настоящий ответ трёхфазного РиМ на любой GET буфера индикации.
        native.onFlatGet = { _, _, _ -> RealMeterBuffer.rawResponse() }
        dispatcher.runCurrent()   // запускает коллекторы состояния соединения внутри менеджера
    }

    fun advance(ms: Long) = dispatcher.advanceBy(ms)

    /** Успешное подключение РиМ по адресу и запуск автообновления — как после нажатия «Подключиться». */
    fun connectRim(address: String = "AA:BB:CC:DD:EE:01", name: String = "RIM1894604314652") {
        dispatcher.run { manager.connectByAddress(address, name) }
        manager.startPolling()
    }

    /** Сколько GET буфера индикации отправлено путём РиМ. */
    fun mainBufferRequests(): Int = native.count("flat-get 7 0.0.21.0.2.255 2")
}
