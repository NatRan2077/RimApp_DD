package ru.rim.dd.data.session

import ru.rim.dd.core.log.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ru.rim.dd.core.ble.MeterBleClient
import ru.rim.dd.core.dlms.ObisReading
import ru.rim.dd.core.model.ConnectionState
import ru.rim.dd.core.model.MeterDeviceName
import ru.rim.dd.core.model.MeterProtocolProfile
import ru.rim.dd.data.dlms.DlmsSessionClient
import ru.rim.dd.data.dlms.MainBufferResult
import ru.rim.dd.data.journal.JournalRecorder
import ru.rim.dd.data.mapper.MeterBufferMapper
import ru.rim.dd.data.state.MeterState
import ru.rim.dd.data.transport.TransportPump
import javax.inject.Inject
import javax.inject.Singleton

/** Прибор, к которому только что подключились. */
data class ConnectedMeter(
    /** Номер для списка сохранённых приборов: сообщённый самим прибором, иначе из BLE-имени. */
    val serialNumber: String,
    val bleAddress: String,
)

/**
 * Жизненный цикл протокольного сеанса с прибором: подключение (BLE → нативный клиент → первое
 * чтение буфера), автообновление раз в 5 с, пересборка сеанса после автопереподключения GATT,
 * счётчики неудач и принудительное переподключение, отключение.
 *
 * Сам протокол — в [DlmsSessionClient], байты — в [TransportPump], данные — в [MeterState],
 * разбор буфера — в [MeterBufferMapper]; здесь только порядок действий и состояние сеанса.
 */
