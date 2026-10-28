package ru.rim.dd.core.ble

/** Найденное при сканировании устройство. */
data class BleDevice(
    val address: String,
    val name: String?,
    val rssi: Int,
)
