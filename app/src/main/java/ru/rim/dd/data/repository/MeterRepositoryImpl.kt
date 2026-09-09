package ru.rim.dd.data.repository

import android.util.Log
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
import ru.rim.dd.core.dlms.parseGetResponseTariffs
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
        withContext(Dispatchers.IO) { ble.connectByAddress(address) }
        spodes.createClient()
        startTransportPump()
        withContext(Dispatchers.IO) { establishHdlcChannel() }
        val connected = withContext(Dispatchers.IO) { probeWithoutAssociation() }
        if (!connected) {
            stopTransportPump()
            ble.disconnect()
            throw IllegalStateException(spodes.lastErrorMessage())
        }
        activeSerialNumber = null // TODO: заполнить реальным серийным номером из ответа AARE/GET
        if (remember) {
            // deviceStore.rememberDevice(...) — после получения serialNumber от прибора
        }
    }

    /**
     * GET-запрос через GetRequestFlatAddress — "плоская" однобайтовая HDLC-адресация и
     * invoke-id-and-priority=0x81, как их использует штатная прошивка пульта РиМ 040.40
     * при обращении к этому конкретному счётчику (подтверждено сравнением с реальным
     * логом Tera Term и совпадающим ответом настоящего прибора на захардкоженный тестовый
     * кадр — см. историю диагностики). Обычный spodes.getRequest() тут не работает: он
     * пишет двухбайтовый logical+physical адрес и invoke-id 0xC1, счётчик такие кадры
     * молча игнорирует.
     *
     * destAddress=0x03/srcAddress=0x43 — адреса, подтверждённые на реальном приборе для
     * этого GET (класс 7 "Data", OBIS 0.0.21.0.2.255, атрибут 2 — первый запрос из лога
     * пульта). Если в дальнейшем понадобятся другие OBIS-запросы — адреса пока считаем
     * постоянными для этого устройства, но это не проверено для других OBIS.
     */
    private fun probeWithoutAssociation(): Boolean {
        Log.d(TAG, "GET-request через GetRequestFlatAddress (плоская адресация, invoke-id=0x81, как у пульта)")
        val sent = spodes.getRequestFlatAddress(
            classId = 7,
            obisCode = "0.0.21.0.2.255",
            attributeId = 2,
            destAddress = 0x03,
            srcAddress = 0x43,
        )
        if (!sent) return false

        val raw = spodes.getResponseRawBytes()
        if (raw.isEmpty()) {
            Log.w(TAG, "GET-response: пустой ответ от транспорта (${spodes.lastErrorMessage()})")
            return true // соединение по BLE рабочее, просто нет данных для парсинга — не рвём коннект
        }
        try {
            val tariffs = parseGetResponseTariffs(raw)
            Log.d(TAG, "GET-response: разобрано ${tariffs.size} показаний: $tariffs")
            _readings.value = tariffs.map {
                Reading(obisId = it.obisCode, tariff = it.tariff, valueKwh = it.valueKwh, timestamp = Instant.now())
            }
        } catch (ex: Exception) {
            // Не рвём соединение из-за ошибки разбора — само GET-request/response уже сработало,
            // а формат ответа для другого OBIS-кода может отличаться и потребовать доработки парсера.
            Log.e(TAG, "GET-response: не удалось разобрать DLMS-данные: ${ex.message}", ex)
        }

        probeOtherAttributes()
        return true
    }

    /**
     * ВРЕМЕННЫЙ ДИАГНОСТИЧЕСКИЙ ПЕРЕБОР (по просьбе пользователя — расширить список
     * запрашиваемых параметров: акт./реакт. энергия потр./ген. по тарифам, мощности,
     * напряжение, ток, частота). У этого счётчика уже подтверждено, что объект
     * класс 7 / OBIS 0.0.21.0.2.255 атрибут 2 отдаёт НЕ один регистр, а сразу массив
     * из 5 показаний (акт. потр. по тарифам 1-5) — это не стандартное поведение
     * IEC 62056 (там attribute 2 обычного "Data"-объекта — всегда скаляр), значит
     * это самодельный сборный объект прошивки счётчика. Рабочая гипотеза: другие
     * такие "пачки" (акт. ген., реактивная потр./ген., мгновенные мощности/U/I/F)
     * отдаются ДРУГИМИ атрибутами ТОГО ЖЕ объекта — проверяем это, читая по очереди
     * атрибуты 3..10 и логируя сырые байты каждого ответа. Если гипотеза не
     * подтвердится (аттрибуты пустые/ошибка), придётся опрашивать стандартные
     * OBIS-регистры (1.0.2.8.x.255, 1.0.3.8.x.255, 1.0.1.7.0.255 и т.д.) по одному —
     * см. следующий шаг доработки после анализа этого лога.
     *
     * УДАЛИТЬ/заменить на постоянный список после того, как станет ясно, что именно
     * приходит в каждом атрибуте.
     */
    private fun probeOtherAttributes() {
        for (attributeId in 3..10) {
            Log.d(TAG, "ДИАГНОСТИКА: пробуем class=7 obis=0.0.21.0.2.255 attribute=$attributeId")
            val sent = spodes.getRequestFlatAddress(
                classId = 7,
                obisCode = "0.0.21.0.2.255",
                attributeId = attributeId,
                destAddress = 0x03,
                srcAddress = 0x43,
            )
            if (!sent) {
                Log.w(TAG, "ДИАГНОСТИКА attribute=$attributeId: запрос не отправлен (${spodes.lastErrorMessage()})")
                continue
            }
            val raw = spodes.getResponseRawBytes()
            if (raw.isEmpty()) {
                Log.w(TAG, "ДИАГНОСТИКА attribute=$attributeId: пустой ответ (${spodes.lastErrorMessage()})")
                continue
            }
            Log.d(TAG, "ДИАГНОСТИКА attribute=$attributeId: сырой ответ (${raw.size} байт): ${raw.joinToString(" ") { "%02X".format(it) }}")
            try {
                val parsed = parseGetResponseTariffs(raw)
                Log.d(TAG, "ДИАГНОСТИКА attribute=$attributeId: распознано как показания: $parsed")
            } catch (ex: Exception) {
                Log.d(TAG, "ДИАГНОСТИКА attribute=$attributeId: не распозналось как набор показаний (${ex.message}) — см. сырые байты выше")
            }
        }
    }

    /**
     * HDLC-адресация нужна SpodesClient ВНУТРЕННЕ (EstablishConnectionRequest берёт
     * connection_addr_ из того, что сохранил SetNormalResponseMode) — поэтому вызов
     * не убираем. НО: по реальному логу обмена пульт↔счётчик (Tera Term) выяснилось,
     * что счётчик вообще не использует классический SNRM/UA хэндшейк — пульт с самого
     * старта шлёт I-кадры с GET-запросами напрямую, без SNRM и даже без AARQ/AARE.
     * Поэтому SNRM теперь ЛУЧШЕ ЭФФОРТ: отправляем (чтобы задать адреса в библиотеке),
     * но не требуем UA и не рвём соединение, если его нет/таймаут — пробуем establishConnection()
     * в любом случае, как и планировал пользователь (идти через DLMS/COSEM, а не сырые кадры).
     *
     * sourceAddress = 16 — единственное разрешённое значение в SetNormalResponseMode
     * (библиотека жёстко проверяет 16/32/48); в реальном обмене пульт использует
     * адрес 33 (0x43>>1), но такое значение эта библиотека не пропустит — это
     * ограничение самой SpodesClient, а не наша ошибка. logicalAddress=1, physicalAddress=1 —
     * совпадает с тем, что видно в реальных кадрах (адрес счётчика = 1).
     */
    private fun establishHdlcChannel() {
        Log.d(TAG, "SNRM: отправляем (source=$HDLC_SOURCE_ADDRESS, logical=$HDLC_LOGICAL_ADDRESS, physical=$HDLC_PHYSICAL_ADDRESS)")
        val snrmOk = spodes.setNormalResponseMode(
            sourceAddress = HDLC_SOURCE_ADDRESS,
            logicalAddress = HDLC_LOGICAL_ADDRESS,
            physicalAddress = HDLC_PHYSICAL_ADDRESS,
        )
        Log.d(TAG, "SNRM: setNormalResponseMode вернул $snrmOk")
        if (!snrmOk) {
            stopTransportPump()
            ble.disconnect()
            throw IllegalStateException("Не удалось сформировать SNRM-кадр: ${spodes.lastErrorMessage()}")
        }
        Log.d(TAG, "SNRM: ждём UA (не блокируем дальнейшую работу, даже если не придёт)...")
        val uaResult = spodes.receiveUnnumberedAcknowledge()
        Log.d(TAG, "SNRM: receiveUnnumberedAcknowledge вернул $uaResult (1=UA получен, 0=таймаут, 2=DM) — идём дальше в любом случае")
    }

    override suspend fun connectBySerialNumber(serialNumber: String, pin: String, remember: Boolean) {
        // TODO: серийный номер счётчика пока не участвует в поиске самого пульта —
        //  фильтруем только по префиксу имени пульта в эфире ("RIM ..."), к первому
        //  найденному и подключаемся. Как только известно, что именно пульт рекламирует
        //  в имени (сам serial или что-то ещё), фильтр можно сузить.
        withContext(Dispatchers.IO) { ble.connectByNamePrefix(BLE_NAME_PREFIX) }
        spodes.createClient()
        startTransportPump()
        withContext(Dispatchers.IO) { establishHdlcChannel() }
        val connected = withContext(Dispatchers.IO) { probeWithoutAssociation() }
        if (!connected) {
            stopTransportPump()
            ble.disconnect()
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

    companion object {
        /** Пульты РиМ 040.40 рекламируются в эфире как "RIM ..." — фильтр скана/поиска по имени. */
        private const val BLE_NAME_PREFIX = "RIM"

        // См. комментарий у establishHdlcChannel(): 16 подтверждено кодом, 1/1 — предположение.
        private const val HDLC_SOURCE_ADDRESS = 16
        private const val HDLC_LOGICAL_ADDRESS = 1
        private const val HDLC_PHYSICAL_ADDRESS = 1

        private const val TAG = "MeterRepository"
    }
}
