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
import kotlinx.coroutines.flow.filter
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

    // [Android-патч] Механизм переподключения. Эмитит один раз при КАЖДОМ НЕОЖИДАННОМ разрыве
    // связи (устройство выключилось/ушло из радиуса действия — НЕ явное disconnect() ниже) — сигнал
    // для MeterRepositoryImpl "останови протокольный обмен (HDLC/SpodesClient/автообновление),
    // жди, пока _connectionState снова не станет Connected, и тогда пересобери сеанс заново".
    //
    // [Android-патч] ИСТОРИЯ: перебрали оба варианта штатного механизма Nordic BLE library —
    // useAutoConnect(false)+retry()/timeout() (активный "прямой коннект": на практике вообще не
    // находил устройство после разрыва) и useAutoConnect(true) (пассивное фоновое сканирование
    // самого Android BLE stack: иногда работало, но у версии библиотеки 2.7.4, которая здесь
    // используется, есть задокументированное ограничение — КАЖДАЯ "первая попытка" нового
    // ConnectRequest всё равно уходит в Android с auto=false, см. javadoc метода
    // ConnectRequest.useAutoConnect(boolean)). Кроме того, по свежим логам выяснилось, что узкое
    // место вообще не в скорости самого GATT-реконнекта (он обычно укладывается в доли секунды —
    // сотни миллисекунд), а в том, что СЧЁТЧИК не успевает "остыть" после резкого разрыва: если
    // сразу после физического переподключения слать SNRM, DLMS-сессия почти гарантированно тут же
    // разваливается опять ("не найден LLC-заголовок") и по кругу — приложение уходит в затяжной
    // цикл forceReconnect() каждые ~12-15 с без единого успешного чтения, пока его не перезапустят
    // вручную (см. RECONNECT_SETTLE_DELAY_MS в MeterRepositoryImpl — отдельный фикс именно этого).
    //
    // Поэтому отказались от борьбы с автоconnect-семантикой Nordic вообще: переподключаемся
    // сами, простым прямым connect(useAutoConnect=false) к ЗАПОМНЕННОМУ устройству
    // (см. lastKnownDevice ниже) со своим экспоненциальным бэкоффом между попытками — полностью
    // предсказуемое поведение, не зависящее от внутренней логики библиотеки. См. handleDisconnected().
    private val _unexpectedDisconnects = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val unexpectedDisconnects: SharedFlow<Unit> = _unexpectedDisconnects.asSharedFlow()

    /**
     * [Android-патч] Отличает СВОЁ disconnect() (пользователь нажал «Отключиться» на экране
     * «Настройки») от НЕОЖИДАННОГО разрыва — оба приходят в один и тот же колбэк
     * ConnectionObserver.onDeviceDisconnected(), но реагировать на них нужно по-разному
     * (см. handleDisconnected() ниже): в первом случае — просто Idle и всё, во втором —
     * Reconnecting + автопереподключение. Выставляется в true ПЕРЕД вызовом gattManager.disconnect()
     * в disconnect() ниже и сбрасывается обратно в false в начале КАЖДОГО нового connect().
     */
    private var userInitiatedDisconnect = false

    private var gattManager: MeterGattManager? = null

    /**
     * [Android-патч] Явно запоминаем, к какому именно устройству подключились — ПО ЗАПРОСУ
     * пользователя (см. обсуждение реконнекта): вместо того чтобы полагаться на скрытую логику
     * Nordic BLE library (useAutoConnect), при любом неожиданном разрыве просто переподключаемся
     * заново к ЭТОМУ ЖЕ [BluetoothDevice] сами, своим циклом (см. handleDisconnected()). До этого
     * тот же объект фактически "помнился" неявно — через замыкание над параметром device в
     * onDisconnected-лямбде connect() ниже — но явное поле нагляднее и позволяет переиспользовать
     * его (например, из forceReconnect()), не пробрасывая device через колбэки.
     */
    private var lastKnownDevice: BluetoothDevice? = null

    /**
     * [Android-патч] «Холодное» переподключение (см. forceReconnect(cold) ниже и историю в
     * MeterRepositoryImpl.consecutiveForceReconnectsWithoutData) — по свежим логам выяснилось,
     * что ПРОСТОЕ переподключение GATT (без паузы) после нескольких подряд неудачных попыток
     * восстановить DLMS-сеанс почти никогда не помогает: пульт/счётчик отвечают либо пустым
     * ответом, либо кадром без LLC-заголовка, СНОВА и СНОВА, даже после свежего GATT-коннекта —
     * то есть дело не в GATT (он поднимается за десятки-сотни мс), а в том, что физическая
     * радиосессия ПУЛЬТ↔СЧЁТЧИК (это ДРУГОЙ канал, не наш BLE между телефоном и пультом — см.
     * собственные логи прошивки пульта про "Radio Error - TimeOut Answer") не успевает
     * пересобраться за то же время. Настоящее ПЕРВОЕ подключение (когда пользователь только
     * открыл приложение) обычно случается спустя заметное время простоя BLE — за это время
     * пульт, видимо, успевает сам заметить, что радиосвязь со счётчиком нужно поднять заново.
     * Наш обычный реконнект — почти мгновенный, такого "простоя" пульту не даёт. Поэтому после
     * нескольких подряд неудачных циклов протокольный уровень просит именно "холодное"
     * переподключение — сюда записывается пауза, которую reconnect-цикл в handleDisconnected()
     * выдержит ПЕРЕД самой первой попыткой connect(), имитируя более естественный разрыв.
     */
    private var nextReconnectExtraDelayMs: Long = 0

    /** Собственный скоуп для цикла переподключения (см. handleDisconnected()) — живёт всё время жизни клиента. */
    private val clientScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var reconnectJob: Job? = null

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
        userInitiatedDisconnect = false
        lastKnownDevice = device // [Android-патч] см. lastKnownDevice выше
        reconnectJob?.cancel()
        _connectionState.value = ConnectionState.Connecting(device.address)
        val manager = MeterGattManager(
            context,
            onStateChanged = { state -> _connectionState.value = state },
            onRssiRead = { rssi -> _signalStrengthDbm.value = rssi },
            onDisconnected = { handleDisconnected(device) },
        )
        gattManager = manager
        manager.connect(device)
            .retry(3, 200)
            .useAutoConnect(false)
            .await()
    }

    /**
     * [Android-патч] Единая реакция на ЛЮБОЙ разрыв GATT-соединения (см. ConnectionObserver в
     * MeterGattManager) — вызывается из onDeviceDisconnected() колбэка, но решение "это я сам
     * попросил или прибор пропал из эфира" принимается ЗДЕСЬ, а не внутри MeterGattManager,
     * потому что только тут известен флаг userInitiatedDisconnect.
     *
     * [Android-патч] ПЕРЕДЕЛАНО — отказались от штатного механизма Nordic BLE library
     * (useAutoConnect) целиком, см. подробную историю у _unexpectedDisconnects выше. Вместо
     * этого — свой явный цикл: переподключаемся к ЗАПОМНЕННОМУ устройству (lastKnownDevice)
     * прямым connect(useAutoConnect=false) с растущей паузой между попытками
     * (RECONNECT_INITIAL_DELAY_MS → удваивается до RECONNECT_MAX_DELAY_MS), пока не получится
     * или пока пользователь сам не нажмёт «Отключиться» (userInitiatedDisconnect). Полностью
     * предсказуемо и не зависит от скрытой логики библиотеки — именно то, что нужно, раз мы уже
     * знаем адрес счётчика и просто хотим держаться того же устройства.
     */
    @SuppressLint("MissingPermission")
    private fun handleDisconnected(device: BluetoothDevice) {
        if (userInitiatedDisconnect) {
            _connectionState.value = ConnectionState.Idle
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
            // [Android-патч] см. nextReconnectExtraDelayMs выше — забираем и сразу обнуляем,
            // чтобы эта пауза сработала РОВНО один раз (перед первой попыткой ЭТОГО цикла), а не
            // на каждом обычном разрыве связи после.
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
                            // gattManager == null — клиент уже отключили/пересоздали снаружи, цикл больше не нужен
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
     * [Android-патч] Принудительное переподключение "снаружи" — используется, когда протокольный
     * уровень (см. MeterRepositoryImpl.consecutiveEmptyOrFailedReads/forceReconnect() в
     * MeterRepository.kt) обнаруживает, что счётчик считает HDLC-канал разорванным (отвечает
     * кадром DM "Server is already disconnected" или байтами без LLC-заголовка), хотя сам Android
     * ВСЁ ЕЩЁ считает GATT-соединение рабочим — то есть "пакеты не уходят, а пишется что
     * подключено". Обычный onDeviceDisconnected() в этом случае сам по себе никогда не наступит —
     * приходится рвать GATT самим. userInitiatedDisconnect НЕ трогаем (в отличие от disconnect()
     * ниже) — это НЕ пользовательское отключение, а автоматическое исправление зависшей сессии:
     * получившийся onDeviceDisconnected() должен пойти по ветке "неожиданный разрыв" (см.
     * handleDisconnected() выше) — Reconnecting + активное переподключение, честно отражая в UI,
     * что связь сейчас восстанавливается, а не молча оставаясь на мёртвом "Подключено".
     *
     * [Android-патч] Параметр [cold] — см. nextReconnectExtraDelayMs выше: обычное (не cold)
     * переподключение просто рвёт и сразу поднимает GATT заново — этого достаточно, если дело
     * было в разовом сбое. Но по логам выяснилось, что после НЕСКОЛЬКИХ подряд таких попыток
     * без единого успешного чтения быстрый GATT-реконнект сам по себе не помогает (счётчик/пульт
     * продолжают отвечать пустышками) — тогда MeterRepositoryImpl просит cold=true, что добавляет
     * паузу (см. nextReconnectExtraDelayMs) перед следующей попыткой connect(), а не немедленный
     * повтор.
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
        val device = adapter?.getRemoteDevice(found.address)
            ?: throw IllegalStateException("Bluetooth недоступен на этом устройстве")
        connect(device)
        return found
    }

    /**
     * [Android-патч] Подключение к КОНКРЕТНОМУ прибору учёта по его номеру (UC-02).
     *
     * ИСПРАВЛЯЕТ РЕАЛЬНУЮ ОШИБКУ: раньше этот сценарий шёл через connectByNamePrefix() выше —
     * то есть номер ПУ, который ввёл пользователь, В ПОИСКЕ ВООБЩЕ НЕ УЧАСТВОВАЛ (в коде на
     * этом месте так и стоял TODO). Фильтром был только префикс имени "RIM", и приложение
     * подключалось к ПЕРВОМУ откликнувшемуся прибору. Пока рядом один счётчик, это незаметно;
     * как только в радиусе есть второй — подключение превращается в лотерею, и пользователь
     * молча смотрит показания ЧУЖОГО прибора, будучи уверен, что открыл свой. Для работы с
     * силовым оборудованием (тот же размыкатель на экране «Сеть») это недопустимо: команда
     * ушла бы не туда.
     *
     * Теперь из эфира берётся только устройство, чей серийный номер в имени ТОЧНО совпадает с
     * запрошенным (см. MeterDeviceName.matches — сравнение полное, без совпадений по
     * подстроке). Если такого прибора в эфире нет — честная ошибка вместо подключения к
     * «похожему»: лучше не подключиться совсем, чем подключиться не к тому.
     *
     * Таймаут больше, чем у поиска по префиксу (15 с против 10 с): нужное устройство может
     * оказаться не первым в эфире, и мы обязаны дослушать, а не хвататься за ближайшее.
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

    /**
     * [Android-патч] Явное отключение по кнопке «Отключиться» (экран «Настройки») — в отличие от
     * НЕОЖИДАННОГО разрыва (см. handleDisconnected()), тут НЕ запускаем автопереподключение:
     * пользователь сам попросил разорвать связь, чтобы затем выбрать другое устройство на экране
     * «Подключение» (см. ConnectionWatcherViewModel/AppNavHost — переход туда происходит
     * автоматически, когда connectionState станет Idle). Флаг ставим ДО disconnect().enqueue(),
     * потому что сам вызов синхронно ничего не гарантирует — ConnectionObserver.onDeviceDisconnected()
     * сработает чуть позже, асинхронно, и должен успеть увидеть уже выставленный флаг.
     */
    @SuppressLint("MissingPermission")
    fun disconnect() {
        userInitiatedDisconnect = true
        reconnectJob?.cancel() // [Android-патч] см. reconnectJob выше — обрываем свой цикл переподключения, если он был в процессе
        gattManager?.disconnect()?.enqueue()
        gattManager = null
        _connectionState.value = ConnectionState.Idle
        _signalStrengthDbm.value = null
    }

    private companion object {
        // [Android-патч] см. handleDisconnected() выше — параметры своего цикла переподключения.
        // Начинаем быстро (1 с — GATT-реконнект к уже известному устройству обычно занимает доли
        // секунды, см. логи), но при повторных неудачах пауза удваивается вплоть до потолка
        // в 20 с — чтобы не долбить счётчик слишком часто, если он сейчас реально недоступен
        // (вне радиуса/выключен), и не создавать тот же "туго закрученный" цикл переподключений,
        // который раньше повторялся каждые ~12-15 с без единого успеха.
        const val RECONNECT_INITIAL_DELAY_MS = 1_000L
        const val RECONNECT_MAX_DELAY_MS = 20_000L
        // Таймаут одной попытки прямого connect() — если GATT не поднимется за это время,
        // Nordic BLE library сама вызовет onDeviceFailedToConnect()/бросит исключение, и наш цикл
        // перейдёт к следующей попытке с очередной паузой.
        const val RECONNECT_ATTEMPT_TIMEOUT_MS = 10_000L

        /**
         * [Android-патч] см. nextReconnectExtraDelayMs/forceReconnect(cold) выше — пауза перед
         * "холодным" переподключением. Подобрана заметно больше обычного RECONNECT_INITIAL_DELAY_MS
         * (1 с): та пауза рассчитана на GATT, а эта — на то, чтобы дать ПУЛЬТУ время заметить
         * реальную потерю связи с телефоном и (предположительно) заново поднять свою собственную
         * радиосессию со счётчиком, которая живёт независимо от нашего BLE-соединения.
         */
        const val COLD_RECONNECT_COOLDOWN_MS = 5_000L

        /**
         * [Android-патч] см. connectBySerialNumber() — сколько слушаем эфир в поисках прибора с
         * ТОЧНО запрошенным номером. Больше, чем таймаут поиска по префиксу: там годилось первое
         * попавшееся устройство, а здесь нужное может откликнуться не первым, и оборвать поиск
         * раньше времени означало бы сказать «прибор не найден» про прибор, который рядом.
         */
        const val SCAN_BY_SERIAL_TIMEOUT_MS = 15_000L
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
    private val onDisconnected: () -> Unit,
) : BleManager(context) {

    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null

    /**
     * [Android-патч] НАЙДЕНА НАСТОЯЩАЯ ПРИЧИНА того, что после переподключения обмен данными
     * больше не восстанавливался («подключено, а данные не идут»), и заодно — части случайных
     * ошибок «не найден LLC-заголовок сервера» прямо посреди рабочей сессии.
     *
     * Здесь ДОЛГО стоял MutableStateFlow (со своим же TODO «заменить на SharedFlow с буфером»).
     * Для потока сырых байт это оказалось фатально по двум причинам:
     *
     * 1. StateFlow ВСЕГДА отдаёт новому подписчику своё ТЕКУЩЕЕ значение. После каждого
     *    переподключения MeterRepositoryImpl.startTransportPump() подписывается заново — и
     *    мгновенно получал ПОСЛЕДНИЙ notify-кадр ОТ ПРЕДЫДУЩЕЙ, уже оборванной сессии, после чего
     *    честно скармливал эти протухшие байты СВЕЖЕМУ нативному клиенту. Дальше всё ломалось
     *    по цепочке (подтверждено логом с точностью до миллисекунд):
     *      19:10:00.899  GET сформирован
     *      19:10:00.903  GetResponseRaw вернул ответ ЧЕРЕЗ 4 мс — это физически невозможно,
     *                    сам GET ещё даже не ушёл в эфир → «не найден LLC-заголовок»
     *      19:10:00.922  только ТЕПЕРЬ GET реально записан в характеристику FFE9
     *      19:10:01.010  пришёл НАСТОЯЩИЙ ответ — но его уже никто не ждёт, он остаётся в буфере
     *    и достаётся СЛЕДУЮЩЕМУ запросу. То есть весь поток кадров навсегда съезжает на один
     *    кадр назад, и сессия после переподключения не восстанавливается уже никогда — ровно то,
     *    что мы безуспешно лечили паузами, forceReconnect() и «холодным» переподключением.
     *
     * 2. StateFlow КОНФЛЕЙТИТ значения: если подписчик не успевает, промежуточные значения молча
     *    теряются. Ответ на основной буфер приходит 7+ сегментами, а несколько notify нередко
     *    прилетают в одну и ту же миллисекунду (видно в логах) — при конфлейте средние сегменты
     *    просто исчезали, поток байт бился, и парсер падал на «не найден LLC-заголовок» уже без
     *    всякого переподключения.
     *
     * SharedFlow с replay = 0 решает обе проблемы разом: новому подписчику НИЧЕГО не
     * переигрывается (после переподключения читаем только то, что реально пришло ПОСЛЕ него), а
     * буфер на 512 кадров гарантирует, что ни один notify не будет выброшен при всплеске
     * сегментов. Пока «насос» остановлен (момент разрыва/переподключения) подписчиков нет, и
     * входящие кадры просто отбрасываются — это именно то поведение, которое нужно: протухшие
     * байты не должны копиться и попадать в новую сессию.
     */
    val incomingBytes = MutableSharedFlow<ByteArray>(
        replay = 0,
        extraBufferCapacity = 512,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )

    init {
        // [Android-патч] ИСТОРИЯ БАГА ("Соединение: Не подключено" при 100% реально рабочей связи):
        // onStateChanged раньше передавался в конструктор, но НИГДЕ не вызывался — _connectionState
        // навсегда застревал на Connecting(...), выставленном в connect() ДО реального поднятия
        // GATT-соединения. Экран «Настройки» смотрит именно на _connectionState — отсюда ложное
        // "Не подключено", хотя весь остальной обмен данными шёл напрямую через
        // gattManager/incomingBytes, минуя _connectionState. Исправлено подпиской на
        // ConnectionObserver (штатный способ Nordic BLE library реально узнавать о событиях
        // GATT-соединения). Идентификатор в ConnectionState.Connected/Connecting — MAC-адрес
        // устройства (device.address): серийный номер счётчика на этот момент ещё не разобран
        // (см. applyDeviceNameInfo() в MeterRepositoryImpl), а значение
        // ConnectionState.Connected.serialNumber пока нигде не читается (проверено — только
        // "is ConnectionState.Connected" в SettingsScreen), так что это безопасно.
        //
        // [Android-патч] МЕХАНИЗМ ПЕРЕПОДКЛЮЧЕНИЯ: onDeviceDisconnected() сам по себе больше НЕ
        // решает, в какое состояние переходить (Idle это или Reconnecting) — это решение зависит от
        // того, САМ ли пользователь отключился (кнопка «Отключиться») или связь оборвалась
        // неожиданно, а это известно только снаружи, в MeterBleClient.userInitiatedDisconnect.
        // Поэтому здесь просто репортим сам факт разрыва через onDisconnected(), а выбор
        // Idle/Reconnecting + запуск автопереподключения — в MeterBleClient.handleDisconnected().
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
                Log.w("MeterGattManager", "onDeviceDisconnected: reason=$reason")
                onDisconnected()
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
                // [Android-патч] см. incomingBytes выше. copyOf() — потому что Nordic BLE library
                // может переиспользовать тот же самый массив под следующий notify: без копии в
                // поток ушла бы ссылка, содержимое которой к моменту обработки уже подменено.
                // tryEmit (а не emit) — мы внутри BLE-колбэка, а не корутины, suspend тут нельзя;
                // при буфере на 512 кадров false здесь означал бы, что «насос» намертво встал,
                // поэтому это не тихая потеря, а явная ошибка в логе.
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
