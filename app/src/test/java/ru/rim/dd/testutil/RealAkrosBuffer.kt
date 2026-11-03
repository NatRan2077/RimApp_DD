package ru.rim.dd.testutil

import ru.rim.dd.core.dlms.ObisReading
import ru.rim.dd.core.dlms.parseGetResponseValue
import ru.rim.dd.core.dlms.readingsOf

/**
 * Реальный ответ AKROS на GET буфера индикации 0.0.21.0.2.255 по пути пульта («плоская» адресация,
 * без ассоциации): три HDLC-кадра из лога прибора (подключение 29.10.2026). В отличие от «компактного»
 * буфера AKROS с ассоциацией, здесь тот же формат, что у РиМ: позиционные поля и тройки
 * {OBIS, значение, scaler+unit}, только значения вещественные (Float32).
 */
object RealAkrosBuffer {

    private val frames = listOf(
        "7E A8 89 43 03 30 67 59 E6 E7 00 C4 01 81 00 01 01 02 13 0F 02 16 02 0F 80 09 01 08 06 11 40 A0 00 02 03 09 06 01 00 01 08 00 FF 17 4B F0 31 11 02 02 0F 00 16 1E 02 03 09 06 01 00 02 08 00 FF 17 48 22 A7 17 02 02 0F 00 16 1E 02 03 09 06 01 00 03 08 00 FF 17 48 A1 D7 21 02 02 0F 00 16 20 02 03 09 06 01 00 04 08 00 FF 17 47 67 2D 04 02 02 0F 00 16 20 02 03 09 06 01 00 0C 07 00 FF 17 00 00 00 00 02 02 0F 00 2B C7 7E",
        "7E A8 89 43 03 32 75 7A 16 23 02 03 09 06 01 00 0B 07 00 FF 17 00 00 00 00 02 02 0F 00 16 21 02 03 09 06 01 00 01 07 00 FF 17 00 00 00 00 02 02 0F 00 16 1B 02 03 09 06 01 00 03 07 00 FF 17 00 00 00 00 02 02 0F 00 16 1D 02 03 09 06 01 00 09 07 00 FF 17 00 00 00 00 02 02 0F 00 16 1C 02 03 09 06 01 00 0E 07 00 FF 17 00 00 00 00 02 02 0F 00 16 2C 02 09 09 06 00 00 01 00 00 FF 09 0C 07 EA 0A 1D 04 10 0E 0B 00 0E BB 7E",
        "7E A0 81 43 03 34 BB A0 FE 5C 02 10 FE 5C 11 00 09 0C 07 EA 02 1C 06 0A 09 00 63 00 00 00 09 0C 07 EA 08 1C 05 0A 09 00 63 00 00 00 0F 78 03 00 16 01 02 09 09 06 00 00 16 00 00 FF 16 06 11 01 11 01 12 00 80 12 00 80 12 00 19 12 00 78 12 00 10 02 09 09 06 00 01 16 00 00 FF 16 00 11 01 11 01 12 00 80 12 00 80 12 00 19 12 00 78 12 00 10 02 02 09 06 00 00 60 01 02 FF 09 04 31 2E 30 32 B4 2E 7E"
    )

    /** Информационное поле HDLC-кадра: без флагов 7E, заголовка (формат, адреса, control, HCS) и FCS. */
    private fun infoField(frame: String): List<Byte> {
        val bytes = frame.split(" ").filter { it != "7E" }.map { it.toInt(16).toByte() }
        return bytes.subList(7, bytes.size - 2)
    }

    /** Полный ответ нативного клиента: информационные поля трёх кадров, склеенные подряд. */
    fun rawResponse(): ByteArray = frames.flatMap { infoField(it) }.toByteArray()

    fun readings(): List<ObisReading> = readingsOf(parseGetResponseValue(rawResponse()))
}
