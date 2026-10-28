package ru.rim.dd.testutil

import ru.rim.dd.core.dlms.ObisReading
import ru.rim.dd.core.dlms.parseGetResponseValue
import ru.rim.dd.core.dlms.readingsOf

/**
 * Реальный ответ трёхфазного РиМ на GET буфера индикации 0.0.21.0.2.255: три HDLC-кадра.
 * Кадры собираются так же, как это делает приложение: из каждого берётся информационное поле,
 * поля склеиваются и разбираются целиком.
 */
object RealMeterBuffer {

    private val frames = listOf(
        "7E A8 89 43 03 F4 4F D9 E6 E7 00 C4 01 AC 00 01 01 02 16 0F 00 16 02 0F 84 09 01 01 06 31 49 0F 00 02 03 09 06 01 00 01 08 01 FF 06 00 00 00 AE 02 02 0F 01 16 1E 02 03 09 06 01 00 01 08 02 FF 06 00 00 00 00 02 02 0F 01 16 1E 02 03 09 06 01 00 01 08 03 FF 06 00 00 00 00 02 02 0F 01 16 1E 02 03 09 06 01 00 01 08 00 FF 06 00 00 00 AE 02 02 0F 01 16 1E 02 03 09 06 01 00 04 08 00 FF 06 00 00 00 29 02 02 0F 01 5E E7 7E",
        "7E A8 89 43 03 F6 5D FA 16 20 02 03 09 06 01 00 01 07 00 FF 05 00 00 00 00 02 02 0F FF 16 1B 02 03 09 06 01 00 15 07 00 FF 05 00 00 00 00 02 02 0F FF 16 1B 02 03 09 06 01 00 29 07 00 FF 05 00 00 00 00 02 02 0F FF 16 1B 02 03 09 06 01 00 3D 07 00 FF 05 00 00 00 00 02 02 0F FF 16 1B 02 03 09 06 01 00 1F 07 00 FF 06 00 00 00 00 02 02 0F FD 16 21 02 03 09 06 01 00 33 07 00 FF 06 00 00 00 00 02 02 0F FD 16 21 25 93 7E",
        "7E A0 83 43 03 F8 AD 95 02 03 09 06 01 00 01 08 00 65 06 00 00 00 AE 02 02 0F 01 16 1E 02 03 09 06 01 00 02 08 01 65 06 00 00 00 00 02 02 0F 01 16 1E 02 03 09 06 01 00 04 08 08 65 06 00 00 00 00 02 02 0F 01 16 20 02 03 09 06 01 00 04 08 00 65 06 00 00 00 29 02 02 0F 01 16 20 02 03 09 06 00 00 60 09 00 FF 12 0B 12 02 02 0F FE 16 09 02 03 09 06 00 00 60 06 03 FF 12 0E 35 02 02 0F FD 16 23 C9 62 7E"
    )

    /** Информационное поле HDLC-кадра: без флагов 7E, заголовка (формат, адреса, control, HCS) и FCS. */
    private fun infoField(frame: String): List<Byte> {
        val bytes = frame.split(" ").filter { it != "7E" }.map { it.toInt(16).toByte() }
        return bytes.subList(7, bytes.size - 2)
    }

    /** Полный ответ нативного клиента на GET буфера: информационные поля трёх кадров, склеенные подряд. */
    fun rawResponse(): ByteArray = frames.flatMap { infoField(it) }.toByteArray()

    fun readings(): List<ObisReading> = readingsOf(parseGetResponseValue(rawResponse()))

    /**
     * Сравнимое представление элементов буфера: у ObisReading поле с байтами (ByteArray), и обычное
     * равенство data class сравнивало бы массивы по ссылке.
     */
    fun describe(readings: List<ObisReading>): List<String> = readings.map {
        "${it.obisCode}|${it.rawValue}|${it.scaler}|${it.unit}|${it.octetValue?.toList()}|${it.textValue}|${it.floatValue}"
    }
}
