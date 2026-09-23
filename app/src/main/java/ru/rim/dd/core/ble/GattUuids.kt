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
 * UUID кастомного GATT-сервиса «прозрачного UART» (аналог HM-10/JDY).
 *
 * [Android-патч] ПОДДЕРЖИВАЮТСЯ ДВА НАБОРА. Исходно был зашит только FFE0/FFE9/FFE4 — они взяты
 * из прошивки пульта РиМ 040.40 (src/app_profile/app_rdtsc.c). Там же, строками ниже, лежал
 * ЗАКОММЕНТИРОВАННЫЙ альтернативный набор ABF0/ABF1/ABF2, и в комментарии к этому файлу стояло
 * предупреждение: «перед первым реальным подключением обязательно сверить сканированием
 * актуального устройства».
 *
 * Предупреждение оказалось пророческим: счётчик AKROS (тоже прибор РиМ) выставляет в эфир именно
 * ABF0 — nRF Connect показал сервис 0000abf0 с характеристиками abf1 [R WNR] (запись) и
 * abf2 [N R] + дескриптор 0x2902 (notify). Соответствие ролей ровно то же, что в прошивке:
 * abf0 ↔ ffe0, abf1 ↔ ffe9 (write), abf2 ↔ ffe4 (notify). Приложение же искало только FFE0, не
 * находило его и САМО отключалось — в логе это выглядело как «onDeviceDisconnected: reason=4»
 * (REASON_NOT_SUPPORTED у Nordic), то есть связь не рвалась, а прибор отвергался нашим же кодом.
 *
 * Поэтому набор больше не одна константа, а список: при подключении перебираем все известные и
 * берём первый, который прибор реально предоставляет (см. isRequiredServiceSupported()).
 * Добавить ещё одну ревизию платы — это одна строка в [ALL], без правок логики.
 */
object GattUuids {

    /** Основной набор — пульт и счётчики РиМ (подтверждён на РиМ 189.40/189.46). */
    val PROFILE_FFE0 = MeterUartProfile(
        label = "FFE0 (РиМ)",
        service = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb"),
        txNotify = UUID.fromString("0000ffe4-0000-1000-8000-00805f9b34fb"),
        rxWrite = UUID.fromString("0000ffe9-0000-1000-8000-00805f9b34fb"),
    )

    /** Альтернативная ревизия — подтверждена на счётчике AKROS (см. комментарий выше). */
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
