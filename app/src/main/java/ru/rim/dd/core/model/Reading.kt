package ru.rim.dd.core.model

import java.time.Instant

/**
 * Категория энергии по 3-й группе OBIS-кода (A.B.C.D.E.F): C=1 — активная (потребление),
 * C=2 — активная (отдача в сеть), C=3 — реактивная Q1, C=4 — реактивная Q4. Подтверждено
 * реальным буфером счётчика (см. историю диагностики — коды 1.0.1.8.x / 1.0.2.8.x /
 * 1.0.3.8.x / 1.0.4.8.x).
 */
enum class EnergyCategory { ACTIVE_IMPORT, ACTIVE_EXPORT, REACTIVE_Q1, REACTIVE_Q4, OTHER }

/**
 * Одно показание энергии по OBIS-объекту (общая сумма или конкретный тариф).
 * obisId — идентификатор объекта DLMS, приходит из SpodesClientBridge.
 */
data class Reading(
    val obisId: String,
    val tariff: Int? = null,       // null — суммарное показание, 1..8 — номер тарифа (Т1..Т8)
    val valueKwh: Double,          // кВт·ч для ACTIVE_*, квар·ч для REACTIVE_* — см. category
    val timestamp: Instant,
    /**
     * [Android-патч] Единица измерения, которую сообщил САМ прибор (из scaler_unit объекта),
     * уже приведённая к «килo»-виду: «кВт·ч», «квар·ч» и т.п. null — прибор её не сообщил
     * (так ведут себя приборы РиМ: у них в буфере единица приходит кодом рядом со значением,
     * но до этой модели не доходит), тогда экран подставляет подпись по категории энергии.
     *
     * Зачем: у AKROS единицу приходится спрашивать отдельно (атрибут scaler_unit), и подставлять
     * вместо неё «кВт·ч по умолчанию» было бы догадкой — на другом приборе той же линейки
     * величина может прийти в других единицах, и подпись молча соврала бы.
     */
    val unitLabel: String? = null,
) {
    val category: EnergyCategory get() = when (obisId.split(".").getOrNull(2)?.toIntOrNull()) {
        1 -> EnergyCategory.ACTIVE_IMPORT
        2 -> EnergyCategory.ACTIVE_EXPORT
        3 -> EnergyCategory.REACTIVE_Q1
        4 -> EnergyCategory.REACTIVE_Q4
        else -> EnergyCategory.OTHER
    }
}
