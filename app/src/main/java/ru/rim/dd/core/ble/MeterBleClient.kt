package ru.rim.dd.core.ble
import dagger.hilt.android.qualifiers.ApplicationContext
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import no.nordicsemi.android.ble.BleManager
import ru.rim.dd.core.model.ConnectionState
import javax.inject.Inject
import javax.inject.Singleton

/** Найденное при сканировании устройство. */
data class BleDevice(
    val address: String,
    val name: String?,
    val rssi: Int,
)

/**
 * Транспортный слой: умеет только "получить/отправить байты по BLE".
 * НИЧЕГО не знает о DLMS/СПОДЭС — разбор содержимого делает SpodesClientBridge,
 * этот класс лишь качает байты через сервис FFE0 (см. GattUuids) и отдаёт их наружу.
 *
 * NB: это упрощённый каркас поверх Nordic Android BLE Library — на первом шаге
 * реализованы сканирование и подключение; очередь GATT-операций (BleManager.requestConnect()
 * / writeCharacteristic() / setNotificationCallback()) дописывается по мере интеграции
 * с реальным устройством (см. план разработки, спринт 1).
 */
@Singleton
class MeterBleClient @Inject constructor(
    @ApplicationContext val context: Context,
) {
    private val adapter: BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter
    }

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private var gattManager: MeterGattManager? = null

    /** Режим сканирования (UC-01). */
    fun scan(): Flow<BleDevice> = callbackFlow {
        val scanner = adapter?.bluetoothLeScanner
            ?: run { close(); return@callbackFlow }

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                trySend(
                    BleDevice(
                        address = result.device.address,
                        name = result.device.name,
                        rssi = result.rssi,
                    )
                )
            }
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        _connectionState.value = ConnectionState.Scanning
        scanner.startScan(null, settings, callback)

        awaitClose { scanner.stopScan(callback) }
    }

    /** Подключение по адресу (после выбора из скана — UC-01, либо сразу — UC-02). */
    suspend fun connect(device: BluetoothDevice) {
        _connectionState.value = ConnectionState.Connecting(device.address)
        val manager = MeterGattManager(context) { state -> _connectionState.value = state }
        gattManager = manager
        manager.connect(device)
            .retry(3, 200)
            .useAutoConnect(false)
            .await()
    }

    /** Отправить кадр протокола (сырые байты, уже собранные SpodesClientBridge). */
    fun writeBytes(bytes: ByteArray) {
        gattManager?.writeRxCharacteristic(bytes)
    }

    /** Поток входящих кадров (notify c характеристики FFE4). */
    fun incomingBytes(): Flow<ByteArray> = gattManager?.incomingBytes
        ?: throw IllegalStateException("Not connected — call connect() first")

    fun disconnect() {
        gattManager?.disconnect()?.enqueue()
        gattManager = null
        _connectionState.value = ConnectionState.Idle
    }
}

/**
 * Внутренний BleManager (Nordic BLE library): очередь GATT-операций,
 * включение notify на FFE4, запись в FFE9. Детали очереди дополняются
 * на этапе реальной интеграции — здесь зафиксирован контракт (какие байты
 * куда идут), которого достаточно для остальных слоёв (repository, JNI-мост).
 */
private class MeterGattManager(
    context: Context,
    private val onStateChanged: (ConnectionState) -> Unit,
) : BleManager(context) {

    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null

    val incomingBytes = MutableStateFlow(ByteArray(0)) // TODO: заменить на SharedFlow с буфером при интеграции

    override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
        val service = gatt.getService(GattUuids.SERVICE) ?: return false
        rxCharacteristic = service.getCharacteristic(GattUuids.CHAR_RX_WRITE)
        txCharacteristic = service.getCharacteristic(GattUuids.CHAR_TX_NOTIFY)
        return rxCharacteristic != null && txCharacteristic != null
    }

    override fun initialize() {
        setNotificationCallback(txCharacteristic).with { _, data ->
            data.value?.let { incomingBytes.value = it }
        }
        enableNotifications(txCharacteristic).enqueue()
    }

    fun writeRxCharacteristic(bytes: ByteArray) {
        writeCharacteristic(
            rxCharacteristic,
            bytes,
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
        ).enqueue()
    }
}
