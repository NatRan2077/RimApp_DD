package ru.rim.dd.core.model

/** Причина текущего состояния реле — влияет на то, что можно предложить пользователю (UC-08). */
enum class RelaySource {
    MANUAL,          // включено/отключено вручную
    POWER_LIMIT,      // отключено автоматически по превышению УПМк
    REMOTE_CONTROL,   // отключено удалённо центром АС (например, за неуплату)
    /**
     * [Android-патч] Реальное состояние control_state прочитано из буфера индикации
     * (0.0.21.0.2.255) — см. RELAY_CONTROL_STATE_OBIS в GetResponseParser.kt и
     * applyDecodedBuffer() в MeterRepositoryImpl.kt. Это САМЫЙ достоверный источник (то же
     * значение, которым руководствуется сам пульт РиМ 040.40 для своей индикации) — приходит
     * каждый цикл автообновления без отдельного запроса и без требования DLMS-ассоциации.
     */
    METER_READ,
    UNKNOWN,
}

data class RelayState(
    val isOn: Boolean,
    val powerLimitKw: Double,
    val source: RelaySource,
    /** Есть ли разрешение центра АС на включение (аналог "мигающей стрелки" на пульте ДД). */
    val remoteTurnOnAllowed: Boolean = false,
)
