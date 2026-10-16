package ru.rim.dd.core.model

enum class MeterProtocolProfile {
    RIM_FLAT,
    AKROS_STANDARD;

    companion object {

        /**
         * Определяет диалект по BLE-имени (известно ещё до подключения). Всё, что не опознано
         * как AKROS, считается РиМ — поведение для уже работающих приборов не меняется.
         */
        fun forDeviceName(name: String?): MeterProtocolProfile =
            if (name?.contains(AKROS_NAME_MARKER, ignoreCase = true) == true) AKROS_STANDARD else RIM_FLAT
        const val AKROS_NAME_MARKER = "AKROS"
    }
}
