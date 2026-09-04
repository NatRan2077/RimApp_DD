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
     * Установление СПОДЭС-сессии (AARQ). securityLevel/password — из паспорта ПУ
     * или конфигуратора, см. открытые вопросы в ТЗ.
     */
    fun establishConnection(securityLevel: Int, password: String?): Boolean =
        nativeEstablishConnection(nativeHandle, securityLevel, password)

    /** Запрос чтения атрибута (сервис GET). obisCode — "1.0.1.8.0.255" и т.п. из ObisCatalog. */
    fun getRequest(classId: Int, obisCode: String, attributeId: Int): Boolean =
        nativeGetRequest(nativeHandle, classId, obisCode, attributeId)

    fun getResponseFloat(): Double = nativeGetResponseFloat(nativeHandle)
    fun getResponseInt(): Long = nativeGetResponseInt(nativeHandle)

    fun lastErrorMessage(): String = nativeGetErrorMessage(nativeHandle)

    // ---- external-функции: реализация в spodes_jni_bridge.cpp ----
    private external fun nativeCreate(): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativePullOutgoing(handle: Long): ByteArray
    private external fun nativePushIncoming(handle: Long, bytes: ByteArray)
    private external fun nativeEstablishConnection(handle: Long, securityLevel: Int, password: String?): Boolean
    private external fun nativeGetRequest(handle: Long, classId: Int, obisCode: String, attributeId: Int): Boolean
    private external fun nativeGetResponseFloat(handle: Long): Double
    private external fun nativeGetResponseInt(handle: Long): Long
    private external fun nativeGetErrorMessage(handle: Long): String

    companion object {
        init {
            System.loadLibrary("spodesclient_bridge")
        }
    }
}
