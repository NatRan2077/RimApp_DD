package ru.rim.dd.testutil

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import ru.rim.dd.core.ble.BleDevice
import ru.rim.dd.core.ble.MeterTransport
import ru.rim.dd.core.model.ConnectionState

/**
 * Транспорт без Bluetooth. Подключается мгновенно (или с заданной ошибкой), запоминает всё, что
 * ему приказали, и умеет «оборвать» и «восстановить» связь по команде теста.
 */
class FakeMeterTransport : MeterTransport {

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    override val connectionState: StateFlow<ConnectionState> = _state

    private val _rssi = MutableStateFlow<Int?>(null)
    override val signalStrengthDbm: StateFlow<Int?> = _rssi

    private val _disconnects = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
    override val unexpectedDisconnects: SharedFlow<Unit> = _disconnects

    /** Входящие кадры: тест кладёт сюда байты, как будто они пришли по notify. */
    val incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)

    /** Что транспорт отправил прибору. */
    val written = mutableListOf<ByteArray>()

    /** Журнал вызовов в порядке поступления: «connectByAddress …», «disconnect», «forceReconnect cold=true» … */
    val events = mutableListOf<String>()

    /** Прибор, который «нашёлся в эфире» при подключении по номеру. */
    var foundDevice = BleDevice(address = "AA:BB:CC:DD:EE:01", name = "RIM1894604314652", rssi = -60)

    /** Если задана — подключение завершается этой ошибкой. */
    var connectError: Exception? = null

    var rssiRequests = 0
        private set

    override fun scan(): Flow<BleDevice> = flowOf(foundDevice)

    override suspend fun connectByAddress(address: String, nameHint: String?) {
        events += "connectByAddress $address"
        connectError?.let { throw it }
        _state.value = ConnectionState.Connected(nameHint ?: address)
    }

    override suspend fun connectBySerialNumber(serialNumber: String, timeoutMs: Long): BleDevice {
        events += "connectBySerialNumber $serialNumber"
        connectError?.let { throw it }
        _state.value = ConnectionState.Connected(foundDevice.name ?: foundDevice.address)
        return foundDevice
    }

    override fun forceReconnect(cold: Boolean) {
        events += "forceReconnect cold=$cold"
    }

    override fun writeBytes(bytes: ByteArray) {
        written += bytes
    }

    override fun incomingBytes(): Flow<ByteArray> = incoming

    override fun requestRssiRead() {
        rssiRequests++
    }

    override fun disconnect() {
        events += "disconnect"
        _state.value = ConnectionState.Idle
    }

    /** Неожиданный обрыв: Android переходит в переподключение, сеанс получает сигнал. */
    fun simulateUnexpectedDisconnect() {
        _state.value = ConnectionState.Reconnecting
        _disconnects.tryEmit(Unit)
    }

    /** Автопереподключение GATT удалось. */
    fun simulateReconnected(name: String = "RIM1894604314652") {
        _state.value = ConnectionState.Connected(name)
    }
}
