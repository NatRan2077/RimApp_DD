package ru.rim.dd.core.ble

import java.util.UUID

/**
 * UUID кастомного GATT-сервиса "прозрачного UART" (аналог HM-10/JDY),
 * найденные в прошивке пульта ДД РиМ 040.40 (src/app_profile/app_rdtsc.c).
 *
 * ВАЖНО: в прошивке есть закомментированный альтернативный набор
 * (…ABF0/ABF1/ABF2) от более ранней ревизии платы — перед первым реальным
 * подключением обязательно сверить сканированием актуального устройства.
 */
object GattUuids {
    val SERVICE: UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")

    /** NOTIFY: прибор → телефон */
    val CHAR_TX_NOTIFY: UUID = UUID.fromString("0000ffe4-0000-1000-8000-00805f9b34fb")

    /** WRITE: телефон → прибор */
    val CHAR_RX_WRITE: UUID = UUID.fromString("0000ffe9-0000-1000-8000-00805f9b34fb")

    /** Standard Client Characteristic Configuration Descriptor — для включения notify. */
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}
