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
    val power: PhaseValues,            // активная мощность, кВт (OBIS 1.0.1.7.0.255)
    val frequencyHz: Double? = null,   // опционально, если разрешено конфигурацией ПУ
    val cosPhi: PhaseValues? = null,   // опционально — см. DLMS_COS / L1/L2/L3_COS в прошивке
    // Ниже — реально найденные в буфере счётчика мгновенные величины (см. историю диагностики),
    // которых не было в исходной модели: реактивная/полная мощность и ток нейтрали.
    val reactivePowerKvar: Double? = null, // OBIS 1.0.3.7.0.255
    val apparentPowerKva: Double? = null,  // OBIS 1.0.9.7.0.255
    val neutralCurrentA: Double? = null,   // OBIS 1.0.91.7.0.255
)
