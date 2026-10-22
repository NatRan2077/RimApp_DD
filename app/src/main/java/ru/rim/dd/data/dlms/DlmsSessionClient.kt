package ru.rim.dd.data.dlms

import ru.rim.dd.core.log.APP_LOGGING_ENABLED
import ru.rim.dd.core.log.AppLog
import ru.rim.dd.core.bridge.SpodesClientBridge
import ru.rim.dd.core.dlms.CaptureColumn
import ru.rim.dd.core.dlms.DlmsValue
import ru.rim.dd.core.dlms.ObisCatalog
import ru.rim.dd.core.dlms.ObisReading
import ru.rim.dd.core.dlms.ScalerUnit
import ru.rim.dd.core.dlms.captureColumnsOf
import ru.rim.dd.core.dlms.compactReadingsOf
import ru.rim.dd.core.dlms.decodeDlmsValue
import ru.rim.dd.core.dlms.parseActionResponseResult
import ru.rim.dd.core.dlms.parseGetResponseBlock
import ru.rim.dd.core.dlms.parseGetResponseValue
import ru.rim.dd.core.dlms.readingsOf
import ru.rim.dd.core.dlms.scalerUnitOf
import ru.rim.dd.core.dlms.unitLabelOf
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/** Итог одного чтения буфера индикации (см. [DlmsSessionClient.readMainBuffer]). */
sealed class MainBufferResult {
    /** Запрос не удалось отправить. */
    data class NotSent(val error: String?) : MainBufferResult()

    /** Запрос ушёл, но транспорт вернул пустой ответ. */
    data class Empty(val error: String?) : MainBufferResult()

    /** Ответ пришёл, но буфер не получен целиком или не разобран как DLMS-значение. */
    object Incomplete : MainBufferResult()

    /** Ответ пришёл, но раскладка элементов буфера бросила исключение. */
    data class DecodeFailed(val error: Exception) : MainBufferResult()

    /** Буфер получен и разложен на элементы. */
    data class Decoded(val readings: List<ObisReading>) : MainBufferResult()
}

/**
 * Протокольный обмен с прибором поверх нативного клиента СПОДЭС: GET буфера индикации,
 * склейка DLMS-блоков, ассоциация AKROS, чтение capture_objects и scaler_unit, команды
 * Disconnect Control. Ничего не знает о состоянии экранов, журнале и BLE — на вход запросы,
 * на выход разобранные элементы буфера.
 *
 * Нативный клиент не потокобезопасен: все вызовы делаются под общим Mutex репозитория
 * (см. MeterSessionManager.protocolMutex), поэтому здесь своей синхронизации нет.
 */
