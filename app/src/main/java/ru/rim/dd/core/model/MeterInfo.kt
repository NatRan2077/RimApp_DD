package ru.rim.dd.core.model

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

data class MeterInfo(
    val model: String,             // напр. "РиМ 189.46"
    val serialNumber: String,
    val firmwareVersion: String,
    val lastSeenAt: Instant? = null,
    // Статусные OBIS 0.0.96.x и часы устройства. Одни приборы присылают время и дату отдельными
    // объектами (0.0.0.9.1.255 время, 0.0.0.9.2.255 дата — бывает и только один из них), современные —
    // одним объектом 0.0.1.0.0.255 (date-time). Дата и время хранятся раздельно, вместе с OBIS-источником.
    val temperatureC: Double? = null,      // OBIS 0.0.96.9.0.255 — внутренняя температура прибора
    val backupVoltageV: Double? = null,    // OBIS 0.0.96.6.3.255 — напряжение резервного питания
    val deviceDate: LocalDate? = null,      // дата самого счётчика (не телефона!)
    val deviceDateObis: String? = null,     // откуда взята дата: 0.0.0.9.2.255 или 0.0.1.0.0.255
    val deviceTime: LocalTime? = null,      // время самого счётчика
    val deviceTimeObis: String? = null,     // откуда взято время: 0.0.0.9.1.255 или 0.0.1.0.0.255
    val currentTariff: Int? = null,        // OBIS 0.0.96.14.0.255 — активный тариф прямо сейчас
    // null — пока не пришёл ни один успешный цикл.
    val clockValid: Boolean? = null,       // статус часов (0.0.1.0.0.255) — достоверность часов прибора
    // OBIS-источник модели и версии ПО (для подписи на экране «Инфо»). null — значение не из
    // буфера прибора: модель могла прийти из разбора BLE-имени, где OBIS-кода нет. У серийного
    // номера такого поля нет намеренно — он всегда берётся из имени BLE-устройства.
    val modelObis: String? = null,
    val firmwareVersionObis: String? = null,
    // Индикаторы экрана «Показания»: счётчики HDLC-кадров (0.0.22.0.0.255 — P1, 0.1.22.0.0.255 — P2)
    // и время работы (0.0.96.8.0.255). null — поля нет в буфере прибора.
    val hdlcP1: String? = null,
    val hdlcP2: String? = null,
    val hdlcP3: String? = null,               // 0.2.22.0.0.255
    val hdlcP4: String? = null,               // 0.3.22.0.0.255
    val operatingTime: Double? = null,      // значение объекта 0.0.96.8.0.255 (с учётом scaler)
    val operatingTimeUnit: String? = null,  // единица, если прибор её сообщил (напр. «ч», «сут»)
    // Поля буфера, расшифрованные как в прошивке пульта (см. GridStatus.kt):
    val rdchDateTime: LocalDateTime? = null,       // расчётный день: 0.0.9.2.101 (дата) + 0.0.9.1.101 (время)
    val phaseStatus: PhaseStatus? = null,          // статус фаз (1.0.96.50.0.255)
    val energyDirection: EnergyDirection? = null,  // направление энергии (байт 3 «значков ДД»)
    // OBIS серийного номера (0.0.96.1.0.255), если номер пришёл в буфере; null — взят из BLE-имени.
    val serialNumberObis: String? = null,
)

data class PairedDevice(
    val serialNumber: String,
    val alias: String? = null,
    val bleAddress: String? = null,
    val lastConnectedAt: Instant? = null,
)
