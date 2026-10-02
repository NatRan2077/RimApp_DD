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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.observer.ConnectionObserver
import ru.rim.dd.core.model.ConnectionState
import ru.rim.dd.core.model.MeterDeviceName
import javax.inject.Inject
import javax.inject.Singleton

/** Найденное при сканировании устройство. */
data class BleDevice(
    val address: String,
    val name: String?,
    val rssi: Int,
)

/**
 * Транспортный слой BLE: передаёт сырые байты через сервис FFE0/ABF0 (см. GattUuids).
 * О DLMS/СПОДЭС не знает — разбор делает SpodesClientBridge.
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

    // RSSI активного соединения (BleDevice.rssi — это RSSI со сканирования, после connect он неактуален).
    // Сбрасывается в null при disconnect().
    private val _signalStrengthDbm = MutableStateFlow<Int?>(null)
    val signalStrengthDbm: StateFlow<Int?> = _signalStrengthDbm

    // Эмитит при каждом НЕОЖИДАННОМ разрыве (не при явном disconnect()): репозиторий должен
    // остановить протокольный обмен и пересобрать сеанс, когда состояние снова станет Connected.
    // Переподключение сделано своим циклом (handleDisconnected()), а не через useAutoConnect Nordic:
    // с ним устройство после разрыва находилось нестабильно, а версия 2.7.4 всё равно шлёт первую
    // попытку нового ConnectRequest с auto=false.
    private val _unexpectedDisconnects = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val unexpectedDisconnects: SharedFlow<Unit> = _unexpectedDisconnects.asSharedFlow()

    /**
     * Отличает своё disconnect() от неожиданного разрыва (оба приходят в onDeviceDisconnected()).
     * Ставится до gattManager.disconnect(), сбрасывается в начале каждого connect().
     */
    private var userInitiatedDisconnect = false

    private var gattManager: MeterGattManager? = null

    /**
     * Пауза перед первой попыткой следующего цикла переподключения (см. forceReconnect(cold)).
     * После нескольких неудачных циклов быстрый GATT-реконнект не помогает: пульту нужно время,
     * чтобы пересобрать свою радиосессию со счётчиком — это отдельный канал, не наш BLE.
     */
    private var nextReconnectExtraDelayMs: Long = 0

    /** Скоуп цикла переподключения; живёт всё время жизни клиента. */
    private val clientScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var reconnectJob: Job? = null

    /**
     * Сканирование (UC-01). Разрешения (BLUETOOTH_SCAN / ACCESS_FINE_LOCATION) запрашивает
     * PairingScreen до вызова.
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
                        // Имя из рекламы приоритетнее device.name: тот берётся из кэша стека и для
                        // неспаренного прибора часто null, а список и поиск по номеру работают по имени.
                        name = result.scanRecord?.deviceName ?: result.device.name,
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

    /** Подключение к устройству (UC-01/UC-02). BLUETOOTH_CONNECT запрашивает PairingScreen. */
    @SuppressLint("MissingPermission")
    suspend fun connect(device: BluetoothDevice) {
        userInitiatedDisconnect = false
        reconnectJob?.cancel()
        _connectionState.value = ConnectionState.Connecting(device.address)
        val manager = MeterGattManager(
            context,
            onStateChanged = { state -> _connectionState.value = state },
            onRssiRead = { rssi -> _signalStrengthDbm.value = rssi },
            onDisconnected = { reason -> handleDisconnected(device, reason) },
        )
        gattManager = manager
        manager.connect(device)
            .retry(3, 200)
            .useAutoConnect(false)
            .await()
    }

    /**
     * Реакция на любой разрыв GATT. Решение «сам отключился или связь пропала» принимается здесь,
     * т.к. флаг userInitiatedDisconnect известен только клиенту. При неожиданном разрыве —
     * цикл прямого connect() к тому же устройству с растущей паузой (RECONNECT_INITIAL_DELAY_MS →
     * RECONNECT_MAX_DELAY_MS), пока не получится или пользователь не нажмёт «Отключиться».
     */
    @SuppressLint("MissingPermission")
    private fun handleDisconnected(device: BluetoothDevice, reason: Int) {
        if (userInitiatedDisconnect) {
            _connectionState.value = ConnectionState.Idle
            return
        }
        // REASON_NOT_SUPPORTED — не разрыв, а отказ нашего isRequiredServiceSupported():
        // повтор ничего не изменит (раньше цикл бесконечно переподключался к такому прибору).
        if (reason == ConnectionObserver.REASON_NOT_SUPPORTED) {
            Log.e(
                "MeterBleClient",
                "Прибор $device не поддерживается: ни один известный профиль обмена не найден " +
                        "(см. isRequiredServiceSupported). Переподключение НЕ запускаем — оно ничего не изменит",
            )
            reconnectJob?.cancel()
            _connectionState.value = ConnectionState.Error.Other(
                "Прибор не поддерживается: на нём нет ожидаемого сервиса обмена данными"
            )
            return
        }
        Log.w(
            "MeterBleClient",
            "Неожиданный разрыв связи (устройство вне радиуса действия/выключено?) — " +
                    "переходим в Reconnecting и переподключаемся к $device своим циклом",
        )
        _connectionState.value = ConnectionState.Reconnecting
        _unexpectedDisconnects.tryEmit(Unit)
        reconnectJob?.cancel()
        reconnectJob = clientScope.launch {
            // Забираем и обнуляем: пауза нужна один раз, перед первой попыткой этого цикла.
            val extraDelay = nextReconnectExtraDelayMs
            nextReconnectExtraDelayMs = 0
            if (extraDelay > 0) {
                Log.w(
                    "MeterBleClient",
                    "«Холодное» переподключение — ждём ${extraDelay}мс перед повторным connect() к " +
                            "$device (даём пульту время заметить реальную потерю связи и пересобрать " +
                            "радиосессию со счётчиком, см. forceReconnect(cold=true))",
                )
                delay(extraDelay)
            }
            var delayMs = RECONNECT_INITIAL_DELAY_MS
            var attempt = 0
            while (isActive && !userInitiatedDisconnect) {
                attempt++
                try {
                    Log.d("MeterBleClient", "Попытка переподключения #$attempt к $device")
                    gattManager?.connect(device)
                        ?.useAutoConnect(false)
                        ?.timeout(RECONNECT_ATTEMPT_TIMEOUT_MS)
                        ?.await()
                        ?: run {
                            // gattManager == null: клиент уже отключён, цикл не нужен
                            return@launch
                        }
                    Log.i("MeterBleClient", "Переподключение к $device успешно (попытка #$attempt)")
                    return@launch
                } catch (ex: Exception) {
                    if (userInitiatedDisconnect) return@launch
                    Log.w(
                        "MeterBleClient",
                        "Попытка переподключения #$attempt не удалась (${ex.message}) — повтор через ${delayMs}мс",
                    )
                    delay(delayMs)
                    delayMs = (delayMs * 2).coerceAtMost(RECONNECT_MAX_DELAY_MS)
                }
            }
        }
    }

    /**
     * Принудительный разрыв GATT, когда протокольный уровень считает DLMS-сессию мёртвой
     * (DM-кадр, ответ без LLC-заголовка), а Android считает соединение рабочим — сам
     * onDeviceDisconnected() тогда не наступит. userInitiatedDisconnect не трогаем: разрыв идёт
     * по ветке «неожиданный» (Reconnecting + переподключение). [cold] — добавить паузу
     * COLD_RECONNECT_COOLDOWN_MS перед первой попыткой (см. nextReconnectExtraDelayMs).
     */
    @SuppressLint("MissingPermission")
    fun forceReconnect(cold: Boolean = false) {
        if (cold) {
            Log.w(
                "MeterBleClient",
                "Принудительное «холодное» переподключение — несколько подряд циклов без данных, " +
                        "даём пульту паузу ${COLD_RECONNECT_COOLDOWN_MS}мс перед следующей попыткой",
            )
            nextReconnectExtraDelayMs = COLD_RECONNECT_COOLDOWN_MS
        } else {
            Log.w("MeterBleClient", "Принудительное переподключение — протокольный уровень счёл DLMS-сессию мёртвой при формально рабочем GATT")
        }
        gattManager?.disconnect()?.enqueue()
    }

    /** Подключение по MAC-адресу без сканирования; присутствие в эфире проверит уже connect(). */
    suspend fun connectByAddress(address: String) {
        connect(remoteDevice(address))
    }

    private fun remoteDevice(address: String): BluetoothDevice =
        adapter?.getRemoteDevice(address)
            ?: throw IllegalStateException("Bluetooth недоступен на этом устройстве")

    /**
     * Сканирование по префиксу имени [namePrefix] и подключение к первому найденному.
     * Возвращает найденный [BleDevice]: модель и серийный номер берутся из его реального имени.
     */
    @Deprecated(
        "Подключается к ПЕРВОМУ прибору с подходящим префиксом имени — при двух приборах рядом " +
                "это подключение к случайному из них (реальная ошибка, см. connectBySerialNumber ниже). " +
                "Для подключения по номеру ПУ используйте connectBySerialNumber().",
        ReplaceWith("connectBySerialNumber(serialNumber)"),
    )
    suspend fun connectByNamePrefix(namePrefix: String, timeoutMs: Long = 10_000): BleDevice {
        val found = withTimeoutOrNull(timeoutMs) {
            scan().first { it.name?.startsWith(namePrefix, ignoreCase = true) == true }
        } ?: throw IllegalStateException(
            "Устройство с именем на \"$namePrefix\" не найдено за ${timeoutMs / 1000} с — убедитесь, что пульт включён и рядом"
        )
        connect(remoteDevice(found.address))
        return found
    }

    /**
     * Подключение к прибору с ТОЧНО совпадающим серийным номером в имени (UC-02; см.
     * MeterDeviceName.matches). Если такого нет в эфире — ошибка: подключение к чужому прибору
     * недопустимо (команды уйдут на силовое оборудование). Возвращает найденный [BleDevice].
     */
    suspend fun connectBySerialNumber(serialNumber: String, timeoutMs: Long = SCAN_BY_SERIAL_TIMEOUT_MS): BleDevice {
        val wanted = MeterDeviceName.normalizeSerial(serialNumber)
            ?: throw IllegalArgumentException(
                "\"$serialNumber\" не похож на номер прибора учёта — ожидается до " +
                        "${MeterDeviceName.SERIAL_DIGITS} цифр (номер со счётчика) либо 13 цифр из имени устройства"
            )
        Log.d("MeterBleClient", "Поиск прибора учёта № $wanted в эфире (таймаут ${timeoutMs}мс)")
        val found = withTimeoutOrNull(timeoutMs) {
            scan().first { MeterDeviceName.matches(it.name, wanted) }
        } ?: throw IllegalStateException(
            "Прибор учёта № $wanted не найден за ${timeoutMs / 1000} с. Проверьте номер и " +
                    "убедитесь, что прибор включён и находится рядом"
        )
        Log.i("MeterBleClient", "Найден прибор учёта № $wanted: \"${found.name}\" (${found.address})")
        connect(remoteDevice(found.address))
        return found
    }

    @SuppressLint("MissingPermission")
    fun writeBytes(bytes: ByteArray) {
        gattManager?.writeRxCharacteristic(bytes)
    }

    /** Входящие кадры (notify TX-характеристики). */
    fun incomingBytes(): Flow<ByteArray> = gattManager?.incomingBytes
        ?: throw IllegalStateException("Not connected — call connect() first")

    /** Асинхронно запрашивает RSSI активного соединения; без соединения ничего не делает. */
    @SuppressLint("MissingPermission")
    fun requestRssiRead() {
        // readRssi() у BleManager protected — вызываем через обёртку внутри MeterGattManager.
        gattManager?.requestRssi()
    }

    /**
     * Явное отключение пользователем: автопереподключение не запускается. Флаг ставится до
     * disconnect(), т.к. onDeviceDisconnected() придёт асинхронно.
     */
    @SuppressLint("MissingPermission")
    fun disconnect() {
        userInitiatedDisconnect = true
        reconnectJob?.cancel()
        gattManager?.disconnect()?.enqueue()
        gattManager = null
        _connectionState.value = ConnectionState.Idle
        _signalStrengthDbm.value = null
    }

    private companion object {
        // Пауза между попытками: 1 с, удваивается до 20 с, чтобы не долбить недоступный счётчик.
        const val RECONNECT_INITIAL_DELAY_MS = 1_000L
        const val RECONNECT_MAX_DELAY_MS = 20_000L
        // Таймаут одной попытки connect(); по истечении цикл переходит к следующей.
        const val RECONNECT_ATTEMPT_TIMEOUT_MS = 10_000L

        /** Пауза «холодного» переподключения — заметно больше RECONNECT_INITIAL_DELAY_MS, см. nextReconnectExtraDelayMs. */
        const val COLD_RECONNECT_COOLDOWN_MS = 5_000L

        /** Время поиска прибора по номеру: нужный может откликнуться не первым. */
        const val SCAN_BY_SERIAL_TIMEOUT_MS = 15_000L
    }
}

