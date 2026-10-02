package ru.rim.dd.core.model

/** Значение параметра сети — общее и по фазам (для 3-фазных ПУ). */
data class PhaseValues(
    val total: Double,
    val l1: Double? = null,
    val l2: Double? = null,
    val l3: Double? = null,
) {
    val isThreePhase: Boolean get() = l1 != null && l2 != null && l3 != null
}

/**
 * Линейные (междуфазные) напряжения трёхфазного ПУ. Новые коды прошивки:
 * 1.0.124/125/126.7.0.255; прежние (legacyCodes = true) — 1.0.12.7.1/2/3.255.
 */
data class LineVoltages(
    val l12: Double?,
    val l23: Double?,
    val l13: Double?,
    val legacyCodes: Boolean = false,
)

/** Параметры сети с экрана «Сеть» (UC-05). */
data class NetworkParams(
    val voltage: PhaseValues,
    val current: PhaseValues,
    val power: PhaseValues,            // активная мощность, кВт (OBIS 1.0.1.7.0.255)
    val frequencyHz: Double? = null,   // опционально, если разрешено конфигурацией ПУ
    val cosPhi: PhaseValues? = null,   // опционально — см. DLMS_COS / L1/L2/L3_COS в прошивке
    val reactivePowerKvar: Double? = null, // OBIS 1.0.3.7.0.255
    val apparentPowerKva: Double? = null,  // OBIS 1.0.9.7.0.255
    val neutralCurrentA: Double? = null,   // OBIS 1.0.91.7.0.255
    val lineVoltages: LineVoltages? = null, // междуфазные напряжения (трёхфазные ПУ)
)
