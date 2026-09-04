package ru.rim.dd.core.model

/** Причина текущего состояния реле — влияет на то, что можно предложить пользователю (UC-08). */
enum class RelaySource {
    MANUAL,          // включено/отключено вручную
    POWER_LIMIT,      // отключено автоматически по превышению УПМк
    REMOTE_CONTROL,   // отключено удалённо центром АС (например, за неуплату)
    UNKNOWN,
}

data class RelayState(
    val isOn: Boolean,
    val powerLimitKw: Double,
    val source: RelaySource,
    /** Есть ли разрешение центра АС на включение (аналог "мигающей стрелки" на пульте ДД). */
    val remoteTurnOnAllowed: Boolean = false,
)
