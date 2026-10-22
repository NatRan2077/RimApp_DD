package ru.rim.dd.data.transport

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.rim.dd.core.ble.MeterBleClient
import ru.rim.dd.core.bridge.SpodesClientBridge
import javax.inject.Inject
import javax.inject.Singleton

/**
 * «Насос» между BLE-транспортом и нативным клиентом СПОДЭС: одна корутина пересылает входящие
 * notify-пакеты в нативный код, другая опрашивает исходящие байты и шлёт их по BLE.
 * Протокола не знает — только гоняет байты.
 */
@Singleton
class TransportPump @Inject constructor(
    private val ble: MeterBleClient,
    private val spodes: SpodesClientBridge,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    val isActive: Boolean get() = job?.isActive == true

    /**
     * Запускает насос (предыдущий останавливается). [onIncoming] вызывается для каждого
     * входящего пакета до передачи в нативный клиент — для журнала сырых байтов.
     */
    fun start(onIncoming: (ByteArray) -> Unit = {}) {
        stop()
        job = scope.launch {
            launch {
                ble.incomingBytes().collect { chunk ->
                    onIncoming(chunk)
                    spodes.pushIncomingBytes(chunk)
                }
            }
            launch {
                while (true) {
                    val outgoing = spodes.pullOutgoingBytes()
                    if (outgoing.isNotEmpty()) ble.writeBytes(outgoing)
                    delay(POLL_INTERVAL_MS)
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private companion object {
        const val POLL_INTERVAL_MS = 20L
    }
}