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

/** Параметры сети с экрана «Сеть» (UC-05). */
data class NetworkParams(
    val voltage: PhaseValues,
    val current: PhaseValues,
    val power: PhaseValues,
    val frequencyHz: Double? = null,   // опционально, если разрешено конфигурацией ПУ
    val cosPhi: PhaseValues? = null,   // опционально — см. DLMS_COS / L1/L2/L3_COS в прошивке
)
