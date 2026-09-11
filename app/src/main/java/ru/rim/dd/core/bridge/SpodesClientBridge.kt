package ru.rim.dd.core.bridge

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Тонкая Kotlin-обёртка над нативной библиотекой libspodesclient.so
 * (см. app/src/main/cpp — исходники SpodesClient + spodes_jni_bridge.cpp).
 *
 * Этот класс НЕ реализует протокол — он лишь передаёт вызовы в C++ и
 * получает типизированные результаты обратно. Вся сложная логика
 * (HDLC, AARQ/AARE, GET/SET/ACTION, разбор DLMS-структур) — в native-коде,
 * см. SpodesClient_rasshifrovka.txt.
 *
 * Транспорт (сырые байты) сюда передаёт MeterRepositoryImpl, забирая их
 * у MeterBleClient — сам мост тоже не знает о Bluetooth.
 *
 * ВАЖНО (контракт асинхронности, см. spodes_jni_bridge.cpp): establishConnection()
 * и getRequest() — блокирующие вызовы (нативный код ждёт ответ через ReadData
 * с таймаутом), поэтому их нужно вызывать на Dispatchers.IO. Пока они блокируются,
 * КТО-ТО ДРУГОЙ должен параллельно: (а) забирать pullOutgoingBytes() и слать их в
 * MeterBleClient.writeBytes(), (б) пересылать каждый пакет из
 * MeterBleClient.incomingBytes() в pushIncomingBytes(). Эту связку поднимает
 * MeterRepositoryImpl.startTransportPump() при подключении.
 */
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

    /** Байты, которые нужно отправить устройству (после nativeConnect/nativeGetRequest). */
    fun pullOutgoingBytes(): ByteArray = nativePullOutgoing(nativeHandle)

    /** Передать байты, полученные по BLE-notify, обратно в библиотеку на разбор. */
    fun pushIncomingBytes(bytes: ByteArray) = nativePushIncoming(nativeHandle, bytes)

    /**
     * Открытие логического HDLC-канала (SNRM) — обязательный шаг ПЕРЕД establishConnection():
     * это тот же уровень протокола, что кадры "0x7E ... 0x7E" в EstablishConnectionRequest, и
     * пока канал не открыт SNRM/UA, устройство просто игнорирует AARQ.
     * sourceAddress — адрес клиента: должен быть 16 (Public client) при securityLevel == 0,
     * согласно проверке в SpodesHandler::FormConnectionRequest — иное значение здесь
     * не проверено на реальном приборе, см. открытые вопросы в ТЗ.
     */
    fun setNormalResponseMode(sourceAddress: Int, logicalAddress: Int, physicalAddress: Int): Boolean =
        nativeSetNormalResponseMode(nativeHandle, sourceAddress, logicalAddress, physicalAddress)

    /** Ждёт UA-ответ на SNRM. 0 — ошибка/таймаут, 1 — успех, 2 — сервер уже разорвал связь (DM). */
    fun receiveUnnumberedAcknowledge(): Int = nativeReceiveUnnumberedAcknowledge(nativeHandle)

    /**
     * Установление СПОДЭС-сессии (AARQ). securityLevel/password — из паспорта ПУ
     * или конфигуратора, см. открытые вопросы в ТЗ. Вызывать только после успешного
     * setNormalResponseMode()+receiveUnnumberedAcknowledge()==1.
     */
    fun establishConnection(securityLevel: Int, password: String?): Boolean =
        nativeEstablishConnection(nativeHandle, securityLevel, password)

    /**
     * [Android-патч] AARQ с "плоской" однобайтовой HDLC-адресацией — как и getRequestFlatAddress(),
     * нужен потому, что обычный establishConnection() пишет двухбайтовый адрес через
     * FormHDLCHeader(), а этот счётчик такие кадры молча игнорирует. Проверено диагностикой:
     * ЛЮБОЙ GET, кроме самого первого "публичного" объекта (0.0.21.0.2.255), получает
     * data-access-result "object unavailable" — в том числе Association LN (0.0.40.0.0.255),
     * который обязан существовать на любом DLMS-сервере. Похоже, без установленной прикладной
     * ассоциации счётчик отдаёт только этот один объект, а остальные требуют AARQ/AARE,
     * которые наш клиент раньше пропускал целиком. securityLevel=0/password=null — нижайший
     * уровень (без пароля), пробуем его первым.
     */
    fun establishConnectionFlatAddress(securityLevel: Int, password: String?, destAddress: Int, srcAddress: Int): Boolean =
        nativeEstablishConnectionFlatAddress(nativeHandle, securityLevel, password, destAddress, srcAddress)

    /** [Android-патч] "Сырые" байты ответа на establishConnectionFlatAddress() (AARE), см. getResponseRawBytes(). */
    fun establishConnectionResponseRawBytes(): ByteArray = nativeEstablishConnectionResponseRaw(nativeHandle)

    /** Запрос чтения атрибута (сервис GET). obisCode — "1.0.1.8.0.255" и т.п. из ObisCatalog. */
    fun getRequest(classId: Int, obisCode: String, attributeId: Int): Boolean =
        nativeGetRequest(nativeHandle, classId, obisCode, attributeId)

    /**
     * [Android-патч] GET-запрос с "плоской" однобайтовой HDLC-адресацией и
     * invoke-id-and-priority=0x81 (без service_class) — именно так собирает кадры
     * штатная прошивка пульта РиМ 040.40 при обращении к этому конкретному счётчику.
     * Обычный getRequest() пишет двухбайтовый logical+physical адрес и invoke-id 0xC1 —
     * этот счётчик такие кадры молча игнорирует. destAddress/srcAddress — уже готовые
     * (не сдвинутые) байты HDLC-адресов счётчика и клиента, см. GetRequestFlatAddress
     * в spodes_client.h/.cpp.
     */
    fun getRequestFlatAddress(classId: Int, obisCode: String, attributeId: Int, destAddress: Int, srcAddress: Int): Boolean =
        nativeGetRequestFlatAddress(nativeHandle, classId, obisCode, attributeId, destAddress, srcAddress)

    /**
     * [Android-патч] ACTION-запрос (управление объектом — напр. включение/отключение реле,
     * класс Disconnect Control) с той же "плоской" адресацией, что и getRequestFlatAddress().
     * methodId — номер метода (для Disconnect Control: 1=remote_disconnect, 2=remote_reconnect).
     * hasParameter/parameterType/parameterValue — параметр метода, если он есть: у метода 2
     * ПОДТВЕРЖДЕНО реальным логом пульта РиМ 040.40 (тип 0x0F=Integer8, значение 0) — см.
     * turnRelayOn()/turnRelayOff() в MeterRepositoryImpl.kt.
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
     * [Android-патч] "Сырые" байты одного полного ответа сервера (с флага 0x7E до флага
     * 0x7E), без разбора APDU библиотечными GetResponseXxx (см. GetResponseRaw() в
     * spodes_client.h/.cpp — те завязаны на смещения для двухбайтовой HDLC-адресации и
     * скалярные типы данных, здесь же ответ на GetRequestFlatAddress() приходит с
     * однобайтовым адресом и представляет собой вложенную DLMS-структуру/массив).
     * Разбор — в ru.rim.dd.core.dlms (DlmsDecoder/parseGetResponseTariffs), см.
     * MeterRepositoryImpl.
     */
    fun getResponseRawBytes(): ByteArray = nativeGetResponseRaw(nativeHandle)

    fun getResponseFloat(): Double = nativeGetResponseFloat(nativeHandle)
    fun getResponseInt(): Long = nativeGetResponseInt(nativeHandle)

    fun lastErrorMessage(): String = nativeGetErrorMessage(nativeHandle)

    // ---- external-функции: реализация в spodes_jni_bridge.cpp ----
    private external fun nativeCreate(): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativePullOutgoing(handle: Long): ByteArray
    private external fun nativePushIncoming(handle: Long, bytes: ByteArray)
    private external fun nativeSetNormalResponseMode(handle: Long, sourceAddress: Int, logicalAddress: Int, physicalAddress: Int): Boolean
    private external fun nativeReceiveUnnumberedAcknowledge(handle: Long): Int
    private external fun nativeEstablishConnection(handle: Long, securityLevel: Int, password: String?): Boolean
    private external fun nativeEstablishConnectionFlatAddress(handle: Long, securityLevel: Int, password: String?, destAddress: Int, srcAddress: Int): Boolean
    private external fun nativeEstablishConnectionResponseRaw(handle: Long): ByteArray
    private external fun nativeGetRequest(handle: Long, classId: Int, obisCode: String, attributeId: Int): Boolean
    private external fun nativeGetRequestFlatAddress(handle: Long, classId: Int, obisCode: String, attributeId: Int, destAddress: Int, srcAddress: Int): Boolean
    private external fun nativeActionRequestFlatAddress(
        handle: Long, classId: Int, obisCode: String, methodId: Int, hasParameter: Boolean,
        parameterType: Int, parameterValue: Int, destAddress: Int, srcAddress: Int,
    ): Boolean
    private external fun nativeGetResponseRaw(handle: Long): ByteArray
    private external fun nativeGetResponseFloat(handle: Long): Double
    private external fun nativeGetResponseInt(handle: Long): Long
    private external fun nativeGetErrorMessage(handle: Long): String

    companion object {
        init {
            System.loadLibrary("spodesclient_bridge")
        }
    }
}
