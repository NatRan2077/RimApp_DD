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
     * [Android-патч] см. ConnectionState.Reconnecting — после НЕОЖИДАННОГО разрыва связи (прибор
     * вне радиуса действия/выключен) эмитит Reconnecting, пока Android BLE stack сам не восстановит
     * GATT-соединение (autoConnect=true, см. MeterBleClient) и не пересоберётся протокольный сеанс
     * (см. MeterRepositoryImpl.reestablishProtocolSession()) — без участия пользователя. А вот
     * переход в Idle (явное disconnect() ниже) — сигнал для AppNavHost/ConnectionWatcherViewModel
     * увести пользователя на экран «Подключение».
     */
    fun connectionState(): Flow<ConnectionState>

    /**
     * [Android-патч] Уровень сигнала (RSSI, дБм) активного BLE-соединения — для экрана
     * «Настройки». В отличие от BleDevice.rssi (см. scanDevices()), который актуален только
     * ВО ВРЕМЯ поиска устройства, это значение обновляется, пока соединение установлено (см.
     * MeterBleClient.requestRssiRead()); null — соединения нет или ни одного успешного чтения
     * ещё не было.
     */
    fun signalStrengthDbm(): Flow<Int?>

    /**
     * [Android-патч] UC-12 (частично) — закрыть текущее BLE-соединение, НЕ забывая прибор
     * (в отличие от forgetDevice(), которая ещё и стирает его из DeviceStore). Для кнопки
     * «Отключиться» на экране «Настройки»: пользователь просто хочет прервать сеанс связи
     * (например, если пульт остаётся включён где-то поблизости и незачем держать сессию
     * активной), а не разорвать сопряжение — переподключение потом снова возможно и по
     * серийному номеру, и по адресу, как обычно.
     */
    suspend fun disconnect()

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

    /**
     * [Android-патч] Состояние пломб корпуса/клеммника, магнитного и СВЧ-датчиков, батареи и
     * превышения лимита мощности — см. TamperState.kt и TAMPER_STATUS_OBIS в GetResponseParser.kt.
     * Расшифровано из исходников прошивки самого пульта РиМ 040.40; приходит каждый цикл
     * автообновления внутри уже читаемого буфера индикации, без отдельного запроса.
     */
    fun tamperState(): Flow<TamperState>

    // ---- UC-10 ----
    fun history(serialNumber: String): Flow<List<Reading>>

    // ---- UC-12 ----
    suspend fun forgetDevice(serialNumber: String)
}
