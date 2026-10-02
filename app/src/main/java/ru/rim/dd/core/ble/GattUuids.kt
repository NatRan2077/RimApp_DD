package ru.rim.dd.core.ble

import java.util.UUID

/**
 * Профиль «прозрачного UART» на приборе: сервис + характеристика на запись (телефон → прибор)
 * и характеристика с notify (прибор → телефон).
 */
data class MeterUartProfile(
    /** Короткое имя для логов — по нему в логе видно, каким набором UUID подключились. */
    val label: String,
    val service: UUID,
    /** NOTIFY: прибор → телефон */
    val txNotify: UUID,
    /** WRITE: телефон → прибор */
    val rxWrite: UUID,
)

/**
 * UUID GATT-сервиса «прозрачного UART» (аналог HM-10/JDY). Наборов два: FFE0/FFE9/FFE4 — из
 * прошивки пульта РиМ 040.40 (app_rdtsc.c), ABF0/ABF1/ABF2 — на счётчике AKROS (роли те же).
 * При подключении берётся первый набор, который прибор реально предоставляет
 * (isRequiredServiceSupported()); новая ревизия платы — одна строка в [ALL].
 */
object GattUuids {

    /** Основной набор — пульт и счётчики РиМ (подтверждён на РиМ 189.40/189.46). */
    val PROFILE_FFE0 = MeterUartProfile(
        label = "FFE0 (РиМ)",
        service = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb"),
        txNotify = UUID.fromString("0000ffe4-0000-1000-8000-00805f9b34fb"),
        rxWrite = UUID.fromString("0000ffe9-0000-1000-8000-00805f9b34fb"),
    )

    /** Альтернативная ревизия — подтверждена на счётчике AKROS. */
    val PROFILE_ABF0 = MeterUartProfile(
        label = "ABF0 (AKROS)",
        service = UUID.fromString("0000abf0-0000-1000-8000-00805f9b34fb"),
        txNotify = UUID.fromString("0000abf2-0000-1000-8000-00805f9b34fb"),
        rxWrite = UUID.fromString("0000abf1-0000-1000-8000-00805f9b34fb"),
    )

    /** Порядок перебора при подключении — сначала основной, затем альтернативные. */
    val ALL: List<MeterUartProfile> = listOf(PROFILE_FFE0, PROFILE_ABF0)

    /** Standard Client Characteristic Configuration Descriptor — для включения notify. */
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}
