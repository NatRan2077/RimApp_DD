package ru.rim.dd.data.repository

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ru.rim.dd.core.ble.BleDevice
import ru.rim.dd.core.ble.MeterBleClient
import ru.rim.dd.core.bridge.SpodesClientBridge
import ru.rim.dd.core.dlms.CLOCK_STATUS_OBIS
import ru.rim.dd.core.dlms.CURRENT_TARIFF_OBIS
import ru.rim.dd.core.dlms.CaptureColumn
import ru.rim.dd.core.dlms.DD_PHASE_DISPLAY_OBIS
import ru.rim.dd.core.dlms.DlmsValue
import ru.rim.dd.core.dlms.ObisCatalog
import ru.rim.dd.core.dlms.ObisReading
import ru.rim.dd.core.dlms.RELAY_CONTROL_STATE_OBIS
import ru.rim.dd.core.dlms.ScalerUnit
import ru.rim.dd.core.dlms.TAMPER_STATUS_OBIS
import ru.rim.dd.core.dlms.captureColumnsOf
import ru.rim.dd.core.dlms.compactReadingsOf
import ru.rim.dd.core.dlms.decodeCosemDate
import ru.rim.dd.core.dlms.decodeCosemTime
import ru.rim.dd.core.dlms.decodeDlmsValue
import ru.rim.dd.core.dlms.describeDlmsValue
import ru.rim.dd.core.dlms.isClockStatusValid
import ru.rim.dd.core.dlms.kiloUnitLabelOf
import ru.rim.dd.core.dlms.longOrBoolValueOf
import ru.rim.dd.core.dlms.parseActionResponseResult
import ru.rim.dd.core.dlms.parseGetResponseBlock
import ru.rim.dd.core.dlms.parseGetResponseValue
import ru.rim.dd.core.dlms.readingsOf
import ru.rim.dd.core.dlms.scalerUnitOf
import ru.rim.dd.core.dlms.textValueOf
import ru.rim.dd.core.dlms.unitLabelOf
import ru.rim.dd.core.model.ConnectionState
import ru.rim.dd.core.model.EnergyDirection
import ru.rim.dd.core.model.LineVoltages
import ru.rim.dd.core.model.MeterDeviceName
import ru.rim.dd.core.model.MeterInfo
import ru.rim.dd.core.model.MeterProtocolProfile
import ru.rim.dd.core.model.NetworkParams
import ru.rim.dd.core.model.PairedDevice
import ru.rim.dd.core.model.PhaseStatus
import ru.rim.dd.core.model.PhaseValues
import ru.rim.dd.core.model.Reading
import ru.rim.dd.core.model.RelaySource
import ru.rim.dd.core.model.RelayState
import ru.rim.dd.core.model.TamperState
import ru.rim.dd.core.model.EnergyCategory
import ru.rim.dd.data.local.DeviceStore
import ru.rim.dd.data.local.JournalLine
import ru.rim.dd.data.local.JournalStore
import ru.rim.dd.data.local.ReadingDao
import ru.rim.dd.ui.components.formatNumber
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Связывает BLE-транспорт ([MeterBleClient]), разбор кадров СПОДЭС ([SpodesClientBridge])
 * и локальное хранение. Все показания приходят из одного объекта — буфера индикации
 * 0.0.21.0.2.255 — и раскладываются по StateFlow'ам для экранов.
 */
