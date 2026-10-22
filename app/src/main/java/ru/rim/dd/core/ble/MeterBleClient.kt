package ru.rim.dd.core.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import ru.rim.dd.core.log.AppLog
import androidx.annotation.RequiresPermission
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.coroutines.async
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.observer.ConnectionObserver
import ru.rim.dd.core.model.ConnectionState
import ru.rim.dd.core.model.MeterDeviceName
import ru.rim.dd.core.model.MeterProtocolProfile
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

    /**
     * «Битая» связь: система считает прибор сопряжённым, но ключи не подходят (прибор забыл их,
     * или связь осталась от старой установки). Запрос PIN тогда не появляется вовсе — Android
     * не пересопрягает уже сопряжённое устройство. Один раз за подключение удаляем связь и
     * даём сопряжению пройти заново. Если удалить не удалось — подсказка пользователю.
     */
    private var staleBondHandled = false
    private var stalePairingHint: String? = null

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

        // Новые приборы (AKROS-17…) рекламируются расширенными объявлениями Bluetooth 5
        // (Advertising Extension, вторичный PHY LE 2M), и по умолчанию Android их не показывает.
        // Но телефоны с Bluetooth 4.x расширенных объявлений не умеют: запрос setLegacy(false) на них
        // у части сборок Android молча давал пустой поиск. Поэтому расширенный режим включаем только
        // там, где контроллер его поддерживает, а при отказе сканера возвращаемся к обычному.
        var extended = adapter?.isLeExtendedAdvertisingSupported == true

        fun settings(withExtended: Boolean): ScanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .apply { if (withExtended) setLegacy(false) }
            .build()

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

            override fun onScanFailed(errorCode: Int) {
                AppLog.e("MeterBleClient", "Сканирование не запустилось, код $errorCode (расширенный режим: $extended)")
                if (extended) {
                    // Расширенный режим не принят — пробуем обычный поиск тем же сканером.
                    extended = false
                    try {
                        scanner.stopScan(this)
                        scanner.startScan(null, settings(false), this)
                        return
                    } catch (ex: Exception) {
                        AppLog.e("MeterBleClient", "Повторный запуск поиска не удался: ${ex.message}")
                    }
                }
                close(IllegalStateException(scanFailureText(errorCode)))
            }
        }

        _connectionState.value = ConnectionState.Scanning
        try {
            scanner.startScan(null, settings(extended), callback)
        } catch (ex: Exception) {
            // Например, SecurityException без разрешения или IllegalStateException при выключенном Bluetooth.
            _connectionState.value = ConnectionState.Idle
            throw IllegalStateException("Не удалось начать поиск: ${ex.message ?: "Bluetooth недоступен"}")
        }

        awaitClose {
            runCatching { scanner.stopScan(callback) }
            // Поиск остановлен (кнопка «Стоп» или найден нужный прибор): не оставляем «Scanning»,
            // но и не затираем Connecting, если подключение уже началось.
            if (_connectionState.value is ConnectionState.Scanning) _connectionState.value = ConnectionState.Idle
        }
    }

    /** Понятное описание кода ошибки ScanCallback.onScanFailed. */
    private fun scanFailureText(errorCode: Int): String = when (errorCode) {
        ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "Поиск уже запущен"
        ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED ->
            "Не удалось запустить поиск Bluetooth. Выключите и снова включите Bluetooth на телефоне"
        ScanCallback.SCAN_FAILED_INTERNAL_ERROR ->
            "Ошибка Bluetooth на телефоне при поиске. Выключите и снова включите Bluetooth"
        ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "Этот телефон не поддерживает такой поиск Bluetooth"
        ScanCallback.SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES -> "Bluetooth занят другими приложениями — закройте их и повторите"
        6 -> "Android ограничил частоту поиска Bluetooth — подождите полминуты и повторите"
        else -> "Не удалось начать поиск (код $errorCode)"
    }

    /** Подключение к устройству (UC-01/UC-02). BLUETOOTH_CONNECT запрашивает PairingScreen. */
    @SuppressLint("MissingPermission")
    suspend fun connect(device: BluetoothDevice, nameHint: String? = null) {
        userInitiatedDisconnect = false
        staleBondHandled = false
        stalePairingHint = null
        reconnectJob?.cancel()
        _connectionState.value = ConnectionState.Connecting(device.address)
        AppLog.d("MeterBleClient", "Подключение к ${device.address}, состояние сопряжения в системе: ${bondStateName(device.bondState)}")
        val manager = MeterGattManager(
            context,
            onStateChanged = { state -> _connectionState.value = state },
            onRssiRead = { rssi -> _signalStrengthDbm.value = rssi },
            onDisconnected = { reason -> handleDisconnected(device, reason) },
            onEncryptionFailure = { handleStaleBond(device) },
            nameHint = nameHint,
        )
        gattManager = manager
        awaitConnectWatchingBond(manager, device)
    }

    /**
     * Подключение с контролем сопряжения. Если пользователь нажал «Отмена» в системном окне PIN
     * (или ввёл неверный PIN), Android шлёт переход BONDING → NONE. Очередь Nordic при этом может
     * дойти до «готово» или перезапускать попытки (retry) — приложение тогда делало вид, что
     * подключилось, и снова просило PIN. Поэтому отказ от сопряжения ловим сами и сразу рвём
     * соединение без повторов.
     */
    @SuppressLint("MissingPermission")
    private suspend fun awaitConnectWatchingBond(manager: MeterGattManager, device: BluetoothDevice) {
        val bondRejected = CompletableDeferred<Unit>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
                val changed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                }
                if (changed?.address != device.address) return
                val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                val previous = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.ERROR)
                AppLog.d("MeterBleClient", "Сопряжение ${device.address}: $previous → $state")
                if (state == BluetoothDevice.BOND_NONE && previous == BluetoothDevice.BOND_BONDING) {
                    bondRejected.complete(Unit)
                }
            }
        }
        context.registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
        // await() у Nordic — блокирующий вызов, поэтому его гоним в отдельной корутине на IO и не
        // делаем дочерней: при отказе от сопряжения ждать его завершения нельзя, сначала рвём связь.
        val connecting = clientScope.async(Dispatchers.IO) {
            manager.connect(device)
                .retry(3, 200)
                .useAutoConnect(false)
                .await()
        }
        try {
            try {
                select<Unit> {
                    connecting.onAwait { }
                    bondRejected.onAwait { throw BondRejectedException() }
                }
            } catch (ex: CancellationException) {
                connecting.cancel()
                throw ex
            }
            // Подключение могло завершиться раньше, чем дошла рассылка об отказе.
            if (bondRejected.isCompleted) throw BondRejectedException()
        } catch (ex: BondRejectedException) {
            AppLog.w("MeterBleClient", "Сопряжение с ${device.address} отменено или не удалось — разрываем соединение")
            // Флаг ставим до disconnect(): иначе разрыв сочтётся неожиданным и запустит переподключение.
            userInitiatedDisconnect = true
            reconnectJob?.cancel()
            connecting.cancel()
            manager.disconnect().enqueue()
            gattManager = null
            _connectionState.value = ConnectionState.Idle
            _signalStrengthDbm.value = null
            throw IllegalStateException(
                "Сопряжение не выполнено: окно ввода PIN-кода закрыто или PIN неверный. " +
                        "Повторите подключение и введите PIN-код прибора",
            )
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            val hint = stalePairingHint
            if (hint != null) throw IllegalStateException(hint)
            throw ex
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    private class BondRejectedException : Exception()

    private fun bondStateName(state: Int): String = when (state) {
        BluetoothDevice.BOND_NONE -> "не сопряжён"
        BluetoothDevice.BOND_BONDING -> "идёт сопряжение"
        BluetoothDevice.BOND_BONDED -> "сопряжён"
        else -> "неизвестно ($state)"
    }

    /**
     * Прибор числится сопряжённым, но шифрованный обмен не состоялся — связь «битая». Удаляем её
     * (скрытый метод removeBond: публичного аналога у Android нет) и рвём GATT, чтобы следующая
     * попытка прошла с нуля и система показала запрос PIN.
     */
    @SuppressLint("MissingPermission")
    private fun handleStaleBond(device: BluetoothDevice) {
        if (staleBondHandled) return
        staleBondHandled = true
        AppLog.w("MeterBleClient", "Прибор сопряжён, но шифрованный обмен не удался — удаляем старую связь и сопрягаем заново")
        val removed = try {
            device.javaClass.getMethod("removeBond").invoke(device) as? Boolean ?: false
        } catch (ex: Exception) {
            AppLog.w("MeterBleClient", "removeBond недоступен: ${ex.message}")
            false
        }
        if (!removed) {
            stalePairingHint = "Не удалось пересопрячься с прибором. Откройте настройки Bluetooth телефона, " +
                    "удалите прибор из списка сопряжённых устройств и подключитесь заново"
        }
        gattManager?.disconnect()?.enqueue()
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
            AppLog.e(
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
        AppLog.w(
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
                AppLog.w(
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
                    AppLog.d("MeterBleClient", "Попытка переподключения #$attempt к $device")
                    gattManager?.connect(device)
                        ?.useAutoConnect(false)
                        ?.timeout(RECONNECT_ATTEMPT_TIMEOUT_MS)
                        ?.await()
                        ?: run {
                            // gattManager == null: клиент уже отключён, цикл не нужен
                            return@launch
                        }
                    AppLog.i("MeterBleClient", "Переподключение к $device успешно (попытка #$attempt)")
                    return@launch
                } catch (ex: Exception) {
                    if (userInitiatedDisconnect) return@launch
                    AppLog.w(
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
            AppLog.w(
                "MeterBleClient",
                "Принудительное «холодное» переподключение — несколько подряд циклов без данных, " +
                        "даём пульту паузу ${COLD_RECONNECT_COOLDOWN_MS}мс перед следующей попыткой",
            )
            nextReconnectExtraDelayMs = COLD_RECONNECT_COOLDOWN_MS
        } else {
            AppLog.w("MeterBleClient", "Принудительное переподключение — протокольный уровень счёл DLMS-сессию мёртвой при формально рабочем GATT")
        }
        gattManager?.disconnect()?.enqueue()
    }

    /** Подключение по MAC-адресу без сканирования; присутствие в эфире проверит уже connect(). */
    suspend fun connectByAddress(address: String, nameHint: String? = null) {
        connect(remoteDevice(address), nameHint)
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
        connect(remoteDevice(found.address), found.name)
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
        AppLog.d("MeterBleClient", "Поиск прибора учёта № $wanted в эфире (таймаут ${timeoutMs}мс)")
        val found = withTimeoutOrNull(timeoutMs) {
            scan().first { MeterDeviceName.matches(it.name, wanted) }
        } ?: throw IllegalStateException(
            "Прибор учёта № $wanted не найден за ${timeoutMs / 1000} с. Проверьте номер и " +
                    "убедитесь, что прибор включён и находится рядом"
        )
        AppLog.i("MeterBleClient", "Найден прибор учёта № $wanted: \"${found.name}\" (${found.address})")
        connect(remoteDevice(found.address), found.name)
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
    private val onEncryptionFailure: () -> Unit,
    /** Имя из рекламы (у неспаренного прибора device.name часто null). */
    private val nameHint: String? = null,
) : BleManager(context) {

    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null

    /** Набор UUID, найденный на приборе; от него зависит, нужно ли сопряжение (см. initialize()). */
    private var activeProfile: MeterUartProfile? = null

    /** Тип записи в RX: WRITE_NO_RESPONSE, если характеристика его поддерживает, иначе запись с ответом. */
    private var rxWriteType: Int = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun isAkrosPeer(): Boolean =
        activeProfile == GattUuids.PROFILE_ABF0 ||
                (nameHint ?: bluetoothDevice?.name)?.contains(MeterProtocolProfile.AKROS_NAME_MARKER, ignoreCase = true) == true

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
                AppLog.w("MeterGattManager", "onDeviceFailedToConnect: reason=$reason")
                onStateChanged(ConnectionState.Error.Other("Не удалось подключиться (код $reason)"))
            }

            override fun onDeviceReady(device: BluetoothDevice) {
                onStateChanged(ConnectionState.Connected(device.address))
            }

            override fun onDeviceDisconnecting(device: BluetoothDevice) {
                // Состояние меняем в onDeviceDisconnected(), там известна причина.
            }

            override fun onDeviceDisconnected(device: BluetoothDevice, reason: Int) {
                AppLog.w("MeterGattManager", "onDeviceDisconnected: reason=$reason")
                onDisconnected(reason)
            }
        })
    }

    fun requestRssi() {
        readRssi()
            .with { _, rssi -> onRssiRead(rssi) }
            .fail { _, status -> AppLog.w("MeterGattManager", "readRssi() не удался, status=$status") }
            .enqueue()
    }

    override fun log(priority: Int, message: String) {
        AppLog.println(priority, "MeterGattManager", message)
    }
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
        // Полный список сервисов и характеристик — чтобы по логу можно было добавить новую ревизию платы.
        for (service in gatt.services) {
            AppLog.d(
                "MeterGattManager",
                "Сервис ${service.uuid}: " + service.characteristics.joinToString { "${it.uuid} props=${it.properties}" },
            )
        }
        val found = mutableListOf<Triple<MeterUartProfile, BluetoothGattCharacteristic, BluetoothGattCharacteristic>>()
        for (profile in GattUuids.ALL) {
            val service = gatt.getService(profile.service) ?: continue
            val rx = service.getCharacteristic(profile.rxWrite)
            val tx = service.getCharacteristic(profile.txNotify)
            if (rx == null || tx == null) {
                AppLog.w(
                    "MeterGattManager",
                    "Набор ${profile.label}: сервис есть, но характеристики неполные " +
                            "(RX=${rx?.uuid}, TX=${tx?.uuid}) — пробуем следующий",
                )
                continue
            }
            found += Triple(profile, rx, tx)
        }
        // Если на приборе несколько известных наборов (новый AKROS может нести и FFE0, и ABF0),
        // для прибора с «AKROS» в имени берём набор AKROS, для остальных — первый по порядку.
        val akrosName = (nameHint ?: bluetoothDevice?.name)
            ?.contains(MeterProtocolProfile.AKROS_NAME_MARKER, ignoreCase = true) == true
        val chosen = (if (akrosName) found.firstOrNull { it.first == GattUuids.PROFILE_ABF0 } else null)
            ?: found.firstOrNull()
            ?: findGenericUart(gatt)
        if (chosen == null) {
            AppLog.e(
                "MeterGattManager",
                "Ни один из известных профилей обмена не найден на устройстве " +
                        "(искали: ${GattUuids.ALL.joinToString { it.label }}), и сервиса вида «UART» тоже нет. " +
                        "Сервисы прибора: " + gatt.services.joinToString { it.uuid.toString() },
            )
            return false
        }
        val (profile, rx, tx) = chosen
        rxCharacteristic = rx
        txCharacteristic = tx
        activeProfile = profile
        rxWriteType = if (rx.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
        AppLog.i("MeterGattManager", "Профиль обмена: ${profile.label}")
        AppLog.d(
            "MeterGattManager",
            "RX(write)=${rx.uuid}, props=${rx.properties}; " +
                    "TX(notify)=${tx.uuid}, props=${tx.properties}, " +
                    "descriptors=${tx.descriptors.map { it.uuid }}",
        )
        return true
    }

    /**
     * Запасной поиск «прозрачного UART» для неизвестной ревизии платы: первый нестандартный сервис,
     * где есть характеристика с notify (прибор → телефон) и характеристика на запись.
     */
    private fun findGenericUart(
        gatt: BluetoothGatt,
    ): Triple<MeterUartProfile, BluetoothGattCharacteristic, BluetoothGattCharacteristic>? {
        for (service in gatt.services) {
            val id = service.uuid.toString().lowercase()
            if (id.startsWith("00001800") || id.startsWith("00001801") || id.startsWith("0000180a")) continue
            val tx = service.characteristics.firstOrNull {
                it.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
            } ?: continue
            val rx = service.characteristics.firstOrNull {
                it !== tx && it.properties and
                        (BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
            } ?: continue
            AppLog.w(
                "MeterGattManager",
                "Известный профиль не найден — берём сервис ${service.uuid} как UART: " +
                        "RX=${rx.uuid}, TX=${tx.uuid} (автоопределение)",
            )
            return Triple(MeterUartProfile("авто ${service.uuid}", service.uuid, tx.uuid, rx.uuid), rx, tx)
        }
        return null
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun initialize() {
        requestMtu(247)
            .done { mtu -> AppLog.d("MeterGattManager", "MTU согласован: $mtu") }
            .fail { _, status -> AppLog.w("MeterGattManager", "requestMtu не удался, status=$status (не критично — .split() всё равно спасёт большие записи)") }
            .enqueue()
        // Как у пульта: AKROS принимает данные только после сопряжения (LE Secure Connections с
        // PIN, при legacy-сопряжении разрывает связь с кодом 0x13), а notify включается уже ПОСЛЕ
        // него. PIN запрашивает системное окно Android. Очередь Nordic выполняется по порядку, так
        // что enableNotifications() ниже стартует только когда bond установлен. Явный createBond()
        // (только если связи ещё нет), а не ensureBond(): на ensureBond() очередь после сопряжения
        // зависала — запрос не завершался, хотя bond уже был создан.
        if (isAkrosPeer()) {
            val alreadyBonded = bluetoothDevice?.bondState == BluetoothDevice.BOND_BONDED
            AppLog.d(
                "MeterGattManager",
                "AKROS: сопряжение, уже сопряжены=$alreadyBonded (состояние системы: ${bluetoothDevice?.bondState})",
            )
            if (!alreadyBonded) {
                createBond()
                    .done { AppLog.d("MeterGattManager", "AKROS: сопряжение выполнено") }
                    .fail { _, status -> AppLog.e("MeterGattManager", "AKROS: сопряжение не удалось, status=$status") }
                    .enqueue()
            }
        }
        setNotificationCallback(txCharacteristic).with { _, data ->
            data.value?.let {
                AppLog.d("MeterGattManager", "<< notify (${it.size} байт): ${it.toHex()}")
                if (!incomingBytes.tryEmit(it.copyOf())) {
                    AppLog.e(
                        "MeterGattManager",
                        "ПОТЕРЯН входящий кадр (${it.size} байт) — буфер incomingBytes переполнен, " +
                                "транспортный «насос» не успевает читать",
                    )
                }
            }
        }
        enableNotifications(txCharacteristic)
            .before { AppLog.d("MeterGattManager", "Включаем notify на TX") }
            .done { AppLog.d("MeterGattManager", "Notifications на TX включены успешно") }
            .fail { _, status ->
                AppLog.e("MeterGattManager", "enableNotifications провалился, status=$status")
                // У AKROS notify доступен только по шифрованной связи: отказ при «сопряжённом» приборе
                // значит, что ключи не подходят — запрос PIN сам не появится, связь надо пересоздать.
                if (isAkrosPeer() && bluetoothDevice?.bondState == BluetoothDevice.BOND_BONDED) {
                    onEncryptionFailure()
                }
            }
            .enqueue()
    }

    fun writeRxCharacteristic(bytes: ByteArray) {
        AppLog.d("MeterGattManager", ">> write (${bytes.size} байт): ${bytes.toHex()}")
        // split() обязателен для кадров длиннее MTU-3 (по умолчанию 20 байт): иначе Android молча
        // обрезает WRITE_NO_RESPONSE, прибор получает битый кадр (FCS) и не отвечает. Разбивка
        // безопасна: «прозрачный UART» копит записи в RX-буфер побайтово.
        writeCharacteristic(
            rxCharacteristic,
            bytes,
            rxWriteType,
        )
            .split()
            .done { AppLog.d("MeterGattManager", ">> write подтверждён (enqueue done)") }
            .fail { _, status -> AppLog.e("MeterGattManager", ">> write ПРОВАЛИЛСЯ, status=$status") }
            .enqueue()
    }
}

/** Hex-дамп для логов: "7E A0 07 ...". */
private fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it) }
