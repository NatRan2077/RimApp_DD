package ru.rim.dd.core.bridge

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SpodesClientBridge @Inject constructor() {

    /** Указатель на нативный объект SpodesClient (opaque handle из C API). 0 — не создан. */
    private var nativeHandle: Long = 0L

    fun createClient() {
        nativeHandle = nativeCreate()
    }

    fun destroyClient() {
        if (nativeHandle != 0L) {
            nativeDestroy(nativeHandle)
            nativeHandle = 0L
        }
    }

    /** Байты, которые нужно отправить устройству. */
    fun pullOutgoingBytes(): ByteArray = nativePullOutgoing(nativeHandle)

    /** Передать байты, полученные по BLE-notify, обратно в библиотеку на разбор. */
    fun pushIncomingBytes(bytes: ByteArray) = nativePushIncoming(nativeHandle, bytes)

    /** SNRM для РиМ. Библиотека принимает sourceAddress только 16/32/48. */
    fun setNormalResponseMode(sourceAddress: Int, logicalAddress: Int, physicalAddress: Int): Boolean =
        nativeSetNormalResponseMode(nativeHandle, sourceAddress, logicalAddress, physicalAddress)

    /**
     * Корректный SNRM (с согласованием max-info) для AKROS. Вариант без Standard шлёт кадр
     * с лишним LLC-заголовком и неверной FCS: РиМ это не замечает, AKROS такой кадр отбрасывает.
     */
    fun setNormalResponseModeStandard(
        sourceAddress: Int,
        logicalAddress: Int,
        physicalAddress: Int,
        maxInfoTransmit: Int = 128,
        maxInfoReceive: Int = 128,
    ): Boolean = nativeSetNormalResponseModeStandard(
        nativeHandle,
        sourceAddress,
        logicalAddress,
        physicalAddress,
        maxInfoTransmit,
        maxInfoReceive,
    )

    /** Запрос следующего блока при блочной передаче ответа. */
    fun sendReadyToReceive(blockNumber: Int): Boolean =
        nativeSendReadyToReceive(nativeHandle, blockNumber)

    /** Ожидание ответа на SNRM: 1 — UA, 2 — DM, 0 — ошибка/таймаут. */
    fun receiveUnnumberedAcknowledge(): Int = nativeReceiveUnnumberedAcknowledge(nativeHandle)

    /** AARQ; вызывать после успешного UA. */
    fun establishConnection(securityLevel: Int, password: String?): Boolean =
        nativeEstablishConnection(nativeHandle, securityLevel, password)

    /** GET атрибута со стандартной адресацией (AKROS). */
    fun getRequest(classId: Int, obisCode: String, attributeId: Int): Boolean =
        nativeGetRequest(nativeHandle, classId, obisCode, attributeId)

    /**
     * GET с «плоской» однобайтовой HDLC-адресацией и invoke-id 0x81 — так обращается к
     * счётчику штатный пульт РиМ. Обычный [getRequest] пишет двухбайтовый адрес и invoke-id 0xC1;
     * такие кадры РиМ молча игнорирует. destAddress/srcAddress — готовые (несдвинутые) байты.
     */
    fun getRequestFlatAddress(classId: Int, obisCode: String, attributeId: Int, destAddress: Int, srcAddress: Int): Boolean =
        nativeGetRequestFlatAddress(nativeHandle, classId, obisCode, attributeId, destAddress, srcAddress)

    /**
     * ACTION с той же «плоской» адресацией. Для Disconnect Control methodId: 1 — remote_disconnect,
     * 2 — remote_reconnect (у него параметр Integer8 = 0, подтверждено логом пульта).
     */
    fun actionRequestFlatAddress(
        classId: Int,
        obisCode: String,
        methodId: Int,
        hasParameter: Boolean,
        parameterType: Int,
        parameterValue: Int,
        destAddress: Int,
        srcAddress: Int,
    ): Boolean = nativeActionRequestFlatAddress(
        nativeHandle, classId, obisCode, methodId, hasParameter, parameterType, parameterValue, destAddress, srcAddress,
    )

    /**
     * Сырые байты полного ответа сервера (от флага 0x7E до 0x7E) без библиотечного разбора APDU,
     * который завязан на двухбайтовую адресацию. Сегментированный ответ РиМ дочитывается на
     * нативной стороне; разбор — в `ru.rim.dd.core.dlms`.
     */
    fun getResponseRawBytes(): ByteArray = nativeGetResponseRaw(nativeHandle)

    fun lastErrorMessage(): String = nativeGetErrorMessage(nativeHandle)

    // Реализация — в spodes_jni_bridge.cpp.
    private external fun nativeCreate(): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativePullOutgoing(handle: Long): ByteArray
    private external fun nativePushIncoming(handle: Long, bytes: ByteArray)
    private external fun nativeSetNormalResponseMode(handle: Long, sourceAddress: Int, logicalAddress: Int, physicalAddress: Int): Boolean
    private external fun nativeSetNormalResponseModeStandard(
        handle: Long,
        sourceAddress: Int,
        logicalAddress: Int,
        physicalAddress: Int,
        maxInfoTransmit: Int,
        maxInfoReceive: Int,
    ): Boolean
    private external fun nativeReceiveUnnumberedAcknowledge(handle: Long): Int
    private external fun nativeSendReadyToReceive(handle: Long, blockNumber: Int): Boolean
    private external fun nativeEstablishConnection(handle: Long, securityLevel: Int, password: String?): Boolean
    private external fun nativeGetRequest(handle: Long, classId: Int, obisCode: String, attributeId: Int): Boolean
    private external fun nativeGetRequestFlatAddress(handle: Long, classId: Int, obisCode: String, attributeId: Int, destAddress: Int, srcAddress: Int): Boolean
    private external fun nativeActionRequestFlatAddress(
        handle: Long, classId: Int, obisCode: String, methodId: Int, hasParameter: Boolean,
        parameterType: Int, parameterValue: Int, destAddress: Int, srcAddress: Int,
    ): Boolean
    private external fun nativeGetResponseRaw(handle: Long): ByteArray
    private external fun nativeGetErrorMessage(handle: Long): String

    companion object {
        init {
            System.loadLibrary("spodesclient_bridge")
        }
    }
}
