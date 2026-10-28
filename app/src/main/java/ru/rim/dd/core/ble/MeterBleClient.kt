package ru.rim.dd.core.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import no.nordicsemi.android.ble.observer.ConnectionObserver
import ru.rim.dd.core.coroutines.DispatcherProvider
import ru.rim.dd.core.log.AppLog
import ru.rim.dd.core.model.ConnectionState
import ru.rim.dd.core.model.MeterDeviceName
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Транспортный слой BLE: состояние связи, подключение к прибору, автопереподключение. Байты
 * передаёт [MeterGattManager] через сервис FFE0/ABF0 (см. GattUuids), поиск эфира делает
 * [BleScanner], восстановление «битого» сопряжения — [BondRecovery], паузы переподключения —
 * [reconnectWithBackoff]. О DLMS/СПОДЭС клиент не знает — разбор делает SpodesClientBridge.
 *
 * Для остальной части приложения это [MeterTransport]: сеанс и репозитории с самим клиентом
 * не работают.
 */
@Singleton
class MeterBleClient @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatchers: DispatcherProvider,
) : MeterTransport {

    private val adapter: BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter
    }

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    override val connectionState: StateFlow<ConnectionState> = _connectionState

    // RSSI активного соединения (BleDevice.rssi — это RSSI со сканирования, после connect он неактуален).
    // Сбрасывается в null при disconnect().
    private val _signalStrengthDbm = MutableStateFlow<Int?>(null)
    override val signalStrengthDbm: StateFlow<Int?> = _signalStrengthDbm

    // Эмитит при каждом НЕОЖИДАННОМ разрыве (не при явном disconnect()): репозиторий должен
    // остановить протокольный обмен и пересобрать сеанс, когда состояние снова станет Connected.
    // Переподключение сделано своим циклом (handleDisconnected()), а не через useAutoConnect Nordic:
    // с ним устройство после разрыва находилось нестабильно, а версия 2.7.4 всё равно шлёт первую
    // попытку нового ConnectRequest с auto=false.
    private val _unexpectedDisconnects = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val unexpectedDisconnects: SharedFlow<Unit> = _unexpectedDisconnects.asSharedFlow()

    /**
     * Отличает своё disconnect() от неожиданного разрыва (оба приходят в onDeviceDisconnected()).
     * Ставится до gattManager.disconnect(), сбрасывается в начале каждого connect().
     */
    private var userInitiatedDisconnect = false

    private val bondRecovery = BondRecovery()

    private val scanner = BleScanner(
        adapter = { adapter },
        onStarting = { _connectionState.value = ConnectionState.Scanning },
        onStartFailed = { _connectionState.value = ConnectionState.Idle },
        // Поиск остановлен (кнопка «Стоп» или найден нужный прибор): не оставляем «Scanning»,
        // но и не затираем Connecting, если подключение уже началось.
        onStopped = { if (_connectionState.value is ConnectionState.Scanning) _connectionState.value = ConnectionState.Idle },
    )

    private var gattManager: MeterGattManager? = null

    /**
     * Пауза перед первой попыткой следующего цикла переподключения (см. forceReconnect(cold)).
     * После нескольких неудачных циклов быстрый GATT-реконнект не помогает: пульту нужно время,
     * чтобы пересобрать свою радиосессию со счётчиком — это отдельный канал, не наш BLE.
     */
    private var nextReconnectExtraDelayMs: Long = 0

    /** Скоуп цикла переподключения; живёт всё время жизни клиента. */
    private val clientScope = CoroutineScope(SupervisorJob() + dispatchers.default)
    private var reconnectJob: Job? = null

    /** Сканирование (UC-01). Разрешения запрашивает PairingScreen до вызова. */
    override fun scan(): Flow<BleDevice> = scanner.scan()

    /** Подключение к устройству (UC-01/UC-02). BLUETOOTH_CONNECT запрашивает PairingScreen. */
    @SuppressLint("MissingPermission")
    private suspend fun connect(device: BluetoothDevice, nameHint: String? = null) {
        userInitiatedDisconnect = false
        bondRecovery.reset()
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
        val connecting = clientScope.async(dispatchers.io) {
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
            val hint = bondRecovery.hint
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
     * Прибор числится сопряжённым, но шифрованный обмен не состоялся — связь «битая». Порядок и
     * «один раз за подключение» — в [BondRecovery]; здесь только то, что требует Android:
     * удалить связь (скрытый метод removeBond: публичного аналога у Android нет) и разорвать GATT.
     */
    @SuppressLint("MissingPermission")
    private fun handleStaleBond(device: BluetoothDevice) {
        bondRecovery.onEncryptionFailure(
            removeBond = {
                try {
                    device.javaClass.getMethod("removeBond").invoke(device) as? Boolean ?: false
                } catch (ex: Exception) {
                    AppLog.w("MeterBleClient", "removeBond недоступен: ${ex.message}")
                    false
                }
            },
            dropConnection = { gattManager?.disconnect()?.enqueue() },
        )
    }

    /**
     * Реакция на любой разрыв GATT. Решение «сам отключился или связь пропала» принимается здесь,
     * т.к. флаг userInitiatedDisconnect известен только клиенту. При неожиданном разрыве —
     * цикл прямого connect() к тому же устройству с растущей паузой (см. [reconnectWithBackoff]),
     * пока не получится или пользователь не нажмёт «Отключиться».
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
            reconnectWithBackoff(
                deviceLabel = device.toString(),
                extraDelayMs = extraDelay,
                isStopped = { userInitiatedDisconnect },
            ) {
                // gattManager == null: клиент уже отключён, цикл не нужен
                val manager = gattManager
                if (manager == null) {
                    false
                } else {
                    manager.connect(device)
                        .useAutoConnect(false)
                        .timeout(ReconnectTiming.ATTEMPT_TIMEOUT_MS)
                        .await()
                    true
                }
            }
        }
    }

    /**
     * Принудительный разрыв GATT, когда протокольный уровень считает DLMS-сессию мёртвой
     * (DM-кадр, ответ без LLC-заголовка), а Android считает соединение рабочим — сам
     * onDeviceDisconnected() тогда не наступит. userInitiatedDisconnect не трогаем: разрыв идёт
     * по ветке «неожиданный» (Reconnecting + переподключение). [cold] — добавить паузу
     * [ReconnectTiming.COLD_COOLDOWN_MS] перед первой попыткой (см. nextReconnectExtraDelayMs).
     */
    @SuppressLint("MissingPermission")
    override fun forceReconnect(cold: Boolean) {
        if (cold) {
            AppLog.w(
                "MeterBleClient",
                "Принудительное «холодное» переподключение — несколько подряд циклов без данных, " +
                        "даём пульту паузу ${ReconnectTiming.COLD_COOLDOWN_MS}мс перед следующей попыткой",
            )
            nextReconnectExtraDelayMs = ReconnectTiming.COLD_COOLDOWN_MS
        } else {
            AppLog.w("MeterBleClient", "Принудительное переподключение — протокольный уровень счёл DLMS-сессию мёртвой при формально рабочем GATT")
        }
        gattManager?.disconnect()?.enqueue()
    }

    /** Подключение по MAC-адресу без сканирования; присутствие в эфире проверит уже connect(). */
    override suspend fun connectByAddress(address: String, nameHint: String?) {
        connect(remoteDevice(address), nameHint)
    }

    private fun remoteDevice(address: String): BluetoothDevice =
        adapter?.getRemoteDevice(address)
            ?: throw IllegalStateException("Bluetooth недоступен на этом устройстве")

    /**
     * Подключение к прибору с ТОЧНО совпадающим серийным номером в имени (UC-02; см.
     * MeterDeviceName.matches). Если такого нет в эфире — ошибка: подключение к чужому прибору
     * недопустимо (команды уйдут на силовое оборудование). Возвращает найденный [BleDevice].
     */
    override suspend fun connectBySerialNumber(serialNumber: String, timeoutMs: Long): BleDevice {
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
    override fun writeBytes(bytes: ByteArray) {
        gattManager?.writeRxCharacteristic(bytes)
    }

    /** Входящие кадры (notify TX-характеристики). */
    override fun incomingBytes(): Flow<ByteArray> = gattManager?.incomingBytes
        ?: throw IllegalStateException("Not connected — call connect() first")

    /** Асинхронно запрашивает RSSI активного соединения; без соединения ничего не делает. */
    @SuppressLint("MissingPermission")
    override fun requestRssiRead() {
        // readRssi() у BleManager protected — вызываем через обёртку внутри MeterGattManager.
        gattManager?.requestRssi()
    }

    /**
     * Явное отключение пользователем: автопереподключение не запускается. Флаг ставится до
     * disconnect(), т.к. onDeviceDisconnected() придёт асинхронно.
     */
    @SuppressLint("MissingPermission")
    override fun disconnect() {
        userInitiatedDisconnect = true
        reconnectJob?.cancel()
        gattManager?.disconnect()?.enqueue()
        gattManager = null
        _connectionState.value = ConnectionState.Idle
        _signalStrengthDbm.value = null
    }
}
