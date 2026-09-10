package ru.rim.dd.core.model

import java.time.Instant
import java.time.LocalDateTime

data class MeterInfo(
    val model: String,             // напр. "РиМ 189.46"
    val serialNumber: String,
    val firmwareVersion: String,
    val signalLevelDbm: Int? = null,
    val lastSeenAt: Instant? = null,
    // Найдены в реальном буфере счётчика (см. историю диагностики) — статусные OBIS 0.0.96.x
    // и служебные часы устройства (0.0.0.9.1.255 время / 0.0.0.9.2.255 дата).
    val temperatureC: Double? = null,      // OBIS 0.0.96.9.0.255 — внутренняя температура прибора
    val backupVoltageV: Double? = null,    // OBIS 0.0.96.6.3.255 — напряжение резервного питания
    val deviceClock: LocalDateTime? = null, // часы самого счётчика (не время телефона!)
)

data class PairedDevice(
    val serialNumber: String,
    val alias: String? = null,
    val bleAddress: String? = null,
    /** PIN НИКОГДА не хранится тут в открытом виде — только в DeviceStore (Keystore/EncryptedSharedPreferences). */
    val lastConnectedAt: Instant? = null,
)