@Singleton
class DlmsSessionClient @Inject constructor(
    private val spodes: SpodesClientBridge,
) {
    /**
     * true — AKROS читается запасным путём (SNRM + AARQ «Reader», компактный буфер); false —
     * путь пульта без ассоциации. Выбирает вызывающий код при подключении.
     */
    var akrosAssociation = false

    /**
     * Колонки компактного буфера AKROS (только при [akrosAssociation]): значения идут подряд без
     * OBIS, смысл колонок читается из capture_objects. null — колонка не разобрана и пропускается.
     */
    private var akrosCaptureObis: List<CaptureColumn?> = emptyList()

    /** Единицы измерения колонок компактного буфера AKROS по OBIS (атрибут scaler_unit). */
    private var akrosScalerUnits: Map<String, ScalerUnit> = emptyMap()

    fun createClient() = spodes.createClient()

    fun destroyClient() = spodes.destroyClient()

    /** Пересоздаёт нативный клиент: счётчики HDLC-кадров начинаются заново. */
    fun recreateClient() {
        spodes.destroyClient()
        spodes.createClient()
    }

    /** Забывает описание буфера прошлого прибора (новое подключение или отключение). */
    fun resetDescription() {
        akrosCaptureObis = emptyList()
        akrosScalerUnits = emptyMap()
    }

    fun lastErrorMessage(): String = spodes.lastErrorMessage()

    /**
     * GET буфера индикации 0.0.21.0.2.255 (класс 7, атрибут 2) и разбор ответа в элементы.
     *
     * Основной путь — механизм штатного пульта (прошивка РиМ 040.40), одинаковый для РиМ и AKROS:
     * «плоская» адресация (dest 0x03 / src 0x43), без SNRM и ассоциации; HDLC-сегменты ответа
     * дочитывает нативный клиент (RR-кадры), DLMS-блоки — [assembleDlmsValue]; склеенное значение
     * разбирается [readingsOf]. Запасной путь AKROS ([akrosAssociation]) — стандартная адресация
     * и компактный буфер.
     */
    fun readMainBuffer(): MainBufferResult {
        val sent = if (akrosAssociation) {
            AppLog.d(TAG, "GET буфера AKROS (стандартная адресация, после ассоциации)")
            spodes.getRequest(MAIN_BUFFER_CLASS_ID, MAIN_BUFFER_OBIS, attributeId = 2)
        } else {
            AppLog.d(TAG, "GET буфера индикации (плоская адресация, как у пульта)")
            spodes.getRequestFlatAddress(
                classId = MAIN_BUFFER_CLASS_ID,
                obisCode = MAIN_BUFFER_OBIS,
                attributeId = 2,
                destAddress = FLAT_DEST_ADDRESS,
                srcAddress = FLAT_SRC_ADDRESS,
            )
        }
        if (!sent) return MainBufferResult.NotSent(spodes.lastErrorMessage())

        val raw = spodes.getResponseRawBytes()
        if (raw.isEmpty()) return MainBufferResult.Empty(spodes.lastErrorMessage())

        return try {
            // Блоки дочитываем сразу, пока канал не занят «хвостом» этого ответа, иначе
            // следующий запрос разъедется с ним.
            val buffer = assembleDlmsValue(raw, "буфер индикации", flat = !akrosAssociation)
            if (buffer == null) {
                MainBufferResult.Incomplete
            } else {
                val all = if (akrosAssociation) {
                    if (akrosCaptureObis.isEmpty()) {
                        // Без описания колонок значения не с чем сопоставить; этот цикл потерян,
                        // следующее автообновление разберёт буфер полностью.
                        AppLog.w(TAG, "AKROS: описания буфера ещё нет — запрашиваем capture_objects повторно")
                        readCaptureObjects()
                    }
                    compactReadingsOf(buffer, akrosCaptureObis, akrosScalerUnits)
                } else {
                    readingsOf(buffer)
                }
                if (APP_LOGGING_ENABLED) AppLog.d(TAG, "GET-response: разобрано ${all.size} элементов буфера: $all")
                MainBufferResult.Decoded(all)
            }
        } catch (ex: Exception) {
            MainBufferResult.DecodeFailed(ex)
        }
    }

    /**
     * Полноценный сеанс AKROS (порядок сверен с эталонным логом штатной утилиты):
     * SNRM (клиент 32, сервер 1:16) → ожидание UA, в котором согласуются max-info 128/128 →
     * AARQ с низким уровнем безопасности (LLS, пароль «Reader») → чтение описания буфера.
     * Для AKROS ассоциация обязательна, в отличие от РиМ, которого она ломает до конца сессии.
     *
     * @return null при успехе, иначе текст ошибки (вызывающий код рвёт соединение).
     */
    fun establishAkrosSession(): String? {
        AppLog.d(
            TAG,
            "AKROS: SNRM (клиент=$AKROS_CLIENT_ADDRESS, сервер логич.=$AKROS_LOGICAL_ADDRESS, " +
                    "физич.=$AKROS_PHYSICAL_ADDRESS)",
        )
        // Именно Standard-вариант: обычный setNormalResponseMode шлёт кадр с лишним LLC-заголовком и
        // неверной FCS — РиМ это не замечает (не отвечает на SNRM), а AKROS такой кадр отбрасывает.
        val snrmOk = spodes.setNormalResponseModeStandard(
            sourceAddress = AKROS_CLIENT_ADDRESS,
            logicalAddress = AKROS_LOGICAL_ADDRESS,
            physicalAddress = AKROS_PHYSICAL_ADDRESS,
        )
        if (!snrmOk) return "AKROS: не удалось сформировать SNRM-кадр: ${spodes.lastErrorMessage()}"

        val ua = spodes.receiveUnnumberedAcknowledge()
        AppLog.d(TAG, "AKROS: ответ на SNRM = $ua (1=UA, 2=DM, 0=ошибка/таймаут)")
        if (ua != 1) {
            return if (ua == 2) "AKROS: прибор ответил DM — сеанс не открыт"
            else "AKROS: не дождались UA на SNRM (${spodes.lastErrorMessage()})"
        }

        // Низкого уровня достаточно: всё нужное лежит в публичном буфере индикации.
        AppLog.d(TAG, "AKROS: AARQ (низкоуровневая ассоциация, LLS, пароль \"$AKROS_ASSOCIATION_PASSWORD\")")
        val aarqOk = spodes.establishConnection(
            securityLevel = AKROS_SECURITY_LEVEL_LOW,
            password = AKROS_ASSOCIATION_PASSWORD,
        )
        if (!aarqOk) return "AKROS: ассоциация не установлена: ${spodes.lastErrorMessage()}"
        AppLog.i(TAG, "AKROS: ассоциация установлена, можно читать данные")
        readCaptureObjects()
        return null
    }

    /**
     * ACTION к Disconnect Control той же «плоской» адресацией, что и GET.
     *
     * @return true — прибор вернул Action-Result = 0 (команда выполнена).
     */
    fun disconnectControlAction(methodId: Int, label: String): Boolean {
        AppLog.d(TAG, "ACTION-запрос размыкателя ($DISCONNECT_CONTROL_OBIS, class=$DISCONNECT_CONTROL_CLASS_ID Disconnect Control, method=$methodId \"$label\")")
        val sent = spodes.actionRequestFlatAddress(
            classId = DISCONNECT_CONTROL_CLASS_ID,
            obisCode = DISCONNECT_CONTROL_OBIS,
            methodId = methodId,
            hasParameter = true,
            parameterType = 0x0F, // Integer8
            parameterValue = 0,
            destAddress = FLAT_DEST_ADDRESS,
            srcAddress = FLAT_SRC_ADDRESS,
        )
        if (!sent) {
            AppLog.w(TAG, "Размыкатель ($label): запрос не отправлен (${spodes.lastErrorMessage()})")
            return false
        }
        val raw = spodes.getResponseRawBytes()
        if (raw.isEmpty()) {
            AppLog.w(TAG, "Размыкатель ($label): пустой ответ от транспорта (${spodes.lastErrorMessage()})")
            return false
        }
        val result = parseActionResponseResult(raw)
        return when {
            result == null -> {
                AppLog.w(TAG, "Размыкатель ($label): не удалось разобрать ACTION-response")
                false
            }
            result != 0 -> {
                AppLog.w(
                    TAG,
                    "Размыкатель ($label): сервер вернул Action-Result код 0x%02X (%d) — команда НЕ выполнена"
                        .format(result, result),
                )
                false
            }
            else -> {
                AppLog.d(TAG, "Размыкатель ($label): УСПЕХ (Action-Result=0)")
                true
            }
        }
    }

    /**
     * Читает ответ на уже отправленный GET и, если прибор разбил его на блоки, дочитывает
     * остальные и склеивает перед разбором значения.
     *
     * AKROS соглашается на информационное поле 128 байт, поэтому описание колонок и сам буфер
     * приходят блоками («C4 02» с флагом последнего блока вместо «C4 01»). Принять первый блок за
     * весь ответ нельзя: разбор промахивается мимо границ и «успешно» возвращает 0 колонок.
     * Обычный ответ проходит насквозь, так что вызывать можно для любого GET.
     */
    private fun readDlmsValueWithBlocks(label: String): DlmsValue? {
        val first = spodes.getResponseRawBytes()
        if (first.isEmpty()) {
            AppLog.w(TAG, "AKROS: пустой ответ на $label (${spodes.lastErrorMessage()})")
            return null
        }
        return assembleDlmsValue(first, label, flat = false)
    }

    /**
     * Склейка DLMS-блоков ответа, когда первый кадр уже прочитан вызывающим кодом: прибор
     * (AKROS ограничивает информационное поле 128 байтами) отвечает «C4 02» — блок с флагом
     * последнего блока вместо «C4 01». Следующий блок запрашивается get-request-next с номером
     * последнего полученного блока: при [flat] — как у пульта, плоской адресацией, иначе — стандартной
     * (запасной путь AKROS). Обычный (не блочный) ответ проходит насквозь.
     *
     * Неполный ответ не разбираем: обрывок декодировался бы «успешно», но в неверные значения.
     * [MAX_DLMS_BLOCKS] защищает от бесконечного обмена, если прибор перестанет ставить флаг
     * последнего блока.
     */
    private fun assembleDlmsValue(first: ByteArray, label: String, flat: Boolean): DlmsValue? {
        var block = parseGetResponseBlock(first)
            ?: return runCatching { parseGetResponseValue(first) }
                .onFailure { AppLog.w(TAG, "Ответ на $label не разобран: ${it.message}") }
                .getOrNull()

        val data = ByteArrayOutputStream()
        data.write(block.data)
        var blocks = 1
        while (!block.lastBlock && blocks < MAX_DLMS_BLOCKS) {
            val requested = if (flat) {
                spodes.getNextBlockRequestFlatAddress(block.blockNumber)
            } else {
                spodes.sendReadyToReceive(block.blockNumber)
            }
            if (!requested) {
                AppLog.w(TAG, "Запрос блока ${block.blockNumber + 1} ($label) не ушёл: ${spodes.lastErrorMessage()}")
                break
            }
            val raw = spodes.getResponseRawBytes()
            if (raw.isEmpty()) {
                AppLog.w(TAG, "Пустой ответ на блок ${block.blockNumber + 1} ($label) (${spodes.lastErrorMessage()})")
                break
            }
            val next = parseGetResponseBlock(raw)
            if (next == null) {
                AppLog.w(TAG, "Блок ${block.blockNumber + 1} ($label) пришёл в неожиданном формате")
                break
            }
            data.write(next.data)
            block = next
            blocks++
        }

        if (!block.lastBlock) {
            AppLog.w(TAG, "$label получен не полностью — оборвались на блоке ${block.blockNumber} из $blocks")
            return null
        }
        val bytes = data.toByteArray()
        AppLog.d(TAG, "$label собран из $blocks блок(ов), ${bytes.size} байт данных")
        return runCatching { decodeDlmsValue(bytes) }
            .onFailure { AppLog.w(TAG, "$label не разобран после склейки блоков: ${it.message}") }
            .getOrNull()
    }

    /**
     * Читает описание колонок буфера (атрибут 3 «capture_objects» Profile Generic).
     * РиМ присылает значения тройками {OBIS, значение, scaler+unit}, а AKROS — «компактный»
     * буфер: только значения подряд, без OBIS. Смысл колонок хранится в самом приборе, читаем
     * его один раз за сеанс. При неудаче сеанс не рвём — следующее чтение буфера повторит запрос.
     */
    private fun readCaptureObjects() {
        akrosCaptureObis = emptyList()
        akrosScalerUnits = emptyMap()
        val sent = spodes.getRequest(MAIN_BUFFER_CLASS_ID, MAIN_BUFFER_OBIS, PROFILE_CAPTURE_OBJECTS_ATTRIBUTE)
        if (!sent) {
            AppLog.w(TAG, "AKROS: не удалось отправить запрос capture_objects: ${spodes.lastErrorMessage()}")
            return
        }
        val value = readDlmsValueWithBlocks("capture_objects") ?: return
        try {
            val columns = captureColumnsOf(value)
            akrosCaptureObis = columns
            AppLog.i(
                TAG,
                "AKROS: описание буфера получено, ${columns.size} колонок: " +
                        columns.joinToString { c ->
                            c?.let { "${it.obisCode} (${ObisCatalog.nameOf(it.obisCode) ?: "?"}, класс ${it.classId})" } ?: "—"
                        },
            )
            readScalerUnits(columns)
        } catch (ex: Exception) {
            AppLog.e(TAG, "AKROS: не удалось разобрать capture_objects: ${ex.message}", ex)
        }
    }

    /**
     * Читает единицу и множитель (атрибут scaler_unit) каждой колонки буфера: компактный буфер
     * содержит только числа, а без множителя показания могли бы отличаться от настоящих в
     * тысячу раз и выглядеть правдоподобно. Спрашиваем только объекты, у которых этот атрибут
     * есть (см. [ScalerUnit.attributeFor]); единицы за сеанс не меняются, поэтому читаем один раз.
     * Ошибка на одной колонке не фатальна — она остаётся без единицы.
     */
    private fun readScalerUnits(columns: List<CaptureColumn?>) {
        val units = mutableMapOf<String, ScalerUnit>()
        columns.filterNotNull()
            .distinctBy { it.obisCode }
            .forEach { column ->
                val attribute = ScalerUnit.attributeFor(column.classId) ?: return@forEach
                if (!spodes.getRequest(column.classId, column.obisCode, attribute)) {
                    AppLog.w(TAG, "AKROS: запрос scaler_unit для ${column.obisCode} не ушёл: ${spodes.lastErrorMessage()}")
                    return@forEach
                }
                val answer = readDlmsValueWithBlocks("scaler_unit ${column.obisCode}") ?: return@forEach
                val parsed = runCatching { scalerUnitOf(answer) }
                    .onFailure { AppLog.w(TAG, "AKROS: scaler_unit для ${column.obisCode} не разобран: ${it.message}") }
                    .getOrNull()
                if (parsed != null) units[column.obisCode] = parsed
            }
        akrosScalerUnits = units
        AppLog.i(
            TAG,
            "AKROS: единицы измерения получены для ${units.size} из ${columns.count { it != null }} колонок: " +
                    units.entries.joinToString { (obis, su) ->
                        "$obis → ${unitLabelOf(su.unit) ?: "ед.${su.unit}"}${if (su.scaler != 0) " ×10^${su.scaler}" else ""}"
                    },
        )
    }

    private companion object {
        const val TAG = "DlmsSession"

        // «Плоская» однобайтовая адресация штатного пульта (HDLC_DEST_ADDR/HDLC_SRC_ADDR в прошивке).
        const val FLAT_DEST_ADDRESS = 0x03
        const val FLAT_SRC_ADDRESS = 0x43

        /** Буфер индикации — «Profile | Индикация» (класс 7, атрибут 2) в паспортах РиМ и AKROS. */
        const val MAIN_BUFFER_OBIS = "0.0.21.0.2.255"
        const val MAIN_BUFFER_CLASS_ID = 7

        /** Disconnect Control — размыкатель (OBIS из паспорта прибора). */
        const val DISCONNECT_CONTROL_OBIS = "0.0.96.3.10.255"
        const val DISCONNECT_CONTROL_CLASS_ID = 70

        /** Атрибут 3 Profile Generic — capture_objects. */
        const val PROFILE_CAPTURE_OBJECTS_ATTRIBUTE = 3

        /** Предел числа блоков одного ответа; самый длинный нужный ответ укладывается в три. */
        const val MAX_DLMS_BLOCKS = 32

        // AKROS: в эфире — байты 0x41 (клиент) и 0x02 0x21 (сервер); библиотека кодирует
        // переданные числа ровно в них (32→0x41, 1→0x02, 16→0x21).
        const val AKROS_CLIENT_ADDRESS = 32
        const val AKROS_LOGICAL_ADDRESS = 1
        const val AKROS_PHYSICAL_ADDRESS = 16

        /** LLS — проверка по паролю без шифрования (mechanism-name 2.16.756.5.8.2.1). */
        const val AKROS_SECURITY_LEVEL_LOW = 1

        /** Пароль ассоциации чтения из эталонного лога; не путать с PIN BLE-сопряжения. */
        const val AKROS_ASSOCIATION_PASSWORD = "Reader"
    }
}