@Singleton
class MeterSessionManager @Inject constructor(
    private val ble: MeterBleClient,
    private val dlms: DlmsSessionClient,
    private val pump: TransportPump,
    private val state: MeterState,
    private val journal: JournalRecorder,
) {
    /** Номер активного прибора, разобранный из BLE-имени (запасной вариант). */
    var activeSerialNumber: String? = null
        private set

    /**
     * Серийный номер, который прибор сообщил сам (0.0.96.1.0.255 в буфере индикации).
     * Достовернее имени из BLE-рекламы: по нему connectBySerialNumber() сверяет, что
     * подключились к нужному прибору.
     */
    private var meterReportedSerial: String? = null

    /** AKROS определяется по BLE-имени один раз при подключении; для РиМ всегда false. */
    private var isAkros = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
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
     * Читать dlms.lastErrorMessage() снаружи лока нельзя: параллельный destroyClient()
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

    /** true, пока «насос» работает, то есть есть активное BLE-соединение с протокольным обменом. */
    val isActive: Boolean get() = pump.isActive

    init {
        // GATT переподключает MeterBleClient (свой цикл); здесь только протокольная часть:
        // при обрыве останавливаем обмен, при восстановлении пересобираем DLMS-сеанс.
        scope.launch {
            ble.unexpectedDisconnects.collect {
                AppLog.w(TAG, "Обнаружен неожиданный разрыв связи — останавливаем протокольный обмен, ждём автопереподключения BLE")
                stopTransportPump()
            }
        }
        scope.launch {
            ble.connectionState.collect { connection ->
                if (connection is ConnectionState.Connected && !pump.isActive && protocolSessionEstablished) {
                    AppLog.d(TAG, "BLE-соединение восстановлено (автопереподключение) — пересобираем протокольный сеанс")
                    reestablishProtocolSession()
                }
            }
        }
    }

    /**
     * Выполняет [block] под протокольным локом — для команд, которые идут в тот же нативный
     * клиент (реле): запросы обмена строго по очереди.
     */
    suspend fun <T> exclusive(block: suspend () -> T): T = protocolMutex.withLock { block() }

    /**
     * Данные принадлежат конкретному прибору, поэтому при новом подключении и при отключении
     * всё, что осталось от прошлого, обнуляется. Иначе поля, которых новый прибор не присылает
     * (например HDLC P1 у AKROS), показывались бы со значениями прежнего ПУ: разбор буфера
     * сохраняет прежнее значение для не пришедших полей.
     */
    private fun resetSessionData() {
        state.reset()
        dlms.resetDescription()
        activeSerialNumber = null
        meterReportedSerial = null
    }

    /**
     * Подключение по адресу: BLE, протокольный сеанс, первое чтение буфера. Опрос не запускается —
     * его включает [startPolling] после того, как вызывающий код запомнил прибор.
     */
    suspend fun connectByAddress(address: String, deviceName: String?): ConnectedMeter {
        // Всё, что осталось от прошлого прибора (номер, показания, инфо), к новому не относится.
        resetSessionData()
        withContext(Dispatchers.IO) { ble.connectByAddress(address, deviceName) }
        openProtocolSession(deviceName)

        // Сбрасываем до разбора имени: иначе при неудачном разборе остался бы номер прошлого прибора.
        // meterReportedSerial не трогаем — первое чтение буфера уже могло его получить.
        activeSerialNumber = null
        applyDeviceNameInfo(deviceName)
        return ConnectedMeter(
            serialNumber = meterReportedSerial ?: activeSerialNumber ?: address,
            bleAddress = address,
        )
    }

    /**
     * Ищет в эфире прибор с ТОЧНО таким серийным номером и после подключения сверяет его с
     * номером, который прибор сообщил сам, — имя в рекламе прибор называет про себя, ему одному
     * доверять нельзя. Несовпадение — разрыв связи: иначе пользователь увидел бы показания
     * (и мог бы управлять реле) чужого прибора. Если прибор номер не сообщает, остаётся только
     * фильтр поиска; это пишется в лог.
     */
    suspend fun connectBySerialNumber(serialNumber: String): ConnectedMeter {
        resetSessionData()
        val found = withContext(Dispatchers.IO) { ble.connectBySerialNumber(serialNumber) }
        openProtocolSession(found.name)

        val reported = meterReportedSerial
        if (reported != null && !MeterDeviceName.serialsEqual(reported, serialNumber)) {
            AppLog.e(
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
            AppLog.w(
                TAG,
                "Прибор не сообщил свой серийный номер (${MeterBufferMapper.SERIAL_NUMBER_OBIS}) — сверить подключение " +
                        "по протоколу не с чем, полагаемся только на совпадение номера в имени устройства " +
                        "(\"${found.name}\")",
            )
        }

        // Реальное имя из эфира приоритетнее введённого номера; если оно нестандартное — берём введённый.
        applyDeviceNameInfo(found.name)
        if (activeSerialNumber == null) activeSerialNumber = serialNumber
        return ConnectedMeter(
            serialNumber = reported ?: activeSerialNumber ?: serialNumber,
            bleAddress = found.address,
        )
    }

    /** Подключение завершено: первый снимок в журнал и запуск автообновления. */
    fun startPolling() {
        recordJournal()
        startAutoRefresh()
    }

    /**
     * Кнопка «Обновить» на всех экранах: один GET буфера обновляет показания, сеть и инфо.
     * Проверяем [isActive], а не activeSerialNumber (при подключении по адресу он может быть null).
     */
    suspend fun refresh() {
        if (!pump.isActive) {
            AppLog.w(TAG, "refreshReadings(): нет активного BLE-соединения — обновлять нечего")
            return
        }
        if (!readMainBuffer()) AppLog.w(TAG, "refreshReadings(): GET-запрос не удался ($lastProtocolError)")
    }

    /**
     * Закрывает сеанс, не забывая прибор. destroyClient() ждёт [protocolMutex]: иначе он
     * обнулил бы handle под выполняющимся чтением (SIGSEGV). protocolSessionEstablished
     * сбрасывается, чтобы следующее явное подключение не приняли за автовосстановление.
     */
    suspend fun disconnect() {
        stopTransportPump()
        protocolMutex.withLock { withContext(Dispatchers.IO) { dlms.destroyClient() } }
        ble.disconnect()
        withContext(NonCancellable + Dispatchers.IO) { journal.end() }
        // Данные отключённого прибора не должны достаться следующему.
        resetSessionData()
        protocolSessionEstablished = false
        consecutiveEmptyOrFailedReads = 0
        isAkros = false
        dlms.akrosAssociation = false
    }

    /**
     * Общая часть подключения: нативный клиент, сеанс и первое чтение буфера. Основной путь — как
     * у штатного пульта: без SNRM и ассоциации сразу I-кадры с GET «плоской» адресацией; нумерацию
     * N(S)/N(R) ведёт нативный клиент. Для AKROS есть запасной путь, см. [establishSessionAndReadFirst].
     */
    private suspend fun openProtocolSession(deviceName: String?) {
        isAkros = MeterProtocolProfile.forDeviceName(deviceName) == MeterProtocolProfile.AKROS_STANDARD
        dlms.akrosAssociation = false
        AppLog.i(TAG, "Обмен с \"$deviceName\": ${if (isAkros) "AKROS" else "РиМ"}")
        // Карточка журнала и лог байтов заводятся до первого обмена; при неудаче удаляются.
        withContext(Dispatchers.IO) { journal.begin() }
        try {
            dlms.createClient()
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
     * Выбранный путь запоминается в [DlmsSessionClient.akrosAssociation], после обрыва связи используется он же.
     * Бросает исключение (через [abortSession]), если ассоциация не установилась.
     */
    private suspend fun establishSessionAndReadFirst(): Boolean {
        if (isAkros && dlms.akrosAssociation) {
            establishAkrosSession()
            return readMainBuffer()
        }
        consecutiveEmptyOrFailedReads = 0
        val ok = readMainBuffer()
        if (!isAkros || (ok && consecutiveEmptyOrFailedReads == 0)) return ok

        AppLog.w(TAG, "AKROS не ответил на запрос без ассоциации ($lastProtocolError) — пробуем ассоциацию (SNRM + AARQ)")
        // Насос останавливаем до destroyClient(): иначе входящий пакет попал бы в уничтоженный клиент.
        // Пока он остановлен, запоздавшие ответы на прежние кадры отбрасываются.
        stopTransportPump()
        delay(AKROS_FALLBACK_SETTLE_DELAY_MS)
        protocolMutex.withLock {
            withContext(Dispatchers.IO) { dlms.recreateClient() }
        }
        startTransportPump()
        establishAkrosSession()
        dlms.akrosAssociation = true
        consecutiveEmptyOrFailedReads = 0
        val fallbackOk = readMainBuffer()
        if (!fallbackOk || consecutiveEmptyOrFailedReads > 0) dlms.akrosAssociation = false
        return fallbackOk
    }

    /** Ассоциация AKROS; при отказе закрывает транспорт и бросает исключение (см. [abortSession]). */
    private suspend fun establishAkrosSession() {
        withContext(Dispatchers.IO) { dlms.establishAkrosSession()?.let { abortSession(it) } }
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
            AppLog.w(TAG, "Модель/серийный номер: не удалось разобрать имя устройства \"$name\" (ожидалось 13 цифр после префикса \"RIM\")")
            return
        }
        AppLog.d(TAG, "Модель/серийный номер разобраны из имени устройства \"$name\": модель=${parsed.model}, серийный=${parsed.serialNumber}")
        activeSerialNumber = parsed.serialNumber
        state.updateInfo {
            (it ?: MeterBufferMapper.blankMeterInfo(activeSerialNumber))
                .copy(model = parsed.model, serialNumber = parsed.serialNumber)
        }
    }

    /**
     * GET буфера индикации (см. [DlmsSessionClient.readMainBuffer]) и раскладка ответа в
     * StateFlow'ы. Общий код для первого чтения, автообновления и кнопки «Обновить».
     *
     * Возвращает false, только если запрос не удалось отправить. Пустой ответ и ошибка разбора
     * считаются неудачей в [consecutiveEmptyOrFailedReads], но связь не рвут.
     */
    private fun readAndApplyMainBuffer(): Boolean {
        when (val result = dlms.readMainBuffer()) {
            is MainBufferResult.NotSent -> {
                lastProtocolError = result.error
                consecutiveEmptyOrFailedReads++
                return false
            }
            is MainBufferResult.Empty -> {
                lastProtocolError = result.error
                consecutiveEmptyOrFailedReads++
                AppLog.w(TAG, "GET-response: пустой ответ от транспорта ($lastProtocolError, подряд неудач: $consecutiveEmptyOrFailedReads)")
            }
            is MainBufferResult.Incomplete -> {
                // Именно ответ транспорта — надёжный признак, что сеанс реально поднялся.
                protocolSessionEstablished = true
                consecutiveEmptyOrFailedReads++
                AppLog.w(TAG, "GET-response: буфер не получен (подряд неудач: $consecutiveEmptyOrFailedReads)")
            }
            is MainBufferResult.DecodeFailed -> {
                protocolSessionEstablished = true
                consecutiveEmptyOrFailedReads++
                AppLog.e(TAG, "GET-response: не удалось разобрать DLMS-данные: ${result.error.message} (подряд неудач: $consecutiveEmptyOrFailedReads)", result.error)
            }
            is MainBufferResult.Decoded -> {
                protocolSessionEstablished = true
                try {
                    applyDecodedBuffer(result.readings)
                    consecutiveEmptyOrFailedReads = 0
                    consecutiveForceReconnectsWithoutData = 0
                } catch (ex: Exception) {
                    consecutiveEmptyOrFailedReads++
                    AppLog.e(TAG, "GET-response: не удалось разобрать DLMS-данные: ${ex.message} (подряд неудач: $consecutiveEmptyOrFailedReads)", ex)
                }
            }
        }
        return true
    }

    /**
     * Раскладывает буфер индикации по StateFlow'ам: состояние реле и пломб, энергия по тарифам,
     * мгновенные параметры сети, сведения о приборе. Показания за предыдущий период (F=101)
     * экран «Показания» показывает отдельной группой «РДЧ».
     */
    private fun applyDecodedBuffer(all: List<ObisReading>) {
        state.applyBuffer(
            all = all,
            activeSerialNumber = activeSerialNumber,
            knownReportedSerial = meterReportedSerial,
            log = { AppLog.d(TAG, it) },
        )?.let { meterReportedSerial = it }
        recordJournal()
    }

    private fun recordJournal() {
        journal.record(
            info = state.info.value,
            network = state.network.value,
            readings = state.readings.value,
            reportedSerial = meterReportedSerial,
        )
    }

    /** Запускает «насос» BLE ⇄ нативный клиент; входящие байты попутно пишутся в журнал. */
    private fun startTransportPump() {
        stopTransportPump()
        pump.start(onIncoming = { journal.onIncomingBytes(it) })
    }

    private fun stopTransportPump() {
        pump.stop()
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
        autoRefreshJob = scope.launch {
            while (true) {
                delay(AUTO_REFRESH_INTERVAL_MS)
                val ok = readMainBuffer()
                if (!ok) AppLog.w(TAG, "Автообновление: GET-запрос не удался ($lastProtocolError)")
                if (consecutiveEmptyOrFailedReads >= MAX_CONSECUTIVE_PROTOCOL_FAILURES) {
                    consecutiveEmptyOrFailedReads = 0
                    consecutiveForceReconnectsWithoutData++
                    val cold = consecutiveForceReconnectsWithoutData >= COLD_RECONNECT_THRESHOLD
                    if (cold) consecutiveForceReconnectsWithoutData = 0
                    AppLog.w(
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
        scope.launch {
            try {
                delay(RECONNECT_SETTLE_DELAY_MS)
                protocolMutex.withLock { withContext(Dispatchers.IO) { dlms.recreateClient() } }
                startTransportPump()
                val connected = establishSessionAndReadFirst()
                startAutoRefresh()
                if (connected) {
                    AppLog.d(TAG, "Протокольный сеанс восстановлен после автопереподключения")
                } else {
                    AppLog.w(
                        TAG,
                        "Восстановление сеанса: первый GET-запрос не удался ($lastProtocolError) — " +
                                "автообновление всё равно запущено и будет повторять попытки",
                    )
                }
            } catch (ex: Exception) {
                AppLog.e(TAG, "Восстановление сеанса после автопереподключения не удалось: ${ex.message}", ex)
            }
        }
    }

    private companion object {
        const val TAG = "MeterSession"

        const val AUTO_REFRESH_INTERVAL_MS = 5_000L

        /** Три цикла × 5 с = 15 с: не реагируем на единичный сбой, но и не держим мёртвое «Подключено». */
        const val MAX_CONSECUTIVE_PROTOCOL_FAILURES = 3

        /** Сколько циклов forceReconnect() подряд без данных терпим до «холодного» переподключения. */
        const val COLD_RECONNECT_THRESHOLD = 3

        /** Пауза перед пересборкой сеанса после восстановления GATT (см. reestablishProtocolSession). */
        const val RECONNECT_SETTLE_DELAY_MS = 1_500L

        /** Пауза между попыткой без ассоциации и ассоциацией AKROS, чтобы запоздавшие ответы не попали в новый сеанс. */
        const val AKROS_FALLBACK_SETTLE_DELAY_MS = 300L
    }
}