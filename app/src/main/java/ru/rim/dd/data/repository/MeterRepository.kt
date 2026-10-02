package ru.rim.dd.data.repository

import kotlinx.coroutines.flow.Flow
import ru.rim.dd.core.ble.BleDevice
import ru.rim.dd.core.model.ConnectionState
import ru.rim.dd.core.model.MeterInfo
import ru.rim.dd.core.model.NetworkParams
import ru.rim.dd.core.model.Reading
import ru.rim.dd.core.model.RelayState
import ru.rim.dd.core.model.TamperState

/**
 * Единственная точка доступа к данным ПУ для всего UI (ViewModel'и знают
 * только этот интерфейс — ни BLE, ни SpodesClient им не видны).
 */
interface MeterRepository {

    /**
     * Reconnecting эмитится после неожиданного разрыва, пока связь восстанавливается сама;
     * Idle (явный [disconnect]) — сигнал увести пользователя на экран «Подключение».
     */
    fun connectionState(): Flow<ConnectionState>

    /** RSSI активного соединения, дБм; null — соединения нет или чтения ещё не было. */
    fun signalStrengthDbm(): Flow<Int?>

    /** Закрывает соединение, не забывая прибор (в отличие от [forgetDevice]). */
    suspend fun disconnect()

    // ---- UC-01 / UC-02 ----
    fun scanDevices(): Flow<BleDevice>

    /**
     * [deviceName] — BLE-имя из скана: по DLMS без ассоциации модель и серийный номер не
     * прочитать, поэтому они разбираются из имени. При null (адрес введён вручную) остаётся прочерк.
     */
    suspend fun connectByAddress(address: String, pin: String, remember: Boolean, deviceName: String? = null)
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

    /** Пломбы, датчики, батарея и лимит мощности — из буфера индикации, см. [TamperState]. */
    fun tamperState(): Flow<TamperState>

    // ---- UC-10 ----
    fun history(serialNumber: String): Flow<List<Reading>>

    // ---- UC-12 ----
    suspend fun forgetDevice(serialNumber: String)
}