/** Внутренний BleManager (Nordic): notify на TX-характеристике, запись в RX. */
private class MeterGattManager(
    context: Context,
    private val onStateChanged: (ConnectionState) -> Unit,
    private val onRssiRead: (Int) -> Unit,
    private val onDisconnected: (reason: Int) -> Unit,
) : BleManager(context) {

    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null

    /**
     * Входящие notify-кадры. Именно SharedFlow(replay=0) с большим буфером, а не StateFlow:
     * StateFlow отдаёт новому подписчику последний кадр прошлой сессии (после переподключения
     * ответы съезжали на кадр) и конфлейтит сегменты многосегментного ответа, теряя средние.
     * Без подписчиков кадры отбрасываются — устаревшие байты не должны попасть в новую сессию.
     */
    val incomingBytes = MutableSharedFlow<ByteArray>(
        replay = 0,
        extraBufferCapacity = 512,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )

    init {
        // Идентификатор в Connecting/Connected — MAC (device.address): серийный номер на этом
        // этапе ещё не разобран. Выбор Idle/Reconnecting делает MeterBleClient.handleDisconnected(),
        // здесь только репортим сам факт разрыва.
        setConnectionObserver(object : ConnectionObserver {
            override fun onDeviceConnecting(device: BluetoothDevice) {
                onStateChanged(ConnectionState.Connecting(device.address))
            }

            override fun onDeviceConnected(device: BluetoothDevice) {
                // Сервисы/notify ещё не настроены — готовность в onDeviceReady().
            }

            override fun onDeviceFailedToConnect(device: BluetoothDevice, reason: Int) {
                Log.w("MeterGattManager", "onDeviceFailedToConnect: reason=$reason")
                onStateChanged(ConnectionState.Error.Other("Не удалось подключиться (код $reason)"))
            }

            override fun onDeviceReady(device: BluetoothDevice) {
                onStateChanged(ConnectionState.Connected(device.address))
            }

            override fun onDeviceDisconnecting(device: BluetoothDevice) {
                // Состояние меняем в onDeviceDisconnected(), там известна причина.
            }

            override fun onDeviceDisconnected(device: BluetoothDevice, reason: Int) {
                Log.w("MeterGattManager", "onDeviceDisconnected: reason=$reason")
                onDisconnected(reason)
            }
        })
    }

    fun requestRssi() {
        readRssi()
            .with { _, rssi -> onRssiRead(rssi) }
            .fail { _, status -> Log.w("MeterGattManager", "readRssi() не удался, status=$status") }
            .enqueue()
    }

    override fun log(priority: Int, message: String) {
        Log.println(priority, "MeterGattManager", message)
    }
    override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
        for (profile in GattUuids.ALL) {
            val service = gatt.getService(profile.service) ?: continue
            val rx = service.getCharacteristic(profile.rxWrite)
            val tx = service.getCharacteristic(profile.txNotify)
            if (rx == null || tx == null) {
                Log.w(
                    "MeterGattManager",
                    "Набор ${profile.label}: сервис есть, но характеристики неполные " +
                            "(RX=${rx?.uuid}, TX=${tx?.uuid}) — пробуем следующий",
                )
                continue
            }
            rxCharacteristic = rx
            txCharacteristic = tx
            Log.i("MeterGattManager", "Профиль обмена: ${profile.label}")
            Log.d(
                "MeterGattManager",
                "RX(write)=${rx.uuid}, props=${rx.properties}; " +
                        "TX(notify)=${tx.uuid}, props=${tx.properties}, " +
                        "descriptors=${tx.descriptors.map { it.uuid }}",
            )
            return true
        }
        Log.e(
            "MeterGattManager",
            "Ни один из известных профилей обмена не найден на устройстве " +
                    "(искали: ${GattUuids.ALL.joinToString { it.label }}). Сервисы прибора: " +
                    gatt.services.joinToString { it.uuid.toString() },
        )
        return false
    }

    override fun initialize() {
        requestMtu(247)
            .done { mtu -> Log.d("MeterGattManager", "MTU согласован: $mtu") }
            .fail { _, status -> Log.w("MeterGattManager", "requestMtu не удался, status=$status (не критично — .split() всё равно спасёт большие записи)") }
            .enqueue()
        setNotificationCallback(txCharacteristic).with { _, data ->
            data.value?.let {
                Log.d("MeterGattManager", "<< notify (${it.size} байт): ${it.toHex()}")
                if (!incomingBytes.tryEmit(it.copyOf())) {
                    Log.e(
                        "MeterGattManager",
                        "ПОТЕРЯН входящий кадр (${it.size} байт) — буфер incomingBytes переполнен, " +
                                "транспортный «насос» не успевает читать",
                    )
                }
            }
        }
        enableNotifications(txCharacteristic)
            .done { Log.d("MeterGattManager", "Notifications на TX включены успешно") }
            .fail { _, status -> Log.e("MeterGattManager", "enableNotifications провалился, status=$status") }
            .enqueue()
    }

    fun writeRxCharacteristic(bytes: ByteArray) {
        Log.d("MeterGattManager", ">> write (${bytes.size} байт): ${bytes.toHex()}")
        // split() обязателен для кадров длиннее MTU-3 (по умолчанию 20 байт): иначе Android молча
        // обрезает WRITE_NO_RESPONSE, прибор получает битый кадр (FCS) и не отвечает. Разбивка
        // безопасна: «прозрачный UART» копит записи в RX-буфер побайтово.
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

/** Hex-дамп для логов: "7E A0 07 ...". */
private fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it) }
