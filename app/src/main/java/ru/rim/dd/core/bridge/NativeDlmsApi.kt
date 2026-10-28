package ru.rim.dd.core.bridge

/**
 * Интерфейс нативного клиента СПОДЭС (C++ через JNI). Боевая реализация — [SpodesClientBridge];
 * в тестах вместо неё стоит фейк, который отвечает настоящими байтами из лога прибора, — так
 * протокольный уровень проверяется без железа и без загрузки нативной библиотеки.
 *
 * Все методы работают с одним нативным клиентом, который не потокобезопасен: вызывать их нужно
 * строго по очереди (см. MeterSessionManager.protocolMutex).
 */
interface NativeDlmsApi {

    /** Создаёт нативный клиент; счётчики HDLC-кадров начинаются заново. */
    fun createClient()

    /** Уничтожает нативный клиент; повторный вызов безопасен. */
    fun destroyClient()

    /** Байты, которые нужно отправить прибору (пусто — отправлять нечего). */
    fun pullOutgoingBytes(): ByteArray

    /** Передать байты, полученные по BLE-notify, клиенту на разбор. */
    fun pushIncomingBytes(bytes: ByteArray)

    /**
     * Запасной путь AKROS: корректный SNRM с согласованием max-info.
     * Библиотека принимает sourceAddress только 16/32/48.
     */
    fun setNormalResponseModeStandard(
        sourceAddress: Int,
        logicalAddress: Int,
        physicalAddress: Int,
        maxInfoTransmit: Int = 128,
        maxInfoReceive: Int = 128,
    ): Boolean

    /** Ожидание ответа на SNRM: 1 — UA, 2 — DM, 0 — ошибка/таймаут. */
    fun receiveUnnumberedAcknowledge(): Int

    /** AARQ; вызывать после успешного UA. */
    fun establishConnection(securityLevel: Int, password: String?): Boolean

    /** GET атрибута со стандартной адресацией (запасной путь AKROS после ассоциации). */
    fun getRequest(classId: Int, obisCode: String, attributeId: Int): Boolean

    /** Запрос следующего блока ответа со стандартной адресацией (запасной путь AKROS). */
    fun sendReadyToReceive(blockNumber: Int): Boolean

    /**
     * GET с «плоской» однобайтовой HDLC-адресацией — так обращается к счётчику штатный пульт РиМ.
     * [destAddress]/[srcAddress] — готовые (несдвинутые) байты.
     */
    fun getRequestFlatAddress(classId: Int, obisCode: String, attributeId: Int, destAddress: Int, srcAddress: Int): Boolean

    /** Запрос следующего блока ответа («плоская» адресация); [lastBlockNumber] — номер последнего полученного. */
    fun getNextBlockRequestFlatAddress(lastBlockNumber: Int): Boolean

    /** ACTION с «плоской» адресацией. Для Disconnect Control: 1 — remote_disconnect, 2 — remote_reconnect. */
    fun actionRequestFlatAddress(
        classId: Int,
        obisCode: String,
        methodId: Int,
        hasParameter: Boolean,
        parameterType: Int,
        parameterValue: Int,
        destAddress: Int,
        srcAddress: Int,
    ): Boolean

    /** Сырые байты полного ответа сервера; пусто — ответа нет. */
    fun getResponseRawBytes(): ByteArray

    /** Текст последней ошибки нативного кода. */
    fun lastErrorMessage(): String
}
