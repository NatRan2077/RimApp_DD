package ru.rim.dd.testutil

import ru.rim.dd.core.bridge.NativeDlmsApi

/**
 * Нативный клиент СПОДЭС без C++: отвечает заранее заданными байтами (обычно — настоящими, из
 * лога прибора). На каждый запрос тест задаёт «ответчика»: лямбда возвращает байты, которые
 * потом отдаст [getResponseRawBytes], либо null — «ответа нет» (пустой массив).
 *
 * Все вызовы записываются в [calls] в порядке поступления — по ним проверяется последовательность
 * действий (например, SNRM → UA → AARQ → capture_objects).
 */
class FakeNativeDlmsApi : NativeDlmsApi {

    val calls = mutableListOf<String>()

    /** Живой ли сейчас клиент (create/destroy сбалансированы). */
    var clientAlive = false
        private set
    var createCount = 0
        private set
    var destroyCount = 0
        private set

    /** GET с «плоской» адресацией (путь РиМ): (classId, obis, attribute) → ответ. */
    var onFlatGet: (classId: Int, obis: String, attribute: Int) -> ByteArray? = { _, _, _ -> null }

    /** GET со стандартной адресацией (запасной путь AKROS). */
    var onGet: (classId: Int, obis: String, attribute: Int) -> ByteArray? = { _, _, _ -> null }

    /** Запрос следующего блока: номер последнего полученного блока → ответ. */
    var onNextBlockFlat: (lastBlock: Int) -> ByteArray? = { null }
    var onReadyToReceive: (lastBlock: Int) -> ByteArray? = { null }

    /** ACTION: номер метода → ответ. */
    var onAction: (methodId: Int) -> ByteArray? = { null }

    /** Результаты «отправки»: false имитирует отказ нативного кода сформировать кадр. */
    var flatGetSends = true
    var getSends = true
    var nextBlockSends = true
    var actionSends = true
    var snrmOk = true
    var uaResult = 1
    var aarqOk = true
    var error = "тестовая ошибка"

    /** Байты, которые «насос» заберёт у клиента и отправит прибору. */
    val outgoing = ArrayDeque<ByteArray>()

    /** Всё, что «насос» передал клиенту из BLE. */
    val incoming = mutableListOf<ByteArray>()

    private var response: ByteArray? = null

    override fun createClient() {
        calls += "create"
        createCount++
        clientAlive = true
    }

    override fun destroyClient() {
        calls += "destroy"
        destroyCount++
        clientAlive = false
    }

    override fun pullOutgoingBytes(): ByteArray = outgoing.removeFirstOrNull() ?: ByteArray(0)

    override fun pushIncomingBytes(bytes: ByteArray) {
        incoming += bytes
    }

    override fun setNormalResponseModeStandard(
        sourceAddress: Int,
        logicalAddress: Int,
        physicalAddress: Int,
        maxInfoTransmit: Int,
        maxInfoReceive: Int,
    ): Boolean {
        calls += "snrm $sourceAddress/$logicalAddress/$physicalAddress"
        return snrmOk
    }

    override fun receiveUnnumberedAcknowledge(): Int {
        calls += "ua"
        return uaResult
    }

    override fun establishConnection(securityLevel: Int, password: String?): Boolean {
        calls += "aarq $securityLevel $password"
        return aarqOk
    }

    override fun getRequest(classId: Int, obisCode: String, attributeId: Int): Boolean {
        calls += "get $classId $obisCode $attributeId"
        response = if (getSends) onGet(classId, obisCode, attributeId) else null
        return getSends
    }

    override fun sendReadyToReceive(blockNumber: Int): Boolean {
        calls += "rr-block $blockNumber"
        response = if (nextBlockSends) onReadyToReceive(blockNumber) else null
        return nextBlockSends
    }

    override fun getRequestFlatAddress(classId: Int, obisCode: String, attributeId: Int, destAddress: Int, srcAddress: Int): Boolean {
        calls += "flat-get $classId $obisCode $attributeId"
        response = if (flatGetSends) onFlatGet(classId, obisCode, attributeId) else null
        return flatGetSends
    }

    override fun getNextBlockRequestFlatAddress(lastBlockNumber: Int): Boolean {
        calls += "flat-next-block $lastBlockNumber"
        response = if (nextBlockSends) onNextBlockFlat(lastBlockNumber) else null
        return nextBlockSends
    }

    override fun actionRequestFlatAddress(
        classId: Int,
        obisCode: String,
        methodId: Int,
        hasParameter: Boolean,
        parameterType: Int,
        parameterValue: Int,
        destAddress: Int,
        srcAddress: Int,
    ): Boolean {
        calls += "action $classId $obisCode method=$methodId"
        response = if (actionSends) onAction(methodId) else null
        return actionSends
    }

    /** Ответ отдаётся один раз: следующий запрос должен сформировать свой. */
    override fun getResponseRawBytes(): ByteArray {
        val r = response ?: return ByteArray(0)
        response = null
        return r
    }

    override fun lastErrorMessage(): String = error

    /** Сколько раз вызывали запрос с таким префиксом в [calls]. */
    fun count(prefix: String): Int = calls.count { it.startsWith(prefix) }
}
