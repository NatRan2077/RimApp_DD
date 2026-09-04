package ru.rim.dd.data.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.rim.dd.core.ble.BleDevice
import ru.rim.dd.core.ble.MeterBleClient
import ru.rim.dd.core.bridge.SpodesClientBridge
import ru.rim.dd.core.model.ConnectionState
import ru.rim.dd.core.model.MeterInfo
import ru.rim.dd.core.model.NetworkParams
import ru.rim.dd.core.model.PairedDevice
import ru.rim.dd.core.model.Reading
import ru.rim.dd.core.model.RelayState
import ru.rim.dd.core.model.RelaySource
import ru.rim.dd.data.local.DeviceStore
import ru.rim.dd.data.local.ReadingDao
import ru.rim.dd.data.local.ReadingEntity
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Склеивает три независимых слоя:
 *  - MeterBleClient   — сырые байты по BLE (транспорт);
 *  - SpodesClientBridge — разбор/сборка кадров СПОДЭС (нативный код);
 *  - ReadingDao/DeviceStore — локальное хранение.
 *
 * На этом этапе (начало реализации) методы чтения — заглушки с понятной
 * структурой вызова: как только заработает связка BLE ⇄ JNI-мост, сюда
 * подставляется реальный код вместо TODO, сигнатуры наружу не меняются —
 * поэтому ViewModel'и и Compose-экраны можно писать и тестировать уже сейчас.
 */
@Singleton
class MeterRepositoryImpl @Inject constructor(
    private val ble: MeterBleClient,
    private val spodes: SpodesClientBridge,
    private val readingDao: ReadingDao,
    private val deviceStore: DeviceStore,
) : MeterRepository {

    private val _relayState = MutableStateFlow(
        RelayState(isOn = false, powerLimitKw = 0.0, source = RelaySource.UNKNOWN)
    )
    private val _networkParams = MutableStateFlow<NetworkParams?>(null)
    private val _meterInfo = MutableStateFlow<MeterInfo?>(null)
    private val _readings = MutableStateFlow<List<Reading>>(emptyList())

    private var activeSerialNumber: String? = null

    /** Собственный скоуп для «насоса» — живёт, пока есть активное BLE-соединение. */
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pumpJob: Job? = null

    override fun connectionState(): Flow<ConnectionState> = ble.connectionState

    override fun scanDevices(): Flow<BleDevice> = ble.scan()

    override suspend fun connectByAddress(address: String, pin: String, remember: Boolean) {
        // TODO(sprint 1): ble.connect(device) с реальным BluetoothDevice по address.
        startTransportPump()
        spodes.createClient()
        val connected = withContext(Dispatchers.IO) {
            // securityLevel/пароль — временно нижайший уровень (0, без pin в протоколе
            // сопряжения СПОДЭС); реальный уровень безопасности и пароль/ключ для
            // конкретного ПУ — открытый вопрос ТЗ, см. рисшифровку SpodesClient.
            spodes.establishConnection(securityLevel = 0, password = pin)
        }
        if (!connected) {
            stopTransportPump()
            throw IllegalStateException(spodes.lastErrorMessage())
        }
        activeSerialNumber = null // TODO: заполнить реальным серийным номером из ответа AARE/GET
        if (remember) {
            // deviceStore.rememberDevice(...) — после получения serialNumber от прибора
        }
    }

    override suspend fun connectBySerialNumber(serialNumber: String, pin: String, remember: Boolean) {
        // TODO(sprint 1): короткое сканирование + фильтр по имени/рекламным данным
        //  устройства, чтобы найти BLE-адрес по serialNumber, затем — как в connectByAddress.
        startTransportPump()
        spodes.createClient()
        val connected = withContext(Dispatchers.IO) {
            spodes.establishConnection(securityLevel = 0, password = pin)
        }
        if (!connected) {
            stopTransportPump()
            throw IllegalStateException(spodes.lastErrorMessage())
        }
        activeSerialNumber = serialNumber
        if (remember) {
            deviceStore.rememberDevice(PairedDevice(serialNumber = serialNumber))
        }
    }

    /**
     * «Насос» между транспортом (BLE) и протоколом (SpodesClientBridge) — см. комментарий
     * в SpodesClientBridge.kt. Две независимые корутины: одна пересылает входящие
     * notify-пакеты в нативный код, другая опрашивает исходящие байты и шлёт их по BLE.
     */
    private fun startTransportPump() {
        stopTransportPump()
        pumpJob = repositoryScope.launch {
            launch {
                ble.incomingBytes().collect { chunk -> spodes.pushIncomingBytes(chunk) }
            }
            launch {
                while (true) {
                    val outgoing = spodes.pullOutgoingBytes()
                    if (outgoing.isNotEmpty()) ble.writeBytes(outgoing)
                    delay(20) // TODO: заменить поллинг на событие/канал, если 20 мс окажется мало
                }
            }
        }
    }

    private fun stopTransportPump() {
        pumpJob?.cancel()
        pumpJob = null
    }

    override fun readings(): Flow<List<Reading>> = _readings.asStateFlow()

    override suspend fun refreshReadings() {
        // TODO(sprint 2): для каждого OBIS-объекта из ObisCatalog вызвать
        //  spodes.getRequest(classId, obisCode, attributeId), затем
        //  spodes.getResponseFloat()/getResponseInt() и замапить в Reading.
        val sn = activeSerialNumber ?: return
        val now = Instant.now()
        // Пример сохранения в историю (реальные значения появятся после интеграции):
        // readingDao.insert(ReadingEntity(obisId = "...", tariff = null, valueKwh = value,
        //     timestampEpochSeconds = now.epochSecond, serialNumber = sn))
    }

    override fun networkParams(): Flow<NetworkParams> = _networkParams.map {
        it ?: NetworkParams(
            voltage = ru.rim.dd.core.model.PhaseValues(0.0),
            current = ru.rim.dd.core.model.PhaseValues(0.0),
            power = ru.rim.dd.core.model.PhaseValues(0.0),
        )
    }

    override fun meterInfo(): Flow<MeterInfo> = _meterInfo.map {
        it ?: MeterInfo(model = "—", serialNumber = activeSerialNumber ?: "—", firmwareVersion = "—")
    }

    override fun relayState(): Flow<RelayState> = _relayState.asStateFlow()

    override suspend fun turnRelayOn() {
        // TODO(sprint 4): UC-08 — проверка remoteTurnOnAllowed, 60-секундный
        //  обратный отсчёт на стороне ViewModel, затем SET/ACTION через spodes.
    }

    override suspend fun turnRelayOff() {
        // TODO(sprint 4): немедленная команда отключения через spodes (ACTION).
    }

    override fun history(serialNumber: String): Flow<List<Reading>> =
        readingDao.observeHistory(serialNumber).map { list ->
            list.map { e ->
                Reading(
                    obisId = e.obisId,
                    tariff = e.tariff,
                    valueKwh = e.valueKwh,
                    timestamp = Instant.ofEpochSecond(e.timestampEpochSeconds),
                )
            }
        }

    override suspend fun forgetDevice(serialNumber: String) {
        deviceStore.forget(serialNumber)
        if (activeSerialNumber == serialNumber) {
            stopTransportPump()
            spodes.destroyClient()
            ble.disconnect()
            activeSerialNumber = null
        }
    }
}
