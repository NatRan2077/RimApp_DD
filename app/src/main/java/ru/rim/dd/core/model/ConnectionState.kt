package ru.rim.dd.core.model

/** Состояние связи с ПУ; набор ошибок повторяет статусы прошивки пульта ДД (MSG_BLE_CONNECT_*, "НЕТ.ОТВ"). */
sealed class ConnectionState {
    data object Idle : ConnectionState()
    data object Scanning : ConnectionState()
    data class Connecting(val serialNumber: String) : ConnectionState()
    data class Connected(val serialNumber: String) : ConnectionState()
    data object Reconnecting : ConnectionState()

    sealed class Error : ConnectionState() {
        data object WrongPin : Error()           // MSG_BLE_CONNECT_ERROR_PINCODE
        data object NotFound : Error()           // MSG_BLE_CONNECT_NOT_FOUND / "НЕТ.ОТВ"
        data class Other(val message: String) : Error()
    }
}
