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
    // deviceName — рекламируемое BLE-имя устройства, если оно уже известно из скана (см.
    // PairingViewModel.selectDevice): из него разбираются модель/серийный номер прибора
    // (см. MeterRepositoryImpl.parseModelAndSerialFromDeviceName()), так как по DLMS их
    // без ассоциации прочитать нельзя. Может быть null (подключение по вручную введённому
    // адресу без предварительного скана) — тогда модель/серийный останутся прочерком.
    suspend fun connectByAddress(address: String, pin: String, remember: Boolean, deviceName: String? = null)
    suspend fun connectBySerialNumber(serialNumber: String, pin: String, remember: Boolean)

    // ---- UC-03 / UC-04 ----
    fun readings(): Flow<List<Reading>>
    suspend fun refreshReadings()

    /**
     * [Android-патч] Диагностика по запросу — пробует прочитать буфер (attribute 2) у ВСЕХ
     * известных из паспорта прибора объектов класса Profile Generic с пометкой "Журнал .../
     * профиль нагрузки" (см. MeterRepositoryImpl.LOG_CANDIDATES). Формат записей каждого журнала
     * свой и заранее неизвестен, поэтому результат сейчас идёт только в logcat (сырые байты +
     * общий DLMS-разбор либо код ошибки на каждый OBIS) — как только по свежему логу станет ясно,
     * какие журналы реально доступны без ассоциации и как устроены их записи, на этой основе
     * добавится нормальный парсер и экран со списком журналов.
     */
    suspend fun probeAllLogs()

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