@Singleton
class MeterRepositoryImpl @Inject constructor(
    private val ble: MeterBleClient,
    private val spodes: SpodesClientBridge,
    private val readingDao: ReadingDao,
    private val deviceStore: DeviceStore,
    private val journal: JournalStore,
) : MeterRepository {

    private val _relayState = MutableStateFlow(
        RelayState(isOn = false, powerLimitKw = 0.0, source = RelaySource.UNKNOWN)
    )
    private val _tamperState = MutableStateFlow(TamperState.UNKNOWN)
    private val _networkParams = MutableStateFlow<NetworkParams?>(null)
    private val _meterInfo = MutableStateFlow<MeterInfo?>(null)
    private val _readings = MutableStateFlow<List<Reading>>(emptyList())

    private var activeSerialNumber: String? = null

    /**
     * Серийный номер, который прибор сообщил сам (0.0.96.1.0.255 в буфере индикации).
     * Достовернее имени из BLE-рекламы: по нему connectBySerialNumber() сверяет, что
     * подключились к нужному прибору.
     */
    private var meterReportedSerial: String? = null

    /** AKROS определяется по BLE-имени один раз при подключении; для РиМ всегда false. */
    private var isAkros = false

    /**
     * true — AKROS читается запасным путём (SNRM + AARQ «Reader», компактный буфер); false —
     * путь пульта без ассоциации. Выбирается при подключении: сначала путь пульта, при отсутствии
     * ответа — ассоциация (см. [establishSessionAndReadFirst]); после разрыва сеанс
     * пересобирается тем же способом.
     */
    private var akrosAssociation = false

    /**
     * Колонки компактного буфера AKROS (только при [akrosAssociation]): значения идут подряд без
     * OBIS, смысл колонок читается из capture_objects. null — колонка не разобрана и пропускается.
     */
    private var akrosCaptureObis: List<CaptureColumn?> = emptyList()

    /** Единицы измерения колонок компактного буфера AKROS по OBIS (атрибут scaler_unit). */
    private var akrosScalerUnits: Map<String, ScalerUnit> = emptyMap()

    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pumpJob: Job? = null
    private var autoRefreshJob: Job? = null

    /**
     * Нативный SpodesClient не потокобезопасен (счётчики HDLC-кадров, общий буфер), а запросы
     * идут из нескольких источников: автообновление, кнопка «Обновить», команды реле.
     * Поэтому весь протокольный обмен сериализован. Mutex не реентерабелен — лок берётся один
     * раз на верхнеуровневом вызове, вложенные функции его не захватывают.
     */
    private val protocolMutex = Mutex()

    /**
     * true с первого успешного ответа транспорта до явного disconnect(). Нужен, чтобы
     * коллектор connectionState не пересобирал сеанс во время первого подключения, а только
     * после автопереподключения GATT.
     */
    private var protocolSessionEstablished = false

    /**
     * Сообщение последней ошибки нативного клиента, записанное под [protocolMutex].
     * Читать spodes.lastErrorMessage() снаружи лока нельзя: параллельный destroyClient()
     * обнуляет handle, и обращение к нему роняет процесс (SIGSEGV).
     */
    private var lastProtocolError: String? = null

    /**
     * Подряд неудачных чтений буфера (пустой ответ или ошибка разбора). Так выглядит DLMS-сессия,
     * которую счётчик считает закрытой (DM-кадр, ответ без LLC-заголовка), пока Android считает
     * GATT рабочим, — обычный «неожиданный разрыв» в этом случае не наступает. Достигнув
     * [MAX_CONSECUTIVE_PROTOCOL_FAILURES], автообновление принудительно переподключается.
     */
    private var consecutiveEmptyOrFailedReads = 0

    /**
     * Сколько полных циклов forceReconnect() подряд прошло без единого разобранного ответа.
     * Быстрое переподключение GATT часто не помогает: радиосессия пульт↔счётчик
     * пересобирается дольше. После [COLD_RECONNECT_THRESHOLD] циклов просим «холодное»
     * переподключение с паузой.
     */
    private var consecutiveForceReconnectsWithoutData = 0

    /**
     * Данные принадлежат конкретному прибору, поэтому при новом подключении и при отключении
     * всё, что осталось от прошлого, обнуляется. Иначе поля, которых новый прибор не присылает
     * (например HDLC P1 у AKROS), показывались бы со значениями прежнего ПУ: разбор буфера
     * сохраняет прежнее значение для не пришедших полей.
     */
    private fun resetSessionData() {
        _relayState.value = RelayState(isOn = false, powerLimitKw = 0.0, source = RelaySource.UNKNOWN)
        _tamperState.value = TamperState.UNKNOWN
        _networkParams.value = null
        _meterInfo.value = null
        _readings.value = emptyList()
        akrosCaptureObis = emptyList()
        akrosScalerUnits = emptyMap()
        activeSerialNumber = null
        meterReportedSerial = null
    }

    init {
        // GATT переподключает MeterBleClient (свой цикл); здесь только протокольная часть:
        // при обрыве останавливаем обмен, при восстановлении пересобираем DLMS-сеанс.
        repositoryScope.launch {
            ble.unexpectedDisconnects.collect {
                Log.w(TAG, "Обнаружен неожиданный разрыв связи — останавливаем протокольный обмен, ждём автопереподключения BLE")
                stopTransportPump()
            }
        }
        repositoryScope.launch {
            ble.connectionState.collect { state ->
                if (state is ConnectionState.Connected && pumpJob?.isActive != true && protocolSessionEstablished) {
                    Log.d(TAG, "BLE-соединение восстановлено (автопереподключение) — пересобираем протокольный сеанс")
                    reestablishProtocolSession()
                }
            }
        }
    }

    override fun connectionState(): Flow<ConnectionState> = ble.connectionState

    override fun signalStrengthDbm(): Flow<Int?> = ble.signalStrengthDbm

    override fun scanDevices(): Flow<BleDevice> = ble.scan()

    override suspend fun connectByAddress(address: String, pin: String, remember: Boolean, deviceName: String?) {
        // Всё, что осталось от прошлого прибора (номер, показания, инфо), к новому не относится.
        resetSessionData()
        withContext(Dispatchers.IO) { ble.connectByAddress(address, deviceName) }
        openProtocolSession(deviceName)

        // Сбрасываем до разбора имени: иначе при неудачном разборе остался бы номер прошлого прибора.
        // meterReportedSerial не трогаем — первое чтение буфера уже могло его получить.
        activeSerialNumber = null
        applyDeviceNameInfo(deviceName)
        if (remember) {
            deviceStore.rememberDevice(
                PairedDevice(
                    serialNumber = meterReportedSerial ?: activeSerialNumber ?: address,
                    bleAddress = address,
                ),
            )
        }
        updateJournal()
        startAutoRefresh()
    }

    /**
     * Ищет в эфире прибор с ТОЧНО таким серийным номером и после подключения сверяет его с
     * номером, который прибор сообщил сам, — имя в рекламе прибор называет про себя, ему одному
     * доверять нельзя. Несовпадение — разрыв связи: иначе пользователь увидел бы показания
     * (и мог бы управлять реле) чужого прибора. Если прибор номер не сообщает, остаётся только
     * фильтр поиска; это пишется в лог.
     */
    override suspend fun connectBySerialNumber(serialNumber: String, pin: String, remember: Boolean) {
        resetSessionData()
        val found = withContext(Dispatchers.IO) { ble.connectBySerialNumber(serialNumber) }
        openProtocolSession(found.name)

        val reported = meterReportedSerial
        if (reported != null && !MeterDeviceName.serialsEqual(reported, serialNumber)) {
            Log.e(
                TAG,
                "ПОДКЛЮЧИЛИСЬ НЕ К ТОМУ ПРИБОРУ: запрошен № $serialNumber, а прибор " +
                        "(\"${found.name}\", ${found.address}) сообщил о себе № $reported — разрываем связь",
            )
            abortSession(
                "Подключение отменено: найденный прибор сообщил номер $reported вместо запрошенного " +
                        "$serialNumber. Проверьте номер ПУ"
            )
        }
        if (reported == null) {
            Log.w(
                TAG,
                "Прибор не сообщил свой серийный номер ($SERIAL_NUMBER_OBIS) — сверить подключение " +
                        "по протоколу не с чем, полагаемся только на совпадение номера в имени устройства " +
                        "(\"${found.name}\")",
            )
        }

        // Реальное имя из эфира приоритетнее введённого номера; если оно нестандартное — берём введённый.
        applyDeviceNameInfo(found.name)
        if (activeSerialNumber == null) activeSerialNumber = serialNumber
        if (remember) {
            deviceStore.rememberDevice(
                PairedDevice(
                    serialNumber = reported ?: activeSerialNumber ?: serialNumber,
                    bleAddress = found.address,
                ),
            )
        }
        updateJournal()
        startAutoRefresh()
    }

    /**
     * Общая часть подключения: нативный клиент, сеанс и первое чтение буфера. Основной путь — как
     * у штатного пульта: без SNRM и ассоциации сразу I-кадры с GET «плоской» адресацией; нумерацию
     * N(S)/N(R) ведёт нативный клиент. Для AKROS есть запасной путь, см. [establishSessionAndReadFirst].
     */
    private suspend fun openProtocolSession(deviceName: String?) {
        isAkros = MeterProtocolProfile.forDeviceName(deviceName) == MeterProtocolProfile.AKROS_STANDARD
        akrosAssociation = false
        Log.i(TAG, "Обмен с \"$deviceName\": ${if (isAkros) "AKROS" else "РиМ"}")
        // Карточка журнала и лог байтов заводятся до первого обмена; при неудаче удаляются.
        withContext(Dispatchers.IO) { journal.begin() }
        try {
            spodes.createClient()
            startTransportPump()
            if (!establishSessionAndReadFirst()) {
                stopTransportPump()
                ble.disconnect()
                throw IllegalStateException(lastProtocolError ?: "неизвестная ошибка")
            }
        } catch (ex: Exception) {
            withContext(NonCancellable + Dispatchers.IO) { journal.discard() }
            throw ex
        }
    }

    /**
     * Сеанс и первое чтение буфера (клиент создан, «насос» запущен). РиМ и AKROS читаются путём
     * пульта. Если AKROS на него не ответил (пустой ответ или нечитаемый буфер), клиент
     * пересоздаётся и AKROS читается через ассоциацию — этот путь проверен на реальном приборе.
     * Выбранный путь запоминается в [akrosAssociation], после обрыва связи используется он же.
     * Бросает исключение (через [abortSession]), если ассоциация не установилась.
     */
    private suspend fun establishSessionAndReadFirst(): Boolean {
        if (isAkros && akrosAssociation) {
            withContext(Dispatchers.IO) { establishAkrosSession() }
            return readMainBuffer()
        }
        consecutiveEmptyOrFailedReads = 0
        val ok = readMainBuffer()
        if (!isAkros || (ok && consecutiveEmptyOrFailedReads == 0)) return ok

        Log.w(TAG, "AKROS не ответил на запрос без ассоциации ($lastProtocolError) — пробуем ассоциацию (SNRM + AARQ)")
        // Насос останавливаем до destroyClient(): иначе входящий пакет попал бы в уничтоженный клиент.
        // Пока он остановлен, запоздавшие ответы на прежние кадры отбрасываются.
        stopTransportPump()
        delay(AKROS_FALLBACK_SETTLE_DELAY_MS)
        protocolMutex.withLock {
            withContext(Dispatchers.IO) {
                spodes.destroyClient()
                spodes.createClient()
            }
        }
        startTransportPump()
        withContext(Dispatchers.IO) { establishAkrosSession() }
        akrosAssociation = true
        consecutiveEmptyOrFailedReads = 0
        val fallbackOk = readMainBuffer()
        if (!fallbackOk || consecutiveEmptyOrFailedReads > 0) akrosAssociation = false
        return fallbackOk
    }

    /** Закрывает транспорт и бросает исключение — для сбоев при установлении сеанса. */
    private fun abortSession(message: String): Nothing {
        stopTransportPump()
        ble.disconnect()
        journal.discard()
        throw IllegalStateException(message)
    }

    private suspend fun readMainBuffer(): Boolean =
        protocolMutex.withLock { withContext(Dispatchers.IO) { readAndApplyMainBuffer() } }

    /**
     * Модель и серийный номер по DLMS без ассоциации не прочитать, но BLE-имя РиМ их содержит
     * («RIM1894604314652»: 18946 → модель 189.46, 04314652 → серийный номер).
     * Разбор живёт в [MeterDeviceName] — им же пользуется поиск прибора по номеру.
     */
    private fun applyDeviceNameInfo(name: String?) {
        val parsed = MeterDeviceName.parse(name)
        if (parsed == null) {
            Log.w(TAG, "Модель/серийный номер: не удалось разобрать имя устройства \"$name\" (ожидалось 13 цифр после префикса \"RIM\")")
            return
        }
        Log.d(TAG, "Модель/серийный номер разобраны из имени устройства \"$name\": модель=${parsed.model}, серийный=${parsed.serialNumber}")
        activeSerialNumber = parsed.serialNumber
        _meterInfo.value = (_meterInfo.value ?: blankMeterInfo())
            .copy(model = parsed.model, serialNumber = parsed.serialNumber)
    }

    private fun blankMeterInfo() =
        MeterInfo(model = "—", serialNumber = activeSerialNumber ?: "—", firmwareVersion = "—")

    /**
     * GET буфера индикации 0.0.21.0.2.255 (класс 7, атрибут 2) и разбор ответа в StateFlow'ы.
     * Общий код для первого чтения, автообновления и кнопки «Обновить».
     *
     * Основной путь — механизм штатного пульта (прошивка РиМ 040.40), одинаковый для РиМ и AKROS:
     * «плоская» адресация (dest 0x03 / src 0x43), без SNRM и ассоциации; HDLC-сегменты ответа
     * дочитывает нативный клиент (RR-кадры), DLMS-блоки — [assembleDlmsValue]; склеенное значение
     * разбирается [readingsOf]. Запасной путь AKROS ([akrosAssociation]) — стандартная адресация
     * и компактный буфер.
     *
     * Возвращает false, только если запрос не удалось отправить. Пустой ответ и ошибка разбора
     * считаются неудачей в [consecutiveEmptyOrFailedReads], но связь не рвут.
     */
    private fun readAndApplyMainBuffer(): Boolean {
        val sent = if (akrosAssociation) {
            Log.d(TAG, "GET буфера AKROS (стандартная адресация, после ассоциации)")
            spodes.getRequest(MAIN_BUFFER_CLASS_ID, MAIN_BUFFER_OBIS, attributeId = 2)
        } else {
            Log.d(TAG, "GET буфера индикации (плоская адресация, как у пульта)")
            spodes.getRequestFlatAddress(
                classId = MAIN_BUFFER_CLASS_ID,
                obisCode = MAIN_BUFFER_OBIS,
                attributeId = 2,
                destAddress = FLAT_DEST_ADDRESS,
                srcAddress = FLAT_SRC_ADDRESS,
            )
        }
        if (!sent) {
            lastProtocolError = spodes.lastErrorMessage()
            consecutiveEmptyOrFailedReads++
            return false
        }

        val raw = spodes.getResponseRawBytes()
        if (raw.isEmpty()) {
            lastProtocolError = spodes.lastErrorMessage()
            consecutiveEmptyOrFailedReads++
            Log.w(TAG, "GET-response: пустой ответ от транспорта ($lastProtocolError, подряд неудач: $consecutiveEmptyOrFailedReads)")
            return true
        }
        // Именно ответ транспорта — надёжный признак, что сеанс реально поднялся.
        protocolSessionEstablished = true
        try {
            // Блоки дочитываем сразу, пока канал не занят «хвостом» этого ответа, иначе
            // следующий запрос разъедется с ним.
            val buffer = assembleDlmsValue(raw, "буфер индикации", flat = !akrosAssociation)
            if (buffer == null) {
                consecutiveEmptyOrFailedReads++
                Log.w(TAG, "GET-response: буфер не получен (подряд неудач: $consecutiveEmptyOrFailedReads)")
                return true
            }
            val all = if (akrosAssociation) {
                if (akrosCaptureObis.isEmpty()) {
                    // Без описания колонок значения не с чем сопоставить; этот цикл потерян,
                    // следующее автообновление разберёт буфер полностью.
                    Log.w(TAG, "AKROS: описания буфера ещё нет — запрашиваем capture_objects повторно")
                    readAkrosCaptureObjects()
                }
                compactReadingsOf(buffer, akrosCaptureObis, akrosScalerUnits)
            } else {
                readingsOf(buffer)
            }
            Log.d(TAG, "GET-response: разобрано ${all.size} элементов буфера: $all")
            applyDecodedBuffer(all)
            consecutiveEmptyOrFailedReads = 0
            consecutiveForceReconnectsWithoutData = 0
        } catch (ex: Exception) {
            consecutiveEmptyOrFailedReads++
            Log.e(TAG, "GET-response: не удалось разобрать DLMS-данные: ${ex.message} (подряд неудач: $consecutiveEmptyOrFailedReads)", ex)
        }
        return true
    }

    // Не вызывается: без ассоциации прибор отдаёт только буфер 0.0.21.0.2.255, состояние реле
    // приходит внутри него (RELAY_CONTROL_STATE_OBIS). Оставлено на случай появления ассоциации.
    private fun readRelayState() {
        val outputState = readDisconnectControlAttribute(attributeId = 2, label = "output_state")
        val controlState = readDisconnectControlAttribute(attributeId = 3, label = "control_state")

        if (outputState == null && controlState == null) return

        val prev = _relayState.value
        val isOn = when {
            controlState != null -> controlState == 1L // 0=disconnected, 1=connected, 2=ready_for_reconnection
            outputState != null -> outputState == 1L
            else -> prev.isOn
        }
        val remoteTurnOnAllowed = controlState == 2L
        _relayState.value = prev.copy(isOn = isOn, remoteTurnOnAllowed = remoteTurnOnAllowed)
        Log.d(
            TAG,
            "Состояние размыкателя: output_state=$outputState, control_state=$controlState → " +
                    "isOn=$isOn, remoteTurnOnAllowed=$remoteTurnOnAllowed",
        )
    }

    private fun readDisconnectControlAttribute(attributeId: Int, label: String): Long? {
        Log.d(TAG, "GET-request размыкателя ($DISCONNECT_CONTROL_OBIS, class=$DISCONNECT_CONTROL_CLASS_ID Disconnect Control, attr=$attributeId \"$label\")")
        val sent = spodes.getRequestFlatAddress(
            classId = DISCONNECT_CONTROL_CLASS_ID,
            obisCode = DISCONNECT_CONTROL_OBIS,
            attributeId = attributeId,
            destAddress = FLAT_DEST_ADDRESS,
            srcAddress = FLAT_SRC_ADDRESS,
        )
        if (!sent) {
            Log.w(TAG, "Размыкатель ($label, attr=$attributeId): запрос не отправлен (${spodes.lastErrorMessage()})")
            return null
        }
        val raw = spodes.getResponseRawBytes()
        if (raw.isEmpty()) {
            Log.w(TAG, "Размыкатель ($label, attr=$attributeId): пустой ответ от транспорта (${spodes.lastErrorMessage()})")
            return null
        }
        return try {
            val value = parseGetResponseValue(raw)
            Log.d(TAG, "Размыкатель ($label, attr=$attributeId): УСПЕХ — ${describeDlmsValue(value)}")
            longOrBoolValueOf(value)
        } catch (ex: Exception) {
            Log.w(TAG, "Размыкатель ($label, attr=$attributeId): ${ex.message}")
            null
        }
    }

    // Не вызывается: версия ПО 0.0.96.1.2.255 без ассоциации отдаёт data-access-result 0x0B
    // («object unavailable») — код верный, но значение доступно только внутри буфера индикации.
    // Оставлено на случай появления ассоциации.
    private fun readFirmwareVersion() {
        Log.d(TAG, "GET-request версии ПО (0.0.96.1.2.255, class=1, attr=2)")
        val sent = spodes.getRequestFlatAddress(
            classId = 1,
            obisCode = "0.0.96.1.2.255",
            attributeId = 2,
            destAddress = FLAT_DEST_ADDRESS,
            srcAddress = FLAT_SRC_ADDRESS,
        )
        if (!sent) {
            Log.w(TAG, "Версия ПО: запрос не отправлен (${spodes.lastErrorMessage()})")
            return
        }
        val raw = spodes.getResponseRawBytes()
        if (raw.isEmpty()) {
            Log.w(TAG, "Версия ПО: пустой ответ от транспорта (${spodes.lastErrorMessage()})")
            return
        }
        try {
            val value = parseGetResponseValue(raw)
            val text = textValueOf(value)
            Log.d(TAG, "Версия ПО: разобрано как \"$text\" (сырое значение: ${describeDlmsValue(value)})")
            _meterInfo.value = (_meterInfo.value ?: blankMeterInfo()).copy(firmwareVersion = text)
        } catch (ex: Exception) {
            Log.w(
                TAG,
                "Версия ПО: не удалось разобрать ответ (${ex.message}) — сырые байты (${raw.size}): " +
                        raw.joinToString(" ") { "%02X".format(it) },
            )
        }
    }

    /**
     * Раскладывает буфер индикации по StateFlow'ам: состояние реле и пломб, энергия по тарифам,
     * мгновенные параметры сети, сведения о приборе. Показания за предыдущий период (F=101)
     * экран «Показания» показывает отдельной группой «РДЧ».
     */
    private fun applyDecodedBuffer(all: List<ObisReading>) {
        applyRelayState(all)
        applyTamperState(all)
        applyEnergyReadings(all)
        applyNetworkParams(all)
        applyMeterInfo(all)
        updateJournal()
    }

    /**
     * Переносит последние разобранные данные в карточку журнала: тип и номер ПУ, энергию по
     * тарифам, напряжение и ток. Вызывается после каждого разобранного буфера.
     */
    private fun updateJournal() {
        val info = _meterInfo.value
        val net = _networkParams.value
        journal.update(
            meterType = info?.model?.replace(Regex("^(РиМ|RIM|Rim)\\s*", RegexOption.IGNORE_CASE), "")?.trim(),
            serialNumber = meterReportedSerial ?: info?.serialNumber,
            energy = journalEnergyLines(_readings.value),
            voltage = net?.let { journalPhases(it.voltage, 1, "В") },
            current = net?.let { journalPhases(it.current, 3, "А") },
        )
    }

    private fun journalEnergyLines(readings: List<Reading>): List<JournalLine> {
        fun head(c: EnergyCategory) = when (c) {
            EnergyCategory.ACTIVE_IMPORT -> "A+"
            EnergyCategory.ACTIVE_EXPORT -> "A−"
            EnergyCategory.REACTIVE_Q1 -> "R+"
            EnergyCategory.REACTIVE_Q4 -> "R−"
            EnergyCategory.OTHER -> "Прочее"
        }
        return readings
            .filter { !it.isRdch && it.category != EnergyCategory.OTHER }
            .sortedWith(compareBy({ it.category.ordinal }, { it.tariff ?: 0 }))
            .map {
                val tariff = if (it.tariff == null) "сумма" else "Т${it.tariff}"
                val unit = it.unitLabel?.let { u -> " $u" } ?: ""
                JournalLine("${head(it.category)} · $tariff", formatNumber(it.valueKwh, 3) + unit)
            }
    }

    private fun journalPhases(v: PhaseValues, decimals: Int, unit: String): String =
        if (v.isThreePhase) {
            "L1 ${formatNumber(v.l1 ?: 0.0, decimals)} · L2 ${formatNumber(v.l2 ?: 0.0, decimals)} · " +
                    "L3 ${formatNumber(v.l3 ?: 0.0, decimals)} $unit"
        } else {
            "${formatNumber(v.total, decimals)} $unit"
        }

    /** Поле буфера по OBIS-коду; при дубликатах берётся первое. */
    private fun List<ObisReading>.byObis(obis: String) = firstOrNull { it.obisCode == obis }

    /**
     * Текст поля буфера. РиМ отдаёт текст как VisibleString, AKROS — как OCTET STRING
     * («1.02» приходит байтами [49,46,48,50]). Октеты принимаются только если все байты
     * печатные: иначе это, например, дата-время, и в «Версию ПО» его подставлять нельзя.
     */
    private fun ObisReading?.asText(): String? {
        val reading = this ?: return null
        reading.textValue?.let { return it }
        val bytes = reading.octetValue ?: return null
        if (bytes.isEmpty() || bytes.any { (it.toInt() and 0xFF) !in 0x20..0x7E }) return null
        return String(bytes, Charsets.US_ASCII).trim().takeIf { it.isNotEmpty() }
    }

    /**
     * control_state размыкателя приходит позиционным полем этого же буфера: прямое чтение
     * Disconnect Control без ассоциации отвечает 0x0D. Значения: 0=disconnected, 1=connected,
     * 2=ready_for_reconnection.
     */
    private fun applyRelayState(all: List<ObisReading>) {
        val controlState = all.byObis(RELAY_CONTROL_STATE_OBIS)?.rawValue ?: return
        _relayState.value = _relayState.value.copy(
            isOn = controlState == 1L,
            remoteTurnOnAllowed = controlState == 2L,
            source = RelaySource.METER_READ,
        )
    }

    /** Пломбы и датчики — позиционное поле «Значки ДД» (раскладка бит — в [TamperState]). */
    private fun applyTamperState(all: List<ObisReading>) {
        val status = all.byObis(TAMPER_STATUS_OBIS)?.rawValue ?: return
        _tamperState.value = TamperState.decode(status)
    }

    private fun applyEnergyReadings(all: List<ObisReading>) {
        val energy = all.filter { r ->
            val g = r.obisCode.split(".")
            // C=1..4 — активная/реактивная энергия; D=8 — накопленная (F=255 текущий период,
            // F=101 расчётный день), D=17 — старые коды РДЧ прежних версий ПО прибора.
            g.size == 6 && (g[2].toIntOrNull() in 1..4) &&
                    ((g[3] == "8" && (g[5] == "255" || g[5] == "101")) || g[3] == "17")
        }
        if (energy.isEmpty()) return
        _readings.value = energy.map {
            Reading(
                obisId = it.obisCode,
                tariff = it.tariff,
                valueKwh = it.valueKwh,
                timestamp = Instant.now(),
                // valueKwh получен делением на 1000, поэтому единицу прибора (Вт·ч) переводим в «кило».
                unitLabel = kiloUnitLabelOf(it.unit),
                isRdch = it.obisCode.split(".").let { g -> g.getOrNull(5) == "101" || g.getOrNull(3) == "17" },
            )
        }
    }

    /**
     * Однофазные приборы (189.xx, AKROS) отдают напряжение и ток под суммарными OBIS
     * (1.0.12.7 / 1.0.11.7), трёхфазный 489 — только пофазными (1.0.32.7 / 52.7 / 72.7).
     * Берём и те и другие: total у трёхфазного — L1, чтобы поле не пустовало.
     */
    private fun applyNetworkParams(all: List<ObisReading>) {
        fun phase(total: String, l1: String, l2: String, l3: String, divide: Double = 1.0): PhaseValues? {
            val t = all.byObis(total)?.value
            val a = all.byObis(l1)?.value
            val b = all.byObis(l2)?.value
            val c = all.byObis(l3)?.value
            if (t == null && a == null && b == null && c == null) return null
            return PhaseValues(
                total = (t ?: a ?: 0.0) / divide,
                l1 = a?.div(divide),
                l2 = b?.div(divide),
                l3 = c?.div(divide),
            )
        }

        val voltage = phase("1.0.12.7.0.255", "1.0.32.7.0.255", "1.0.52.7.0.255", "1.0.72.7.0.255")
        val current = phase("1.0.11.7.0.255", "1.0.31.7.0.255", "1.0.51.7.0.255", "1.0.71.7.0.255")
        // Мощности прибор отдаёт в Вт/вар/ВА, на экране — кВт/квар/кВА.
        val power = phase("1.0.1.7.0.255", "1.0.21.7.0.255", "1.0.41.7.0.255", "1.0.61.7.0.255", divide = 1000.0)
        val reactivePower = phase("1.0.3.7.0.255", "1.0.23.7.0.255", "1.0.43.7.0.255", "1.0.63.7.0.255", divide = 1000.0)
        val apparentPower = phase("1.0.9.7.0.255", "1.0.29.7.0.255", "1.0.49.7.0.255", "1.0.69.7.0.255", divide = 1000.0)
        val cosPhi = phase("1.0.13.7.0.255", "1.0.33.7.0.255", "1.0.53.7.0.255", "1.0.73.7.0.255")

        // Линейные напряжения: 1.0.124/125/126.7, у прежних версий ПО — 1.0.12.7.1/2/3.
        val newL12 = all.byObis("1.0.124.7.0.255")?.value
        val newL23 = all.byObis("1.0.125.7.0.255")?.value
        val newL13 = all.byObis("1.0.126.7.0.255")?.value
        val oldL12 = all.byObis("1.0.12.7.1.255")?.value
        val oldL23 = all.byObis("1.0.12.7.2.255")?.value
        val oldL13 = all.byObis("1.0.12.7.3.255")?.value
        val lineVoltages = when {
            newL12 != null || newL23 != null || newL13 != null -> LineVoltages(newL12, newL23, newL13)
            oldL12 != null || oldL23 != null || oldL13 != null -> LineVoltages(oldL12, oldL23, oldL13, legacyCodes = true)
            else -> null
        }

        if (voltage == null && current == null && power == null) return
        _networkParams.value = NetworkParams(
            voltage = voltage ?: PhaseValues(total = 0.0),
            current = current ?: PhaseValues(total = 0.0),
            power = power ?: PhaseValues(total = 0.0),
            frequencyHz = all.byObis("1.0.14.7.0.255")?.value,
            reactivePowerKvar = reactivePower?.total,
            apparentPowerKva = apparentPower?.total,
            neutralCurrentA = all.byObis("1.0.91.7.0.255")?.value,
            cosPhi = cosPhi,
            lineVoltages = lineVoltages,
        )
    }

    /** Разобранный date-time (12 байт) объекта часов 0.0.1.0.0.255; любая часть может быть null. */
    private class ClockFields(val date: LocalDate?, val time: LocalTime?, val status: Int?)

    /**
     * date-time объекта часов 0.0.1.0.0.255 (12 байт): год(2), месяц, день, день недели, часы,
     * минуты, секунды, сотые, отклонение(2), статус. Год 0xFFFF — «не задан» (как в прошивке
     * пульта) — такие часы пропускаются; статус 0xFF — «не указан». Дату читает [decodeCosemDate]
     * (байты 0–4), время — [decodeCosemTime] (байты 5–8).
     */
    private fun clockFieldsOf(bytes: ByteArray?): ClockFields? {
        if (bytes == null || bytes.size != 12) return null
        if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFF.toByte()) return null
        val status = (bytes[11].toInt() and 0xFF).takeIf { it != 0xFF }
        return ClockFields(
            date = decodeCosemDate(bytes.copyOfRange(0, 5)),
            time = decodeCosemTime(bytes.copyOfRange(5, 9)),
            status = status,
        )
    }

    /**
     * Сведения о приборе. Поля, не пришедшие в этом цикле, сохраняют прежнее значение;
     * OBIS-подпись хранится вместе со значением, чтобы не отвязаться от источника.
     */
    private fun applyMeterInfo(all: List<ObisReading>) {
        val temperatureC = all.byObis("0.0.96.9.0.255")?.value
        val backupVoltageV = all.byObis("0.0.96.6.3.255")?.value

        // Дата и время прибора. Одни приборы присылают их отдельными объектами (0.0.0.9.2.255 дата,
        // 0.0.0.9.1.255 время — иногда только один из двух), современные — одним объектом часов
        // 0.0.1.0.0.255. Каждая часть берётся независимо: отдельный объект приоритетнее (как в
        // прошивке пульта), недостающая часть добирается из часов 0.0.1.0.0.255. OBIS-источник
        // запоминается для подписи на экране.
        val separateDate = all.byObis(DATE_OBIS)?.octetValue?.let { decodeCosemDate(it) }
        val separateTime = all.byObis(TIME_OBIS)?.octetValue?.let { decodeCosemTime(it) }
        val clock = clockFieldsOf(all.firstOrNull { it.obisCode == CLOCK_STATUS_OBIS && it.octetValue != null }?.octetValue)
        val deviceDate = separateDate ?: clock?.date
        val deviceDateObis = when {
            separateDate != null -> DATE_OBIS
            clock?.date != null -> CLOCK_STATUS_OBIS
            else -> null
        }
        val deviceTime = separateTime ?: clock?.time
        val deviceTimeObis = when {
            separateTime != null -> TIME_OBIS
            clock?.time != null -> CLOCK_STATUS_OBIS
            else -> null
        }

        // Состав буфера зависит от модели; текстовые поля логируем, чтобы версию ПО под
        // незнакомым OBIS было видно сразу.
        val textReadings = all.filter { it.textValue != null }
        if (textReadings.isNotEmpty()) {
            Log.d(TAG, "В буфере найдены текстовые поля: ${textReadings.joinToString { "${it.obisCode}=\"${it.textValue}\"" }}")
        }
        val firmwareEntry = FIRMWARE_VERSION_OBIS_CANDIDATES
            .firstNotNullOfOrNull { obis -> all.byObis(obis).asText()?.let { obis to it } }
        val firmwareVersion = firmwareEntry?.second
        // Имя модели из буфера надёжнее разбора BLE-имени, которое остаётся запасным вариантом.
        val modelEntry = MODEL_NAME_OBIS_CANDIDATES
            .firstNotNullOfOrNull { obis -> all.byObis(obis).asText()?.let { obis to it } }
        val modelName = modelEntry?.second

        val serialFromBuffer = all.byObis(SERIAL_NUMBER_OBIS).asText()?.trim()?.takeIf { it.isNotEmpty() }
        if (serialFromBuffer != null && serialFromBuffer != meterReportedSerial) {
            Log.d(TAG, "Прибор сообщил свой серийный номер ($SERIAL_NUMBER_OBIS): \"$serialFromBuffer\"")
        }
        if (serialFromBuffer != null) meterReportedSerial = serialFromBuffer

        val currentTariff = all.byObis(CURRENT_TARIFF_OBIS)?.rawValue?.toInt()
        // Тот же OBIS у AKROS несёт ещё и сами часы (octetValue != null) — статус только у позиционного поля.
        // Достоверность часов: статус часов (позиционное поле буфера) либо, если его нет, байт статуса
        // из date-time объекта 0.0.1.0.0.255.
        val clockValid = (all.firstOrNull { it.obisCode == CLOCK_STATUS_OBIS && it.octetValue == null }?.rawValue
            ?: clock?.status?.toLong())?.let { isClockStatusValid(it) }

        // Раскладка «как в прошивке пульта»: направление энергии — байт 3 поля «Значки ДД»,
        // статус фаз — отдельное позиционное поле.
        val energyDirection = all.byObis(TAMPER_STATUS_OBIS)?.rawValue?.let { EnergyDirection.decode(it) }
        val phaseStatus = all.byObis(DD_PHASE_DISPLAY_OBIS)?.rawValue?.let { PhaseStatus.decode(it) }
        if (phaseStatus != null) {
            Log.d(TAG, "Буфер: статус фаз ($DD_PHASE_DISPLAY_OBIS) = $phaseStatus, направление энергии = $energyDirection")
        }

        val rdchDate = all.byObis("0.0.0.9.2.101")?.octetValue?.let { decodeCosemDate(it) }
        val rdchTime = all.byObis("0.0.0.9.1.101")?.octetValue?.let { decodeCosemTime(it) }
        val rdchDateTime = rdchDate?.let { LocalDateTime.of(it, rdchTime ?: LocalTime.MIDNIGHT) }

        val hdlcP1 = all.byObis(HDLC_P1_OBIS)?.let { describeHdlcSetup(it) }
        val hdlcP2 = all.byObis(HDLC_P2_OBIS)?.let { describeHdlcSetup(it) }
        val hdlcP3 = all.byObis(HDLC_P3_OBIS)?.let { describeHdlcSetup(it) }
        val hdlcP4 = all.byObis(HDLC_P4_OBIS)?.let { describeHdlcSetup(it) }
        val operatingTimeReading = all.byObis(OPERATING_TIME_OBIS)
        val operatingTime = operatingTimeReading?.value
        val operatingTimeUnit = unitLabelOf(operatingTimeReading?.unit)

        if (temperatureC == null && backupVoltageV == null && deviceDate == null && deviceTime == null && firmwareVersion == null &&
            modelName == null && currentTariff == null && clockValid == null && serialFromBuffer == null &&
            hdlcP1 == null && hdlcP2 == null && hdlcP3 == null && hdlcP4 == null && operatingTime == null &&
            rdchDateTime == null && phaseStatus == null && energyDirection == null
        ) return

        val prev = _meterInfo.value
        _meterInfo.value = (prev ?: blankMeterInfo()).copy(
            model = modelName ?: prev?.model ?: "—",
            // Номер от самого прибора важнее разобранного из BLE-имени.
            serialNumber = serialFromBuffer ?: activeSerialNumber ?: prev?.serialNumber ?: "—",
            temperatureC = temperatureC,
            backupVoltageV = backupVoltageV,
            deviceDate = deviceDate,
            deviceDateObis = deviceDateObis,
            deviceTime = deviceTime,
            deviceTimeObis = deviceTimeObis,
            lastSeenAt = Instant.now(),
            firmwareVersion = firmwareVersion ?: prev?.firmwareVersion ?: "—",
            currentTariff = currentTariff ?: prev?.currentTariff,
            clockValid = clockValid ?: prev?.clockValid,
            modelObis = modelEntry?.first ?: prev?.modelObis,
            firmwareVersionObis = firmwareEntry?.first ?: prev?.firmwareVersionObis,
            serialNumberObis = if (serialFromBuffer != null) SERIAL_NUMBER_OBIS else prev?.serialNumberObis,
            hdlcP1 = hdlcP1 ?: prev?.hdlcP1,
            hdlcP2 = hdlcP2 ?: prev?.hdlcP2,
            hdlcP3 = hdlcP3 ?: prev?.hdlcP3,
            hdlcP4 = hdlcP4 ?: prev?.hdlcP4,
            operatingTime = operatingTime ?: prev?.operatingTime,
            operatingTimeUnit = operatingTimeUnit ?: prev?.operatingTimeUnit,
            rdchDateTime = rdchDateTime ?: prev?.rdchDateTime,
            phaseStatus = phaseStatus ?: prev?.phaseStatus,
            energyDirection = energyDirection ?: prev?.energyDirection,
        )
    }

    /**
     * Текст для объекта HDLC-настройки (класс 23): скорость (enum атрибута 2) и адрес устройства
     * (атрибут 8). Формат значения в буфере у разных приборов может отличаться, поэтому разбор
     * терпимый: Structure → enum как скорость, число как адрес; OctetString → hex; иначе число.
     * Сырое значение пишется в лог, чтобы можно было уточнить раскладку по реальному ответу.
     */
    private fun describeHdlcSetup(r: ObisReading): String {
        val items = r.structItems
        val text = when {
            // Раскладка буфера: [speed, window_tx, window_rx, max_info_tx, max_info_rx,
            // inter_octet_timeout, inactivity_timeout, device_address].
            items != null && items.size >= 8 && items.first() is DlmsValue.EnumVal -> {
                val speed = hdlcSpeedLabel((items.first() as DlmsValue.EnumVal).value)
                val address = when (val last = items.last()) {
                    is DlmsValue.U16 -> last.value.toLong()
                    is DlmsValue.U32 -> last.value
                    is DlmsValue.U8 -> last.value.toLong()
                    else -> null
                }
                if (address != null) "$speed, адрес $address" else speed
            }
            items != null -> items.joinToString(", ") { describeDlmsValue(it) }
            r.textValue != null -> r.textValue
            r.octetValue != null -> r.octetValue.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
            else -> r.rawValue.toString()
        }
        Log.d(TAG, "HDLC ${r.obisCode}: атрибуты=${items?.joinToString(prefix = "[", postfix = "]") { describeDlmsValue(it) } ?: "raw=${r.rawValue}"} → \"$text\"")
        return text
    }

    /** Значение enum comm_speed из IEC HDLC setup (IEC 62056-6-2, класс 23) в бодах. */
    private fun hdlcSpeedLabel(code: Int): String {
        val baud = when (code) {
            0 -> 300
            1 -> 600
            2 -> 1200
            3 -> 2400
            4 -> 4800
            5 -> 9600
            6 -> 19200
            7 -> 38400
            8 -> 57600
            9 -> 115200
            else -> return "скорость код $code"
        }
        return "$baud бод"
    }

    /**
     * Полноценный сеанс AKROS (порядок сверен с эталонным логом штатной утилиты):
     * SNRM (клиент 32, сервер 1:16) → ожидание UA, в котором согласуются max-info 128/128 →
     * AARQ с низким уровнем безопасности (LLS, пароль «Reader») → чтение описания буфера.
     * Для AKROS ассоциация обязательна, в отличие от РиМ, которого она ломает до конца сессии.
     */
    private fun establishAkrosSession() {
        Log.d(
            TAG,
            "AKROS: SNRM (клиент=$AKROS_CLIENT_ADDRESS, сервер логич.=$AKROS_LOGICAL_ADDRESS, " +
                    "физич.=$AKROS_PHYSICAL_ADDRESS)",
        )
        // Именно Standard-вариант: обычный setNormalResponseMode шлёт кадр с лишним LLC-заголовком и
        // неверной FCS — РиМ это не замечает (не отвечает на SNRM), а AKROS такой кадр отбрасывает.
        val snrmOk = spodes.setNormalResponseModeStandard(
            sourceAddress = AKROS_CLIENT_ADDRESS,
            logicalAddress = AKROS_LOGICAL_ADDRESS,
            physicalAddress = AKROS_PHYSICAL_ADDRESS,
        )
        if (!snrmOk) abortSession("AKROS: не удалось сформировать SNRM-кадр: ${spodes.lastErrorMessage()}")

        val ua = spodes.receiveUnnumberedAcknowledge()
        Log.d(TAG, "AKROS: ответ на SNRM = $ua (1=UA, 2=DM, 0=ошибка/таймаут)")
        if (ua != 1) {
            abortSession(
                if (ua == 2) "AKROS: прибор ответил DM — сеанс не открыт"
                else "AKROS: не дождались UA на SNRM (${spodes.lastErrorMessage()})"
            )
        }

        // Низкого уровня достаточно: всё нужное лежит в публичном буфере индикации.
        Log.d(TAG, "AKROS: AARQ (низкоуровневая ассоциация, LLS, пароль \"$AKROS_ASSOCIATION_PASSWORD\")")
        val aarqOk = spodes.establishConnection(
            securityLevel = AKROS_SECURITY_LEVEL_LOW,
            password = AKROS_ASSOCIATION_PASSWORD,
        )
        if (!aarqOk) abortSession("AKROS: ассоциация не установлена: ${spodes.lastErrorMessage()}")
        Log.i(TAG, "AKROS: ассоциация установлена, можно читать данные")
        readAkrosCaptureObjects()
    }

    /**
     * Читает ответ на уже отправленный GET и, если прибор разбил его на блоки, дочитывает
     * остальные и склеивает перед разбором значения.
     *
     * AKROS соглашается на информационное поле 128 байт, поэтому описание колонок и сам буфер
     * приходят блоками («C4 02» с флагом последнего блока вместо «C4 01»). Принять первый блок за
     * весь ответ нельзя: разбор промахивается мимо границ и «успешно» возвращает 0 колонок.
     * Обычный ответ проходит насквозь, так что вызывать можно для любого GET.
     */
    private fun readDlmsValueWithBlocks(label: String): DlmsValue? {
        val first = spodes.getResponseRawBytes()
        if (first.isEmpty()) {
            Log.w(TAG, "AKROS: пустой ответ на $label (${spodes.lastErrorMessage()})")
            return null
        }
        return assembleDlmsValue(first, label, flat = false)
    }

    /**
     * Склейка DLMS-блоков ответа, когда первый кадр уже прочитан вызывающим кодом: прибор
     * (AKROS ограничивает информационное поле 128 байтами) отвечает «C4 02» — блок с флагом
     * последнего блока вместо «C4 01». Следующий блок запрашивается get-request-next с номером
     * последнего полученного блока: при [flat] — как у пульта, плоской адресацией, иначе — стандартной
     * (запасной путь AKROS). Обычный (не блочный) ответ проходит насквозь.
     *
     * Неполный ответ не разбираем: обрывок декодировался бы «успешно», но в неверные значения.
     * [MAX_DLMS_BLOCKS] защищает от бесконечного обмена, если прибор перестанет ставить флаг
     * последнего блока.
     */
    private fun assembleDlmsValue(first: ByteArray, label: String, flat: Boolean): DlmsValue? {
        var block = parseGetResponseBlock(first)
            ?: return runCatching { parseGetResponseValue(first) }
                .onFailure { Log.w(TAG, "Ответ на $label не разобран: ${it.message}") }
                .getOrNull()

        val data = ByteArrayOutputStream()
        data.write(block.data)
        var blocks = 1
        while (!block.lastBlock && blocks < MAX_DLMS_BLOCKS) {
            val requested = if (flat) {
                spodes.getNextBlockRequestFlatAddress(block.blockNumber)
            } else {
                spodes.sendReadyToReceive(block.blockNumber)
            }
            if (!requested) {
                Log.w(TAG, "Запрос блока ${block.blockNumber + 1} ($label) не ушёл: ${spodes.lastErrorMessage()}")
                break
            }
            val raw = spodes.getResponseRawBytes()
            if (raw.isEmpty()) {
                Log.w(TAG, "Пустой ответ на блок ${block.blockNumber + 1} ($label) (${spodes.lastErrorMessage()})")
                break
            }
            val next = parseGetResponseBlock(raw)
            if (next == null) {
                Log.w(TAG, "Блок ${block.blockNumber + 1} ($label) пришёл в неожиданном формате")
                break
            }
            data.write(next.data)
            block = next
            blocks++
        }

        if (!block.lastBlock) {
            Log.w(TAG, "$label получен не полностью — оборвались на блоке ${block.blockNumber} из $blocks")
            return null
        }
        val bytes = data.toByteArray()
        Log.d(TAG, "$label собран из $blocks блок(ов), ${bytes.size} байт данных")
        return runCatching { decodeDlmsValue(bytes) }
            .onFailure { Log.w(TAG, "$label не разобран после склейки блоков: ${it.message}") }
            .getOrNull()
    }

    /**
     * Читает описание колонок буфера (атрибут 3 «capture_objects» Profile Generic).
     * РиМ присылает значения тройками {OBIS, значение, scaler+unit}, а AKROS — «компактный»
     * буфер: только значения подряд, без OBIS. Смысл колонок хранится в самом приборе, читаем
     * его один раз за сеанс. При неудаче сеанс не рвём — следующее чтение буфера повторит запрос.
     */
    private fun readAkrosCaptureObjects() {
        akrosCaptureObis = emptyList()
        akrosScalerUnits = emptyMap()
        val sent = spodes.getRequest(MAIN_BUFFER_CLASS_ID, MAIN_BUFFER_OBIS, PROFILE_CAPTURE_OBJECTS_ATTRIBUTE)
        if (!sent) {
            Log.w(TAG, "AKROS: не удалось отправить запрос capture_objects: ${spodes.lastErrorMessage()}")
            return
        }
        val value = readDlmsValueWithBlocks("capture_objects") ?: return
        try {
            val columns = captureColumnsOf(value)
            akrosCaptureObis = columns
            Log.i(
                TAG,
                "AKROS: описание буфера получено, ${columns.size} колонок: " +
                        columns.joinToString { c ->
                            c?.let { "${it.obisCode} (${ObisCatalog.nameOf(it.obisCode) ?: "?"}, класс ${it.classId})" } ?: "—"
                        },
            )
            readAkrosScalerUnits(columns)
        } catch (ex: Exception) {
            Log.e(TAG, "AKROS: не удалось разобрать capture_objects: ${ex.message}", ex)
        }
    }

    /**
     * Читает единицу и множитель (атрибут scaler_unit) каждой колонки буфера: компактный буфер
     * содержит только числа, а без множителя показания могли бы отличаться от настоящих в
     * тысячу раз и выглядеть правдоподобно. Спрашиваем только объекты, у которых этот атрибут
     * есть (см. [ScalerUnit.attributeFor]); единицы за сеанс не меняются, поэтому читаем один раз.
     * Ошибка на одной колонке не фатальна — она остаётся без единицы.
     */
    private fun readAkrosScalerUnits(columns: List<CaptureColumn?>) {
        val units = mutableMapOf<String, ScalerUnit>()
        columns.filterNotNull()
            .distinctBy { it.obisCode }
            .forEach { column ->
                val attribute = ScalerUnit.attributeFor(column.classId) ?: return@forEach
                if (!spodes.getRequest(column.classId, column.obisCode, attribute)) {
                    Log.w(TAG, "AKROS: запрос scaler_unit для ${column.obisCode} не ушёл: ${spodes.lastErrorMessage()}")
                    return@forEach
                }
                val answer = readDlmsValueWithBlocks("scaler_unit ${column.obisCode}") ?: return@forEach
                val parsed = runCatching { scalerUnitOf(answer) }
                    .onFailure { Log.w(TAG, "AKROS: scaler_unit для ${column.obisCode} не разобран: ${it.message}") }
                    .getOrNull()
                if (parsed != null) units[column.obisCode] = parsed
            }
        akrosScalerUnits = units
        Log.i(
            TAG,
            "AKROS: единицы измерения получены для ${units.size} из ${columns.count { it != null }} колонок: " +
                    units.entries.joinToString { (obis, su) ->
                        "$obis → ${unitLabelOf(su.unit) ?: "ед.${su.unit}"}${if (su.scaler != 0) " ×10^${su.scaler}" else ""}"
                    },
        )
    }

    /**
     * «Насос» между транспортом и протоколом: одна корутина пересылает входящие notify-пакеты
     * в нативный код, другая опрашивает исходящие байты и шлёт их по BLE.
     */
    private fun startTransportPump() {
        stopTransportPump()
        pumpJob = repositoryScope.launch {
            launch {
                ble.incomingBytes().collect { chunk ->
                    journal.appendBytes(chunk)
                    spodes.pushIncomingBytes(chunk)
                }
            }
            launch {
                while (true) {
                    val outgoing = spodes.pullOutgoingBytes()
                    if (outgoing.isNotEmpty()) ble.writeBytes(outgoing)
                    delay(PUMP_POLL_INTERVAL_MS)
                }
            }
        }
    }

    private fun stopTransportPump() {
        pumpJob?.cancel()
        pumpJob = null
        stopAutoRefresh()
    }

    /**
     * Автообновление: каждые [AUTO_REFRESH_INTERVAL_MS] перечитывает буфер индикации (тот же
     * [readAndApplyMainBuffer], что и у кнопки «Обновить») и заодно обновляет RSSI. Лок берётся на
     * каждой итерации, так что ручной запрос или команда реле просто ждут очереди.
     *
     * При [MAX_CONSECUTIVE_PROTOCOL_FAILURES] неудачных циклах подряд принудительно
     * переподключается (см. [consecutiveEmptyOrFailedReads]); job завершается сам, не дожидаясь
     * отмены из коллектора разрывов, чтобы не отправить ещё один GET в готовящийся разрыв.
     */
    private fun startAutoRefresh() {
        stopAutoRefresh()
        ble.requestRssiRead()
        autoRefreshJob = repositoryScope.launch {
            while (true) {
                delay(AUTO_REFRESH_INTERVAL_MS)
                val ok = readMainBuffer()
                if (!ok) Log.w(TAG, "Автообновление: GET-запрос не удался ($lastProtocolError)")
                if (consecutiveEmptyOrFailedReads >= MAX_CONSECUTIVE_PROTOCOL_FAILURES) {
                    consecutiveEmptyOrFailedReads = 0
                    consecutiveForceReconnectsWithoutData++
                    val cold = consecutiveForceReconnectsWithoutData >= COLD_RECONNECT_THRESHOLD
                    if (cold) consecutiveForceReconnectsWithoutData = 0
                    Log.w(
                        TAG,
                        "Автообновление: $MAX_CONSECUTIVE_PROTOCOL_FAILURES неудачных циклов подряд — " +
                                "считаем DLMS-сессию разорванной при формально рабочем GATT, принудительно " +
                                "переподключаемся" + if (cold) " (холодное переподключение — обычное не помогало $COLD_RECONNECT_THRESHOLD раз подряд)" else "",
                    )
                    ble.forceReconnect(cold = cold)
                    return@launch
                }
                ble.requestRssiRead()
            }
        }
    }

    private fun stopAutoRefresh() {
        autoRefreshJob?.cancel()
        autoRefreshJob = null
    }

    /**
     * Пересборка протокольного сеанса после того, как Android сам восстановил GATT. Порядок тот
     * же, что при первом подключении, но без поиска устройства и разбора имени.
     *
     * Пауза перед началом нужна счётчику: после резкого обрыва HDLC-сессии он ещё не готов
     * принять первый кадр новой сессии, и первый GET почти гарантированно проваливался.
     * Клиент уничтожается под [protocolMutex] — иначе destroyClient() обнулил бы handle под
     * выполняющимся чтением (см. [lastProtocolError]).
     *
     * Автообновление запускается и при неудачном первом GET: если оно не стартует, опрос
     * останавливается навсегда при формально «подключённом» BLE; а при мёртвом транспорте
     * счётчик неудач сам вызовет forceReconnect().
     */
    private fun reestablishProtocolSession() {
        repositoryScope.launch {
            try {
                delay(RECONNECT_SETTLE_DELAY_MS)
                protocolMutex.withLock {
                    withContext(Dispatchers.IO) {
                        spodes.destroyClient()
                        spodes.createClient()
                    }
                }
                startTransportPump()
                val connected = establishSessionAndReadFirst()
                startAutoRefresh()
                if (connected) {
                    Log.d(TAG, "Протокольный сеанс восстановлен после автопереподключения")
                } else {
                    Log.w(
                        TAG,
                        "Восстановление сеанса: первый GET-запрос не удался ($lastProtocolError) — " +
                                "автообновление всё равно запущено и будет повторять попытки",
                    )
                }
            } catch (ex: Exception) {
                Log.e(TAG, "Восстановление сеанса после автопереподключения не удалось: ${ex.message}", ex)
            }
        }
    }

    override suspend fun addToJournal(): Boolean = withContext(Dispatchers.IO) { journal.commit() }

    override fun readings(): Flow<List<Reading>> = _readings.asStateFlow()

    /**
     * Кнопка «Обновить» на всех экранах: один GET буфера обновляет показания, сеть и инфо.
     * Проверяем pumpJob, а не activeSerialNumber (при подключении по адресу он может быть null).
     */
    override suspend fun refreshReadings() {
        if (pumpJob?.isActive != true) {
            Log.w(TAG, "refreshReadings(): нет активного BLE-соединения — обновлять нечего")
            return
        }
        if (!readMainBuffer()) Log.w(TAG, "refreshReadings(): GET-запрос не удался ($lastProtocolError)")
    }

    override fun networkParams(): Flow<NetworkParams> = _networkParams.map {
        it ?: NetworkParams(
            voltage = PhaseValues(0.0),
            current = PhaseValues(0.0),
            power = PhaseValues(0.0),
        )
    }

    override fun meterInfo(): Flow<MeterInfo> = _meterInfo.map { it ?: blankMeterInfo() }

    override fun relayState(): Flow<RelayState> = _relayState.asStateFlow()

    override fun tamperState(): Flow<TamperState> = _tamperState.asStateFlow()

    /**
     * Включение реле: ACTION method 2 («remote_reconnect») Disconnect Control. Формат
     * подтверждён логом пульта: параметр Integer8 = 0. 60-секундный отсчёт — на стороне ViewModel.
     */
    override suspend fun turnRelayOn() {
        sendDisconnectControlAction(methodId = 2, label = "remote_reconnect/включение")
    }

    /**
     * Отключение реле: ACTION method 1 («remote_disconnect»). В логе пульта нашлась только команда
     * включения, поэтому параметр (Integer8 = 0) собран по аналогии — предположение. Если прибор
     * вернёт ошибку, первым делом стоит попробовать вызов без параметра: по DLMS UA 1000-1 у
     * remote_disconnect параметров нет.
     */
    override suspend fun turnRelayOff() {
        sendDisconnectControlAction(methodId = 1, label = "remote_disconnect/отключение")
    }

    /**
     * ACTION к Disconnect Control той же «плоской» адресацией, что и GET. Состояние реле после
     * успешного Action-Result (0) обновляем сразу, по факту команды: прямое чтение атрибутов без
     * ассоциации отказывает (0x0D). Реальное значение придёт на следующем автообновлении из
     * буфера индикации и перезапишет предположение.
     */
    private suspend fun sendDisconnectControlAction(methodId: Int, label: String) {
        if (pumpJob?.isActive != true) {
            Log.w(TAG, "Размыкатель ($label): нет активного BLE-соединения — команда не отправлена")
            return
        }
        protocolMutex.withLock {
            withContext(Dispatchers.IO) {
                Log.d(TAG, "ACTION-запрос размыкателя ($DISCONNECT_CONTROL_OBIS, class=$DISCONNECT_CONTROL_CLASS_ID Disconnect Control, method=$methodId \"$label\")")
                val sent = spodes.actionRequestFlatAddress(
                    classId = DISCONNECT_CONTROL_CLASS_ID,
                    obisCode = DISCONNECT_CONTROL_OBIS,
                    methodId = methodId,
                    hasParameter = true,
                    parameterType = 0x0F, // Integer8
                    parameterValue = 0,
                    destAddress = FLAT_DEST_ADDRESS,
                    srcAddress = FLAT_SRC_ADDRESS,
                )
                if (!sent) {
                    Log.w(TAG, "Размыкатель ($label): запрос не отправлен (${spodes.lastErrorMessage()})")
                    return@withContext
                }
                val raw = spodes.getResponseRawBytes()
                if (raw.isEmpty()) {
                    Log.w(TAG, "Размыкатель ($label): пустой ответ от транспорта (${spodes.lastErrorMessage()})")
                    return@withContext
                }
                val result = parseActionResponseResult(raw)
                when {
                    result == null -> Log.w(TAG, "Размыкатель ($label): не удалось разобрать ACTION-response")
                    result != 0 -> Log.w(
                        TAG,
                        "Размыкатель ($label): сервер вернул Action-Result код 0x%02X (%d) — команда НЕ выполнена"
                            .format(result, result),
                    )
                    else -> {
                        Log.d(TAG, "Размыкатель ($label): УСПЕХ (Action-Result=0)")
                        _relayState.value = _relayState.value.copy(isOn = methodId == 2, source = RelaySource.REMOTE_CONTROL)
                    }
                }
            }
        }
    }

    override fun history(serialNumber: String): Flow<List<Reading>> =
        readingDao.observeHistory(serialNumber).map { list ->
            list.map { e ->
                Reading(
                    obisId = e.obisId,
                    tariff = e.tariff,
                    valueKwh = e.valueKwh,
                    timestamp = Instant.ofEpochSecond(e.timestampEpochSeconds),
                )
            }
        }

    override suspend fun forgetDevice(serialNumber: String) {
        deviceStore.forget(serialNumber)
        if (activeSerialNumber == serialNumber) disconnect()
    }

    /**
     * Закрывает сеанс, не забывая прибор. destroyClient() ждёт [protocolMutex]: иначе он
     * обнулил бы handle под выполняющимся чтением (SIGSEGV). protocolSessionEstablished
     * сбрасывается, чтобы следующее явное подключение не приняли за автовосстановление.
     */
    override suspend fun disconnect() {
        stopTransportPump()
        protocolMutex.withLock { withContext(Dispatchers.IO) { spodes.destroyClient() } }
        ble.disconnect()
        withContext(NonCancellable + Dispatchers.IO) { journal.end() }
        // Данные отключённого прибора не должны достаться следующему.
        resetSessionData()
        protocolSessionEstablished = false
        consecutiveEmptyOrFailedReads = 0
        isAkros = false
        akrosAssociation = false
    }

    companion object {
        private const val TAG = "MeterRepository"

        // «Плоская» однобайтовая адресация штатного пульта (HDLC_DEST_ADDR/HDLC_SRC_ADDR в прошивке).
        private const val FLAT_DEST_ADDRESS = 0x03
        private const val FLAT_SRC_ADDRESS = 0x43

        private const val PUMP_POLL_INTERVAL_MS = 20L
        private const val AUTO_REFRESH_INTERVAL_MS = 5_000L

        /** Три цикла × 5 с = 15 с: не реагируем на единичный сбой, но и не держим мёртвое «Подключено». */
        private const val MAX_CONSECUTIVE_PROTOCOL_FAILURES = 3

        /** Сколько циклов forceReconnect() подряд без данных терпим до «холодного» переподключения. */
        private const val COLD_RECONNECT_THRESHOLD = 3

        /** Пауза перед пересборкой сеанса после восстановления GATT (см. reestablishProtocolSession). */
        private const val RECONNECT_SETTLE_DELAY_MS = 1_500L

        /** Буфер индикации — «Profile | Индикация» (класс 7, атрибут 2) в паспортах РиМ и AKROS. */
        private const val MAIN_BUFFER_OBIS = "0.0.21.0.2.255"
        private const val MAIN_BUFFER_CLASS_ID = 7

        /** Disconnect Control — размыкатель (OBIS из паспорта прибора). */
        private const val DISCONNECT_CONTROL_OBIS = "0.0.96.3.10.255"
        private const val DISCONNECT_CONTROL_CLASS_ID = 70

        /**
         * Версия ПО — единственный код, подтверждённый паспортом. 0.0.96.1.0/1.255 в кандидаты
         * не годятся: это серийный номер и тип прибора, и в поле «Версия ПО» они попали бы молча.
         */
        private val FIRMWARE_VERSION_OBIS_CANDIDATES = listOf("0.0.96.1.2.255")

        /** «Device ID 1» — имя модели текстом («RIM 189.40»). */
        private val MODEL_NAME_OBIS_CANDIDATES = listOf("0.0.96.1.1.255")

        private const val SERIAL_NUMBER_OBIS = "0.0.96.1.0.255"

        /** Дата и время отдельными объектами (у одних приборов оба, у других один, у новых — ни одного). */
        private const val DATE_OBIS = "0.0.0.9.2.255"
        private const val TIME_OBIS = "0.0.0.9.1.255"

        // Индикаторы экрана «Показания».
        private const val HDLC_P1_OBIS = "0.0.22.0.0.255"
        private const val HDLC_P2_OBIS = "0.1.22.0.0.255"
        private const val HDLC_P3_OBIS = "0.2.22.0.0.255"
        private const val HDLC_P4_OBIS = "0.3.22.0.0.255"
        private const val OPERATING_TIME_OBIS = "0.0.96.8.0.255"

        /** Пауза между попыткой без ассоциации и ассоциацией AKROS, чтобы запоздавшие ответы не попали в новый сеанс. */
        private const val AKROS_FALLBACK_SETTLE_DELAY_MS = 300L

        /** Атрибут 3 Profile Generic — capture_objects. */
        private const val PROFILE_CAPTURE_OBJECTS_ATTRIBUTE = 3

        /** Предел числа блоков одного ответа; самый длинный нужный ответ укладывается в три. */
        private const val MAX_DLMS_BLOCKS = 32

        // AKROS: в эфире — байты 0x41 (клиент) и 0x02 0x21 (сервер); библиотека кодирует
        // переданные числа ровно в них (32→0x41, 1→0x02, 16→0x21).
        private const val AKROS_CLIENT_ADDRESS = 32
        private const val AKROS_LOGICAL_ADDRESS = 1
        private const val AKROS_PHYSICAL_ADDRESS = 16

        /** LLS — проверка по паролю без шифрования (mechanism-name 2.16.756.5.8.2.1). */
        private const val AKROS_SECURITY_LEVEL_LOW = 1

        /** Пароль ассоциации чтения из эталонного лога; не путать с PIN BLE-сопряжения. */
        private const val AKROS_ASSOCIATION_PASSWORD = "Reader"
    }
}
