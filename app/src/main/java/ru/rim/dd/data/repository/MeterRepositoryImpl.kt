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
import ru.rim.dd.core.dlms.ObisReading
import ru.rim.dd.core.dlms.decodeCosemDate
import ru.rim.dd.core.dlms.decodeCosemTime
import ru.rim.dd.core.dlms.describeDlmsValue
import ru.rim.dd.core.dlms.parseGetResponseTariffs
import ru.rim.dd.core.dlms.parseGetResponseValue
import ru.rim.dd.core.dlms.textValueOf
import ru.rim.dd.core.model.ConnectionState
import ru.rim.dd.core.model.MeterInfo
import ru.rim.dd.core.model.NetworkParams
import ru.rim.dd.core.model.PairedDevice
import ru.rim.dd.core.model.PhaseValues
import ru.rim.dd.core.model.Reading
import ru.rim.dd.core.model.RelayState
import ru.rim.dd.core.model.RelaySource
import ru.rim.dd.data.local.DeviceStore
import ru.rim.dd.data.local.ReadingDao
import java.time.Instant
import java.time.LocalDateTime
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

    override suspend fun connectByAddress(address: String, pin: String, remember: Boolean, deviceName: String?) {
        withContext(Dispatchers.IO) { ble.connectByAddress(address) }
        spodes.createClient()
        startTransportPump()
        withContext(Dispatchers.IO) { establishHdlcChannel() }
        val connected = withContext(Dispatchers.IO) { readAndApplyMainBuffer() }
        if (!connected) {
            stopTransportPump()
            ble.disconnect()
            throw IllegalStateException(spodes.lastErrorMessage())
        }
        applyDeviceNameInfo(deviceName)
        if (remember) {
            deviceStore.rememberDevice(PairedDevice(serialNumber = activeSerialNumber ?: address, bleAddress = address))
        }
    }

    /**
     * [Android-патч] Модель и серийный номер счётчика НЕЛЬЗЯ прочитать по DLMS без ассоциации
     * (см. комментарий у readFirmwareVersion() — та же причина, "object unavailable"). Но пульт
     * РиМ 040.40 рекламируется в эфире BLE-именем, которое само содержит эти данные — подтверждено
     * пользователем на реальном устройстве ("RIM1894604314652": первые 5 цифр после буквенного
     * префикса — модель, "18946" → "189.46" (точка после 3-й цифры), оставшиеся 8 цифр — серийный
     * номер, "04314652"). Парсер терпим к пробелам/дефисам в имени (просто выбирает все цифры), но
     * при ЛЮБОМ отклонении от 13 цифр возвращает null — лучше оставить прочерк, чем ошибочно
     * догадаться модель/серийный из имени нестандартного формата.
     */
    private fun parseModelAndSerialFromDeviceName(name: String?): Pair<String, String>? {
        val digits = name?.filter { it.isDigit() } ?: return null
        if (digits.length != 13) return null
        val modelDigits = digits.substring(0, 5)
        val serial = digits.substring(5)
        val model = "${modelDigits.substring(0, 3)}.${modelDigits.substring(3)}"
        return model to serial
    }

    /** Разбирает [name] через [parseModelAndSerialFromDeviceName] и применяет к _meterInfo/activeSerialNumber. */
    private fun applyDeviceNameInfo(name: String?) {
        val parsed = parseModelAndSerialFromDeviceName(name)
        if (parsed == null) {
            Log.w(TAG, "Модель/серийный номер: не удалось разобрать имя устройства \"$name\" (ожидалось 13 цифр после префикса \"RIM\")")
            return
        }
        val (model, serial) = parsed
        Log.d(TAG, "Модель/серийный номер разобраны из имени устройства \"$name\": модель=$model, серийный=$serial")
        activeSerialNumber = serial
        val prev = _meterInfo.value
        _meterInfo.value = (prev ?: MeterInfo(model = "—", serialNumber = "—", firmwareVersion = "—"))
            .copy(model = model, serialNumber = serial)
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
    /**
     * [Android-патч] ПОПРОБОВАЛИ и ОТКАЗАЛИСЬ: установление DLMS-ассоциации (AARQ/AARE) через
     * establishConnectionFlatAddress() — см. историю диагностики. Гипотеза была разумной
     * (0x0B "object unavailable" даже для обязательного Association LN намекал на нужду в
     * ассоциации), но реальный счётчик отреагировал на AARQ ПЛОХО: он не прислал настоящий
     * AARE (тег 0x61), а начал отвечать одним и тем же кадром "E6 E7 00 D8 02 01" АБСОЛЮТНО
     * на любой следующий запрос — включая тот самый GET 0.0.21.0.2.255, который до этого
     * стабильно работал. То есть счётчик, получив AARQ, которого не ждал (он вообще не
     * использует классическое ACSE-соглашение — ни SNRM/UA, ни AARQ/AARE, судя по всему),
     * переходит в какой-то залоченный/ошибочный режим до конца BLE-сессии (переподключение
     * его сбрасывает). Поэтому AARQ теперь НЕ вызывается автоматически при подключении —
     * это было бы регрессией (ломает единственный рабочий канал данных). Метод
     * tryEstablishAssociation() и нативная пара establishConnectionFlatAddress()/
     * establishConnectionResponseRawBytes() оставлены в бридже нетронутыми на случай, если
     * позже найдётся правильный security_level/пароль и захочется проверить снова вручную —
     * но по умолчанию код к этому больше не обращается.
     *
     * [Android-патч] РАЗГАДКА (получена сравнением с реальным логом самого пульта РиМ 040.40,
     * снятым на настоящем обмене пульт↔счётчик): "5 показаний" — это была вовсе не вся правда
     * об объекте 0.0.21.0.2.255, а только ПЕРВЫЙ HDLC-сегмент его ответа! Лог пульта показывает,
     * что ответ на ЭТОТ ЖЕ САМЫЙ запрос (байт в байт совпадает с нашим — сверено) реально приходит
     * 7+ сегментами: пульт получает первый ~137-байтовый кадр с битом "сегментировано", шлёт
     * HDLC S-frame (RR) с "плоской" адресацией, получает следующий сегмент, и так далее, пока не
     * получит финальный (без этого бита) — и только тогда разбирает итоговый буфер (в логе пульта
     * это подписано "End Of Segmentation" / "DLMS - Parsing Data Success"). Наш клиент раньше
     * (см. историю в GetResponseRaw()) считал этот бит ложным и НЕ запрашивал продолжение — из-за
     * чего видел только первый сегмент (те самые 5 значений активной энергии по тарифам).
     * ИСПРАВЛЕНО: GetResponseRaw() теперь сам дочитывает все сегменты через
     * SendReadyToReceiveFlatAddress() (см. spodes_client.cpp) — тот самый баг с "ложной
     * сегментацией" был на самом деле багом в RR-кадре (он шёл с двухбайтовой адресацией,
     * которую счётчик игнорировал, отсюда и 6-секундное зависание, которое когда-то заставило
     * убрать чтение продолжения вовсе). Разобрав ПОЛНЫЙ буфер (в логе пульта — 83 элемента),
     * там оказались активная/реактивная энергия по тарифам 1-8 (не только 1-5) за ДВА периода,
     * мощности (активная/реактивная/полная), ток нейтрали, частота, дата/время и статусные
     * OBIS-коды 0.0.96.x — то есть, вероятно, все ~80 параметров "Настройки индикации" И БЕЗ
     * ассоциации, И без обращения к другим OBIS-объектам.
     *
     * [Android-патч] УТОЧНЕНИЕ (после полного разбора всех 83 элементов, см.
     * applyDecodedBuffer() ниже): напряжение и ток в буфере ЕСТЬ, но под НЕСТАНДАРТНЫМИ OBIS —
     * 1.0.12.7.0.255 (не 1.0.32.7.0.255) и 1.0.11.7.0.255 (не 1.0.31.7.0.255). Также нашлись
     * температура прибора (0.0.96.9.0.255) и напряжение резервного питания (0.0.96.6.3.255).
     * Первоначальный вывод "напряжения в буфере нет" был ошибкой при неполной таблице единиц
     * измерения IEC 62056-6-2 — исправлено.
     *
     * [Android-патч] Диагностический перебор кандидатов (probeStandardObisCandidates(),
     * ~40 дополнительных GET-запросов на подключение — сканирование 0.0.21.0.C.255 по C=0..19
     * плюс десяток угадаек по стандартным Register-объектам) УДАЛЁН: он был нужен только пока
     * не было ясно, что единственный буфер 0.0.21.0.2.255 уже содержит все нужные параметры
     * (энергия, мощности, напряжение, ток, частота, температура, часы — см. applyDecodedBuffer()
     * ниже). Теперь это просто лишняя задержка подключения — каждый кандидат из перебора это
     * отдельный GET-запрос/ответ по BLE, а нужных данных он всё равно не давал (без ассоциации
     * все "угадайки" гарантированно возвращали object-unavailable, а сканирование 0.0.21.0.C.255
     * подтвердило, что кроме C=1 и C=2 других профилей нет).
     *
     * [Android-патч] Переименована из probeWithoutAssociation() в readAndApplyMainBuffer() —
     * теперь это не только "проверка при подключении", но и общая логика для кнопки
     * «Обновить» на экранах (см. refreshReadings() ниже): один и тот же GET-запрос
     * 0.0.21.0.2.255 обновляет все три StateFlow разом (показания/сеть/инфо), поэтому
     * достаточно одной функции, вызываемой и при коннекте, и по требованию пользователя.
     */
    private fun readAndApplyMainBuffer(): Boolean {
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
            val all = parseGetResponseTariffs(raw)
            Log.d(TAG, "GET-response: разобрано ${all.size} элементов буфера: $all")
            applyDecodedBuffer(all)
        } catch (ex: Exception) {
            // Не рвём соединение из-за ошибки разбора — само GET-request/response уже сработало,
            // а формат ответа для другого OBIS-кода может отличаться и потребовать доработки парсера.
            Log.e(TAG, "GET-response: не удалось разобрать DLMS-данные: ${ex.message}", ex)
        }

        readFirmwareVersion()
        return true
    }

    /**
     * [Android-патч] Отдельный "голый" GET на служебный OBIS 0.0.96.1.2.255 (class 1 "Data",
     * attribute 2) — версия ПО этого прибора. Не входит в capture_objects профиля
     * 0.0.21.0.2.255, поэтому запрашивается отдельным запросом сразу после основного.
     *
     * [Android-патч] ВАЖНО (см. историю диагностики кнопки «Обновить»): счётчик, похоже, ДЕЙСТВИТЕЛЬНО
     * хранит нумерацию HDLC-кадров (N(S)/N(R)) в рамках всего BLE-соединения, а не по одному
     * запросу — попытка принудительно сбрасывать её в 0 перед каждым верхнеуровневым GET (был
     * такой фикс в GetRequestFlatAddress()) оказалась ОШИБКОЙ: счётчик воспринимал повтор N(S)=0
     * как дублирующийся кадр и просто переотправлял старый ACK, не выполняя команду заново —
     * поэтому фикс откачен. Корректность второго GET подряд (этого) зависит от того, что
     * send_sequence_number_/receive_sequence_number_ у нас накапливаются БЕЗ ДРЕЙФА относительно
     * реального числа переданных/принятых кадров — отдельно исправлен баг в BleTransport::ReadData()
     * (см. ble_transport.h), из-за которого чтение каждого сегмента могло растягиваться на секунды
     * и, возможно, склеивать границы кадров. Это подтверждено логом реального обмена: второй GET
     * подряд теперь ПОЛУЧАЕТ настоящий (не голый RR) DLMS-ответ.
     *
     * [Android-патч] КОД ПОДТВЕРЖДЁН (сверено с паспортом объектов прибора, файл-выгрузка
     * "RIM1894604314652", загруженный пользователем): для класса "Data" с описанием "Версия ПО"
     * в паспорте прямо указан OBIS 0.0.96.1.2.255 — то есть угадка была верной, дело не в неверном
     * коде. Реальный ответ прибора на этот GET — корректно оформленный data-access-result с кодом
     * 0x0B (11, "object unavailable"): прибор понимает запрос, но не отдаёт значение по этому
     * адресу без прикладной ассоциации (AARQ/AARE). Это согласуется с более ранним наблюдением у
     * establishConnectionFlatAddress() (см. комментарий ниже по коду и в SpodesClientBridge.kt) —
     * БЕЗ ассоциации счётчик отдаёт ровно ОДИН "публичный" объект, 0.0.21.0.2.255 (основной буфер),
     * а вообще всё остальное, включая обязательный для любого DLMS-сервера Association LN
     * (0.0.40.0.0.255), отвечает тем же самым "object unavailable". Поэтому перебор ДРУГИХ
     * OBIS-кодов версии ПО (0.0.96.1.0/1/6.255, 1.0.0.2.0.255 и т.п.) ничего не даст — они тоже
     * упрутся в то же самое "нет ассоциации", а не в "неверный код". А сам AARQ на этом приборе
     * пробовали включать раньше — он не присылает настоящий AARE и вместо этого ломает единственный
     * рабочий канал данных до конца BLE-сессии (см. подробности в readAndApplyMainBuffer() выше),
     * так что автоматически он больше не вызывается. Итог: "странная" версия ПО — это ЧИСТАЯ,
     * ожидаемая ошибка доступа, а не мусорные/нечитаемые байты; чтобы читать её по-настоящему,
     * нужен правильный security_level/пароль для AARQ этого конкретного прибора (уточнить у
     * специалиста РиМ или по паспорту прибора) — тогда можно будет аккуратно (вручную, не при
     * каждом коннекте) попробовать tryEstablishAssociation() ещё раз. Ошибку разбора здесь не
     * считаем фатальной — версия ПО просто останется прочерком.
     */
    private fun readFirmwareVersion() {
        Log.d(TAG, "GET-request версии ПО (0.0.96.1.2.255, class=1, attr=2)")
        val sent = spodes.getRequestFlatAddress(
            classId = 1,
            obisCode = "0.0.96.1.2.255",
            attributeId = 2,
            destAddress = 0x03,
            srcAddress = 0x43,
        )
        if (!sent) {
            Log.w(TAG, "Версия ПО: запрос не отправлен (${spodes.lastErrorMessage()})")
            return
        }
        val raw = spodes.getResponseRawBytes()
        if (raw.isEmpty()) {
            Log.w(TAG, "Версия ПО: пустой ответ от транспорта (${spodes.lastErrorMessage()})")
            return
        }
        try {
            val value = parseGetResponseValue(raw)
            val text = textValueOf(value)
            Log.d(TAG, "Версия ПО: разобрано как \"$text\" (сырое значение: ${describeDlmsValue(value)})")
            val prev = _meterInfo.value
            _meterInfo.value = (
                    prev ?: MeterInfo(model = "—", serialNumber = activeSerialNumber ?: "—", firmwareVersion = "—")
                    ).copy(firmwareVersion = text)
        } catch (ex: Exception) {
            // Ожидаемый случай на этом приборе без ассоциации: data-access-result 0x0B
            // ("object unavailable") — код OBIS верный (сверено с паспортом), просто прибор не
            // отдаёт его без AARQ. См. комментарий к функции выше.
            Log.w(
                TAG,
                "Версия ПО: не удалось разобрать ответ (${ex.message}) — сырые байты (${raw.size}): " +
                        raw.joinToString(" ") { "%02X".format(it) },
            )
        }
    }

    /**
     * [Android-патч] По запросу пользователя ("вытягивать все возможные журналы") — каталог
     * объектов класса Profile Generic из паспорта прибора (файл-выгрузка "RIM1894604314652",
     * загруженный пользователем), у которых в описании явно значится "Журнал ..." либо это
     * связанные с журналами суточный/месячный отчёт и профили нагрузки. OBIS-коды и названия
     * взяты дословно из паспорта — attribute 2 (buffer) для класса Profile Generic (7) везде
     * одинаков, как и у уже рабочего 0.0.21.0.2.255.
     */
    private data class LogCandidate(val obisCode: String, val label: String)

    private val LOG_CANDIDATES = listOf(
        LogCandidate("0.0.99.98.0.255", "Журнал событий по напряжению"),
        LogCandidate("0.0.99.98.1.255", "Журнал событий по току"),
        LogCandidate("0.0.99.98.2.255", "Журнал включений-выключений"),
        LogCandidate("0.0.99.98.3.255", "Журнал коррекций"),
        LogCandidate("0.0.99.98.4.255", "Журнал внешних воздействий"),
        LogCandidate("0.0.99.98.5.255", "Журнал подключений"),
        LogCandidate("0.0.99.98.6.255", "Журнал несанкционированного доступа"),
        LogCandidate("0.0.99.98.7.255", "Журнал самодиагностики"),
        LogCandidate("0.0.99.98.8.255", "Журнал событий по Tg(Ф)"),
        LogCandidate("0.0.99.98.9.255", "Журнал ПКЭ"),
        LogCandidate("0.0.99.98.11.255", "Журнал радиопоиска"),
        LogCandidate("0.0.99.98.12.255", "Журнал контроля Tg(Ф)"),
        LogCandidate("0.0.99.98.13.255", "Журнал установки времени"),
        LogCandidate("0.0.99.98.14.255", "Годовой журнал"),
        LogCandidate("0.0.99.98.15.255", "Журнал ПКЭ на РДЧ"),
        LogCandidate("0.0.99.98.16.255", "Журнал контроля мощности"),
        LogCandidate("0.0.99.98.18.255", "Журнал блокировки реле"),
        LogCandidate("1.0.94.7.0.255", "Журнал стоп-кадра"),
        LogCandidate("1.0.98.1.0.255", "Месячный журнал"),
        LogCandidate("1.0.98.2.0.255", "Суточный журнал"),
        LogCandidate("1.0.99.1.0.255", "Профиль нагрузки №1"),
        LogCandidate("1.0.99.2.0.255", "Профиль нагрузки №2"),
        LogCandidate("1.0.99.3.0.255", "Профиль нагрузки №3"),
    )

    /**
     * Один GET на буфер журнала — та же "плоская" адресация, что и у readAndApplyMainBuffer()/
     * readFirmwareVersion(). Формат записей у каждого журнала свой (разные capture_objects) и
     * заранее неизвестен, поэтому пока не парсим целевую структуру — просто логируем общий DLMS-
     * разбор (describeDlmsValue) при успехе либо код ошибки при неудаче. По ожиданиям (см.
     * комментарий у readFirmwareVersion()) большинство, вероятно, вернёт то же самое "object
     * unavailable" 0x0B — этот прибор без AARQ отдаёт только 0.0.21.0.2.255 — но не проверяли
     * КАЖДЫЙ журнал по отдельности, так что диагностика имеет смысл.
     */
    private fun tryReadLog(candidate: LogCandidate) {
        Log.d(TAG, "GET-request журнала \"${candidate.label}\" (${candidate.obisCode}, class=7, attr=2)")
        val sent = spodes.getRequestFlatAddress(
            classId = 7,
            obisCode = candidate.obisCode,
            attributeId = 2,
            destAddress = 0x03,
            srcAddress = 0x43,
        )
        if (!sent) {
            Log.w(TAG, "Журнал \"${candidate.label}\" (${candidate.obisCode}): запрос не отправлен (${spodes.lastErrorMessage()})")
            return
        }
        val raw = spodes.getResponseRawBytes()
        if (raw.isEmpty()) {
            Log.w(TAG, "Журнал \"${candidate.label}\" (${candidate.obisCode}): пустой ответ от транспорта (${spodes.lastErrorMessage()})")
            return
        }
        try {
            val value = parseGetResponseValue(raw)
            Log.d(TAG, "Журнал \"${candidate.label}\" (${candidate.obisCode}): УСПЕХ — ${describeDlmsValue(value)}")
        } catch (ex: Exception) {
            Log.w(TAG, "Журнал \"${candidate.label}\" (${candidate.obisCode}): ${ex.message}")
        }
    }

    private fun probeAllLogCandidates() {
        Log.d(TAG, "=== Диагностика журналов: пробуем ${LOG_CANDIDATES.size} объектов класса Profile Generic ===")
        for (candidate in LOG_CANDIDATES) {
            tryReadLog(candidate)
        }
        Log.d(TAG, "=== Диагностика журналов: завершено ===")
    }

    override suspend fun probeAllLogs() {
        if (pumpJob?.isActive != true) {
            Log.w(TAG, "probeAllLogs(): нет активного BLE-соединения")
            return
        }
        withContext(Dispatchers.IO) { probeAllLogCandidates() }
    }

    /**
     * Раскладывает ПОЛНЫЙ буфер объекта 0.0.21.0.2.255 (83 элемента, см. историю диагностики —
     * сравнение с логом реального пульта) по трём независимым StateFlow, которые уже слушают
     * экраны «Показания»/«Сеть»/«Инфо». Один и тот же буфер содержит вперемешку:
     *  - накопленную энергию (OBIS A.B.C.8.E.F, C=1..4 — активная/реактивная, E — тариф,
     *    F=255 — текущий расчётный период, F=101 — предыдущий период) → _readings;
     *  - мгновенные величины сети (A.B.C.7.0.255 — мощность/напряжение/ток/частота,
     *    нестандартные коды C=11/12/91, подтверждённые реальными значениями) → _networkParams;
     *  - статусные OBIS 0.0.96.x (температура, резервное питание) и часы самого прибора
     *    (0.0.0.9.1.255 время / 0.0.0.9.2.255 дата) → _meterInfo.
     * Показания за предыдущий период (F=101) сознательно не показываются на текущем этапе —
     * экран «Показания» ориентирован на текущие значения (можно добавить отдельно позже).
     */
    private fun applyDecodedBuffer(all: List<ObisReading>) {
        fun find(obis: String) = all.firstOrNull { it.obisCode == obis }

        // ---- Энергия по категориям и тарифам (текущий период) → _readings ----
        val energyReadings = all.filter { r ->
            val g = r.obisCode.split(".")
            g.size == 6 && g[3] == "8" && g[5] == "255" && (g[2].toIntOrNull() in 1..4)
        }
        if (energyReadings.isNotEmpty()) {
            _readings.value = energyReadings.map {
                Reading(obisId = it.obisCode, tariff = it.tariff, valueKwh = it.valueKwh, timestamp = Instant.now())
            }
        }

        // ---- Мгновенные параметры сети → _networkParams ----
        val activePowerW = find("1.0.1.7.0.255")?.value
        val reactivePowerVar = find("1.0.3.7.0.255")?.value
        val apparentPowerVa = find("1.0.9.7.0.255")?.value
        val voltageV = find("1.0.12.7.0.255")?.value
        val currentA = find("1.0.11.7.0.255")?.value
        val neutralCurrentA = find("1.0.91.7.0.255")?.value
        val frequencyHz = find("1.0.14.7.0.255")?.value
        if (voltageV != null || currentA != null || activePowerW != null) {
            _networkParams.value = NetworkParams(
                voltage = PhaseValues(total = voltageV ?: 0.0),
                current = PhaseValues(total = currentA ?: 0.0),
                power = PhaseValues(total = (activePowerW ?: 0.0) / 1000.0),
                frequencyHz = frequencyHz,
                reactivePowerKvar = reactivePowerVar?.div(1000.0),
                apparentPowerKva = apparentPowerVa?.div(1000.0),
                neutralCurrentA = neutralCurrentA,
            )
        }

        // ---- Температура / резервное питание / часы прибора → _meterInfo ----
        val temperatureC = find("0.0.96.9.0.255")?.value
        val backupVoltageV = find("0.0.96.6.3.255")?.value
        val dateBytes = find("0.0.0.9.2.255")?.octetValue
        val timeBytes = find("0.0.0.9.1.255")?.octetValue
        val deviceClock = if (dateBytes != null && timeBytes != null) {
            val date = decodeCosemDate(dateBytes)
            val time = decodeCosemTime(timeBytes)
            if (date != null && time != null) LocalDateTime.of(date, time) else null
        } else null
        if (temperatureC != null || backupVoltageV != null || deviceClock != null) {
            val prev = _meterInfo.value
            _meterInfo.value = (
                    prev ?: MeterInfo(model = "—", serialNumber = activeSerialNumber ?: "—", firmwareVersion = "—")
                    ).copy(
                    temperatureC = temperatureC,
                    backupVoltageV = backupVoltageV,
                    deviceClock = deviceClock,
                    lastSeenAt = Instant.now(),
                )
        }
    }

    /**
     * HDLC-адресация нужна SpodesClient ВНУТРЕННЕ (EstablishConnectionRequest берёт
     * connection_addr_ из того, что сохранил SetNormalResponseMode) — поэтому вызов
     * setNormalResponseMode() не убираем. НО: по реальному логу обмена пульт↔счётчик
     * (Tera Term) выяснилось, что счётчик вообще не использует классический SNRM/UA
     * хэндшейк — пульт с самого старта шлёт I-кадры с GET-запросами напрямую, без SNRM
     * и даже без AARQ/AARE.
     *
     * [Android-патч] НАЙДЕНА причина "долгого подключения": мы всё равно ЖДАЛИ ответ на
     * SNRM (spodes.receiveUnnumberedAcknowledge()) — а он гарантированно таймаутится
     * (устройство никогда не шлёт UA), и таймаут транспорта — 3 секунды (см.
     * BleTransport::read_timeout_ms_ в ble_transport.h). То есть КАЖДОЕ подключение теряло
     * фиксированные ~3 секунды на заведомо бесполезное ожидание. При этом connection_addr_,
     * который выставляет setNormalResponseMode(), в реальности вообще не используется —
     * весь рабочий путь идёт через *FlatAddress()-методы (GetRequestFlatAddress(),
     * GetResponseRaw(), SendReadyToReceiveFlatAddress()), которые строят HDLC-заголовок
     * САМИ, с параметрами dest/src, переданными явно, а не через connection_addr_. Поэтому
     * само ожидание UA не даёт вообще ничего полезного нашему коду — убрано. SNRM
     * по-прежнему отправляем (мгновенная запись, не блокирует) на случай, если устройству
     * всё же важно увидеть этот кадр в эфире, просто больше не тратим 3 секунды на ответ,
     * которого не будет.
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
        // Ожидание UA убрано — см. комментарий выше: 3 секунды впустую на каждое подключение.
    }

    override suspend fun connectBySerialNumber(serialNumber: String, pin: String, remember: Boolean) {
        // TODO: серийный номер счётчика пока не участвует в поиске самого пульта —
        //  фильтруем только по префиксу имени пульта в эфире ("RIM ..."), к первому
        //  найденному и подключаемся. Как только известно, что именно пульт рекламирует
        //  в имени (сам serial или что-то ещё), фильтр можно сузить.
        val found = withContext(Dispatchers.IO) { ble.connectByNamePrefix(BLE_NAME_PREFIX) }
        spodes.createClient()
        startTransportPump()
        withContext(Dispatchers.IO) { establishHdlcChannel() }
        val connected = withContext(Dispatchers.IO) { readAndApplyMainBuffer() }
        if (!connected) {
            stopTransportPump()
            ble.disconnect()
            throw IllegalStateException(spodes.lastErrorMessage())
        }
        // Реальное имя найденного устройства (found.name) — приоритетнее введённого пользователем
        // serialNumber: из него разбираются и модель, и серийный (см. applyDeviceNameInfo()).
        // Если разбор не удался (нестандартное имя), activeSerialNumber останется null здесь —
        // тогда используем то, что ввёл пользователь, как и раньше.
        applyDeviceNameInfo(found.name)
        if (activeSerialNumber == null) activeSerialNumber = serialNumber
        if (remember) {
            deviceStore.rememberDevice(PairedDevice(serialNumber = activeSerialNumber ?: serialNumber, bleAddress = found.address))
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

    /**
     * Обновление по кнопке («Показания»/«Сеть»/«Инфо» — все три экрана дёргают этот же метод,
     * см. ReadingsViewModel.refresh()/NetworkViewModel.refresh()/InfoViewModel.refresh()).
     * Раньше это была заглушка, ничего не делавшая — кнопка «Обновить» была нерабочей. Теперь
     * просто переспрашиваем тот же буфер 0.0.21.0.2.255 (readAndApplyMainBuffer() — общий код
     * с первым чтением при подключении), который разом обновляет энергию/сеть/инфо.
     *
     * Проверяем pumpJob (а не activeSerialNumber — он остаётся null при подключении по адресу,
     * см. TODO в connectByAddress()), чтобы не слать GET по мёртвому BLE-соединению.
     */
    override suspend fun refreshReadings() {
        if (pumpJob?.isActive != true) {
            Log.w(TAG, "refreshReadings(): нет активного BLE-соединения — обновлять нечего")
            return
        }
        withContext(Dispatchers.IO) {
            val ok = readAndApplyMainBuffer()
            if (!ok) Log.w(TAG, "refreshReadings(): GET-запрос не удался (${spodes.lastErrorMessage()})")
        }
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
