package ru.rim.dd.data.repository

import kotlinx.coroutines.flow.Flow
import ru.rim.dd.core.ble.BleDevice
import ru.rim.dd.core.model.ConnectionState
import ru.rim.dd.core.model.MeterInfo
import ru.rim.dd.core.model.NetworkParams
import ru.rim.dd.core.model.Reading
import ru.rim.dd.core.model.RelayState

/**
 * Единственная точка доступа к данным ПУ для всего UI (ViewModel'и знают
 * только этот интерфейс — ни BLE, ни SpodesClient им не видны).
 */
interface MeterRepository {

    fun connectionState(): Flow<ConnectionState>

    // ---- UC-01 / UC-02 ----
    fun scanDevices(): Flow<BleDevice>
    suspend fun connectByAddress(address: String, pin: String, remember: Boolean)
    suspend fun connectBySerialNumber(serialNumber: String, pin: String, remember: Boolean)

    // ---- UC-03 / UC-04 ----
    fun readings(): Flow<List<Reading>>
    suspend fun refreshReadings()

    // ---- UC-05 ----
    fun networkParams(): Flow<NetworkParams>

    // ---- UC-06 ----
    fun meterInfo(): Flow<MeterInfo>

    // ---- UC-07 / UC-08 ----
    fun relayState(): Flow<RelayState>
    suspend fun turnRelayOn()
    suspend fun turnRelayOff()

    // ---- UC-10 ----
    fun history(serialNumber: String): Flow<List<Reading>>

    // ---- UC-12 ----
    suspend fun forgetDevice(serialNumber: String)
}
