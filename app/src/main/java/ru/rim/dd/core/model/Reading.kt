package ru.rim.dd.core.model

import java.time.Instant

/**
 * Одно показание энергии по OBIS-объекту (общая сумма или конкретный тариф).
 * obisId — идентификатор объекта DLMS, приходит из SpodesClientBridge.
 */
data class Reading(
    val obisId: String,
    val tariff: Int? = null,       // null — суммарное показание, 1..8 — номер тарифа (Т1..Т8)
    val valueKwh: Double,
    val timestamp: Instant,
)
