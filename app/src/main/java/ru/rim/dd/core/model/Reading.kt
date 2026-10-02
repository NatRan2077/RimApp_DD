package ru.rim.dd.core.model

import java.time.Instant
enum class EnergyCategory { ACTIVE_IMPORT, ACTIVE_EXPORT, REACTIVE_Q1, REACTIVE_Q4, OTHER }

data class Reading(
    val obisId: String,
    val tariff: Int? = null,       // null — суммарное показание, 1..8 — номер тарифа (Т1..Т8)
    val valueKwh: Double,          // кВт·ч для ACTIVE_*, квар·ч для REACTIVE_* — см. category
    val timestamp: Instant,
    val unitLabel: String? = null,
    val isRdch: Boolean = false,
) {
    val category: EnergyCategory get() = when (obisId.split(".").getOrNull(2)?.toIntOrNull()) {
        1 -> EnergyCategory.ACTIVE_IMPORT
        2 -> EnergyCategory.ACTIVE_EXPORT
        3 -> EnergyCategory.REACTIVE_Q1
        4 -> EnergyCategory.REACTIVE_Q4
        else -> EnergyCategory.OTHER
    }
}
