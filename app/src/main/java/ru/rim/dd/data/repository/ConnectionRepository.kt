package ru.rim.dd.data.repository

import kotlinx.coroutines.flow.Flow
import ru.rim.dd.core.ble.BleDevice
import ru.rim.dd.core.model.ConnectionState

/** Поиск, подключение и отключение прибора (UC-01 / UC-02 / UC-12). */
interface ConnectionRepository {

    /**
     * Reconnecting эмитится после неожиданного разрыва, пока связь восстанавливается сама;
     * Idle (явный [disconnect]) — сигнал увести пользователя на экран «Подключение».
     */
    fun connectionState(): Flow<ConnectionState>

    /** RSSI активного соединения, дБм; null — соединения нет или чтения ещё не было. */
    fun signalStrengthDbm(): Flow<Int?>

    fun scanDevices(): Flow<BleDevice>

    /**
     * [deviceName] — BLE-имя из скана: по DLMS без ассоциации модель и серийный номер не
     * прочитать, поэтому они разбираются из имени. При null (адрес введён вручную) остаётся прочерк.
     */
    suspend fun connectByAddress(address: String, pin: String, remember: Boolean, deviceName: String? = null)

    suspend fun connectBySerialNumber(serialNumber: String, pin: String, remember: Boolean)

    /** Закрывает соединение, не забывая прибор (в отличие от [forgetDevice]). */
    suspend fun disconnect()

    /** Забывает сохранённый прибор; если он сейчас подключён — отключает. */
    suspend fun forgetDevice(serialNumber: String)
}
