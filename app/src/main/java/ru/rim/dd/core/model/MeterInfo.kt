package ru.rim.dd.core.model

import java.time.Instant

data class MeterInfo(
    val model: String,             // напр. "РиМ 189.46"
    val serialNumber: String,
    val firmwareVersion: String,
    val signalLevelDbm: Int? = null,
    val lastSeenAt: Instant? = null,
)

data class PairedDevice(
    val serialNumber: String,
    val alias: String? = null,
    val bleAddress: String? = null,
    /** PIN НИКОГДА не хранится тут в открытом виде — только в DeviceStore (Keystore/EncryptedSharedPreferences). */
    val lastConnectedAt: Instant? = null,
)
