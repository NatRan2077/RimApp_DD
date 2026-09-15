package ru.rim.dd.core.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.observer.ConnectionObserver
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
    @ApplicationContext private val context: Context,
) {
    private val adapter: BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter
    }

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    // [Android-патч] Уровень сигнала (RSSI) АКТИВНОГО соединения — для экрана «Настройки».
    // BleDevice.rssi выше — это RSSI, пойманный ПРИ СКАНИРОВАНИИ (UC-01/UC-02), он теряет
    // смысл сразу после подключения. Здесь — отдельное значение, которое обновляется по
    // requestRssiRead() (см. ниже), пока есть активное GATT-соединение; сбрасывается в null
    // при disconnect(), чтобы не показывать пользователю уровень сигнала от УЖЕ разорванного
    // соединения как актуальный.
    private val _signalStrengthDbm = MutableStateFlow<Int?>(null)
    val signalStrengthDbm: StateFlow<Int?> = _signalStrengthDbm

    private var gattManager: MeterGattManager? = null

    /**
     * Режим сканирования (UC-01). BLUETOOTH_SCAN (Android 12+) или ACCESS_FINE_LOCATION
     * (до Android 12) уже запрошены в рантайме на экране PairingScreen до вызова этого
     * метода — здесь подавляем lint-предупреждение, а не риск падения "вслепую".
     */
    @SuppressLint("MissingPermission")
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

    /**
     * Подключение по адресу (после выбора из скана — UC-01, либо сразу — UC-02).
     * BLUETOOTH_CONNECT уже запрошен в рантайме на PairingScreen до вызова.
     */
    @SuppressLint("MissingPermission")
    suspend fun connect(device: BluetoothDevice) {
        _connectionState.value = ConnectionState.Connecting(device.address)
        val manager = MeterGattManager(
            context,
            onStateChanged = { state -> _connectionState.value = state },
            onRssiRead = { rssi -> _signalStrengthDbm.value = rssi },
        )
        gattManager = manager
        manager.connect(device)
            .retry(3, 200)
            .useAutoConnect(false)
            .await()
    }

    /**
     * Подключение по MAC-адресу (когда BluetoothDevice ещё не получен явным сканированием,
     * например — вводом серийного номера/адреса напрямую). adapter.getRemoteDevice не проверяет
     * реальное присутствие устройства в эфире — ошибка проявится уже на этапе connect().
     */
    suspend fun connectByAddress(address: String) {
        val device = adapter?.getRemoteDevice(address)
            ?: throw IllegalStateException("Bluetooth недоступен на этом устройстве")
        connect(device)
    }

    /**
     * Короткое сканирование в поисках устройства, чьё рекламируемое имя начинается
     * с [namePrefix] (пульты РиМ 040.40 рекламируются как "RIM ..."), с подключением
     * к первому найденному. Используется, когда известен только серийный номер ПУ,
     * а не MAC-адрес пульта (UC-02).
     *
     * [Android-патч] Возвращает найденный [BleDevice] (а не Unit) — вызывающему коду
     * (MeterRepositoryImpl.connectBySerialNumber) нужно РЕАЛЬНОЕ рекламируемое имя устройства,
     * а не то, что ввёл пользователь: модель и серийный номер счётчика зашиты именно в имени
     * (см. parseModelAndSerialFromDeviceName()), а введённый пользователем номер — это лишь
     * фильтр поиска.
     */
    suspend fun connectByNamePrefix(namePrefix: String, timeoutMs: Long = 10_000): BleDevice {
        val found = withTimeoutOrNull(timeoutMs) {
            scan().first { it.name?.startsWith(namePrefix, ignoreCase = true) == true }
        } ?: throw IllegalStateException(
            "Устройство с именем на \"$namePrefix\" не найдено за ${timeoutMs / 1000} с — убедитесь, что пульт включён и рядом"
        )
        val device = adapter?.getRemoteDevice(found.address)
            ?: throw IllegalStateException("Bluetooth недоступен на этом устройстве")
        connect(device)
        return found
    }

    /** Отправить кадр протокола (сырые байты, уже собранные SpodesClientBridge). */
    @SuppressLint("MissingPermission")
    fun writeBytes(bytes: ByteArray) {
        gattManager?.writeRxCharacteristic(bytes)
    }

    /** Поток входящих кадров (notify c характеристики FFE4). */
    fun incomingBytes(): Flow<ByteArray> = gattManager?.incomingBytes
        ?: throw IllegalStateException("Not connected — call connect() first")

    /**
     * [Android-патч] Асинхронный запрос текущего RSSI активного GATT-соединения (см.
     * signalStrengthDbm выше) — результат приходит в onRssiRead()/_signalStrengthDbm не сразу,
     * а отдельным callback'ом от Nordic BLE library (как и остальные операции очереди GATT —
     * writeRxCharacteristic() и т.п.). Ничего не делает, если сейчас нет соединения (gattManager
     * == null) — вызывающий код (MeterRepositoryImpl) дёргает это на каждом цикле автообновления
     * "на всякий случай", без проверки состояния связи.
     */
    @SuppressLint("MissingPermission")
    fun requestRssiRead() {
        // [Android-патч] readRssi() у самого BleManager (Nordic BLE library) — protected,
        // наружу (сюда, в MeterBleClient) не виден — компилятор ругался "Cannot access
        // 'readRssi': it is protected in 'MeterGattManager'". Поэтому дёргаем не его
        // напрямую, а публичную обёртку requestRssi() ниже, которая живёт ВНУТРИ
        // MeterGattManager (там protected-метод базового класса доступен) и репортит
        // результат наружу через колбэк — тем же способом, что и onStateChanged.
        gattManager?.requestRssi()
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        gattManager?.disconnect()?.enqueue()
        gattManager = null
        _connectionState.value = ConnectionState.Idle
        _signalStrengthDbm.value = null
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
    private val onRssiRead: (Int) -> Unit,
) : BleManager(context) {

    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null

    val incomingBytes = MutableStateFlow(ByteArray(0)) // TODO: заменить на SharedFlow с буфером при интеграции

    init {
        // [Android-патч] НАЙДЕН БАГ ("Соединение: Не подключено" при 100% реально рабочей связи):
        // onStateChanged передавался в конструктор, но НИГДЕ не вызывался — поэтому _connectionState
        // в MeterBleClient навсегда застревал на значении Connecting(...), выставленном в connect()
        // ДО того, как GATT-соединение реально поднялось, и никогда не переходил в Connected. Экран
        // «Настройки» смотрит именно на _connectionState — отсюда ложное "Не подключено", хотя весь
        // остальной обмен данными (чтение буфера, RSSI и т.д.) на самом деле работает: он идёт
        // напрямую через gattManager/incomingBytes, минуя _connectionState вовсе, поэтому баг был
        // незаметен по функциональности — только по индикатору на экране «Настройки».
        // Исправлено штатным способом Nordic BLE library — ConnectionObserver, который реально
        // получает события GATT-соединения (в отличие от заглушки, которая просто хранила колбэк).
        // Идентификатор в ConnectionState.Connected/Connecting — MAC-адрес устройства (device.address),
        // как и было в исходном connect() — серийный номер счётчика на этот момент ещё не разобран
        // (см. applyDeviceNameInfo() в MeterRepositoryImpl), а нигде в коде значение
        // ConnectionState.Connected.serialNumber пока не читается (проверено — только
        // "is ConnectionState.Connected" в SettingsScreen), так что смена идентификатора безопасна.
        setConnectionObserver(object : ConnectionObserver {
            override fun onDeviceConnecting(device: BluetoothDevice) {
                onStateChanged(ConnectionState.Connecting(device.address))
            }

            override fun onDeviceConnected(device: BluetoothDevice) {
                // GATT-уровень подключен, но сервисы/notify ещё не настроены (это делает
                // initialize() ниже) — по-настоящему готово к обмену данными в onDeviceReady().
            }

            override fun onDeviceFailedToConnect(device: BluetoothDevice, reason: Int) {
                Log.w("MeterGattManager", "onDeviceFailedToConnect: reason=$reason")
                onStateChanged(ConnectionState.Error.Other("Не удалось подключиться (код $reason)"))
            }

            override fun onDeviceReady(device: BluetoothDevice) {
                onStateChanged(ConnectionState.Connected(device.address))
            }

            override fun onDeviceDisconnecting(device: BluetoothDevice) {
                // Ничего не переключаем здесь — дожидаемся onDeviceDisconnected(reason) ниже,
                // там же будет и настоящая причина разрыва.
            }

            override fun onDeviceDisconnected(device: BluetoothDevice, reason: Int) {
                // [Android-патч] Раньше НЕОЖИДАННЫЙ разрыв связи (устройство выключилось/ушло из
                // радиуса действия) вообще никак не отражался в _connectionState — состояние так и
                // оставалось "Connected" до следующего явного disconnect()/connect(). Теперь любой
                // разрыв (в т.ч. непредвиденный) сразу переводит состояние в Idle.
                Log.w("MeterGattManager", "onDeviceDisconnected: reason=$reason")
                onStateChanged(ConnectionState.Idle)
            }
        })
    }

    /**
     * [Android-патч] Публичная обёртка над readRssi() (см. MeterBleClient.requestRssiRead()) —
     * сам readRssi() объявлен protected в базовом BleManager (Nordic BLE library) и виден
     * только отсюда, изнутри подкласса, поэтому наружу его не отдать напрямую.
     */
    fun requestRssi() {
        readRssi()
            .with { _, rssi -> onRssiRead(rssi) }
            .fail { _, status -> Log.w("MeterGattManager", "readRssi() не удался, status=$status") }
            .enqueue()
    }

    override fun log(priority: Int, message: String) {
        // Внутренние логи Nordic BLE library — обычно тут написана точная причина
        // разрыва (например, отсутствие CCCD-дескриптора у характеристики notify).
        Log.println(priority, "MeterGattManager", message)
    }

    override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
        val service = gatt.getService(GattUuids.SERVICE)
        if (service == null) {
            Log.e("MeterGattManager", "Сервис ${GattUuids.SERVICE} не найден на устройстве")
            return false
        }
        rxCharacteristic = service.getCharacteristic(GattUuids.CHAR_RX_WRITE)
        txCharacteristic = service.getCharacteristic(GattUuids.CHAR_TX_NOTIFY)
        Log.d(
            "MeterGattManager",
            "RX(write)=${rxCharacteristic?.uuid}, props=${rxCharacteristic?.properties}; " +
                    "TX(notify)=${txCharacteristic?.uuid}, props=${txCharacteristic?.properties}, " +
                    "descriptors=${txCharacteristic?.descriptors?.map { it.uuid }}",
        )
        return rxCharacteristic != null && txCharacteristic != null
    }

    override fun initialize() {
        requestMtu(247)
            .done { mtu -> Log.d("MeterGattManager", "MTU согласован: $mtu") }
            .fail { _, status -> Log.w("MeterGattManager", "requestMtu не удался, status=$status (не критично — .split() всё равно спасёт большие записи)") }
            .enqueue()
        setNotificationCallback(txCharacteristic).with { _, data ->
            data.value?.let {
                Log.d("MeterGattManager", "<< notify (${it.size} байт): ${it.toHex()}")
                incomingBytes.value = it
            }
        }
        enableNotifications(txCharacteristic)
            .done { Log.d("MeterGattManager", "Notifications на TX включены успешно") }
            .fail { _, status -> Log.e("MeterGattManager", "enableNotifications провалился, status=$status") }
            .enqueue()
    }

    fun writeRxCharacteristic(bytes: ByteArray) {
        Log.d("MeterGattManager", ">> write (${bytes.size} байт): ${bytes.toHex()}")
        // .split() ОБЯЗАТЕЛЕН для кадров длиннее MTU-3 (по умолчанию MTU=23 → 20 байт полезной
        // нагрузки): без него Android молча обрезает WRITE_TYPE_NO_RESPONSE-запись длиннее лимита,
        // устройство получает битый кадр (плохой FCS) и просто не отвечает — именно это и
        // происходило (SNRM 36 байт улетал одним куском, ответа не было вообще).
        // Это "прозрачный UART"-мост: он копит входящие GATT-записи в RX-буфер побайтово,
        // поэтому разбивка на несколько последовательных записей по MTU эквивалентна одной
        // большой записи и ничего не портит на стороне устройства.
        writeCharacteristic(
            rxCharacteristic,
            bytes,
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
        )
            .split()
            .done { Log.d("MeterGattManager", ">> write подтверждён (enqueue done)") }
            .fail { _, status -> Log.e("MeterGattManager", ">> write ПРОВАЛИЛСЯ, status=$status") }
            .enqueue()
    }
}

/** Компактный hex-дамп для логов ("7E A0 07 ..."), чтобы видеть реальные байты кадра. */
private fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it) }
