package ru.rim.dd.data.mapper

import ru.rim.dd.core.dlms.CLOCK_STATUS_OBIS
import ru.rim.dd.core.dlms.CURRENT_TARIFF_OBIS
import ru.rim.dd.core.dlms.DD_PHASE_DISPLAY_OBIS
import ru.rim.dd.core.dlms.DlmsValue
import ru.rim.dd.core.dlms.ObisReading
import ru.rim.dd.core.dlms.RELAY_CONTROL_STATE_OBIS
import ru.rim.dd.core.dlms.TAMPER_STATUS_OBIS
import ru.rim.dd.core.dlms.decodeCosemDate
import ru.rim.dd.core.dlms.decodeCosemTime
import ru.rim.dd.core.dlms.describeDlmsValue
import ru.rim.dd.core.dlms.isClockStatusValid
import ru.rim.dd.core.dlms.kiloUnitLabelOf
import ru.rim.dd.core.dlms.unitLabelOf
import ru.rim.dd.core.model.EnergyDirection
import ru.rim.dd.core.model.LineVoltages
import ru.rim.dd.core.model.MeterInfo
import ru.rim.dd.core.model.NetworkParams
import ru.rim.dd.core.model.PhaseStatus
import ru.rim.dd.core.model.PhaseValues
import ru.rim.dd.core.model.Reading
import ru.rim.dd.core.model.RelaySource
import ru.rim.dd.core.model.RelayState
import ru.rim.dd.core.model.TamperState
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** Результат разбора сведений о приборе: модель и серийный номер, который прибор сообщил сам. */
data class MeterInfoResult(
    val info: MeterInfo,
    /** Серийный номер из буфера (0.0.96.1.0.255); null — в этом буфере его не было. */
    val reportedSerial: String?,
)

/**
 * Раскладка разобранного буфера индикации (список [ObisReading]) по моделям экранов.
 *
 * Чистые функции без Android, BLE и состояния: на вход — буфер и (где нужно) прежнее значение,
 * на выход — новая модель или null, если в буфере для неё ничего не пришло («не обновлять»).
 * Поэтому весь класс проверяется обычными JUnit-тестами на реальном буфере прибора.
 * Диагностический вывод идёт через необязательный [log], а не напрямую в Logcat.
 */
object MeterBufferMapper {

    /** Дата и время отдельными объектами (у одних приборов оба, у других один, у новых — ни одного). */
    const val DATE_OBIS = "0.0.0.9.2.255"
    const val TIME_OBIS = "0.0.0.9.1.255"
    const val SERIAL_NUMBER_OBIS = "0.0.96.1.0.255"

    /**
     * Версия ПО — единственный код, подтверждённый паспортом. 0.0.96.1.0/1.255 в кандидаты
     * не годятся: это серийный номер и тип прибора, и в поле «Версия ПО» они попали бы молча.
     */
    private val FIRMWARE_VERSION_OBIS_CANDIDATES = listOf("0.0.96.1.2.255")

    /** «Device ID 1» — имя модели текстом («RIM 189.40»). */
    private val MODEL_NAME_OBIS_CANDIDATES = listOf("0.0.96.1.1.255")

    private const val HDLC_P1_OBIS = "0.0.22.0.0.255"
    private const val HDLC_P2_OBIS = "0.1.22.0.0.255"
    private const val HDLC_P3_OBIS = "0.2.22.0.0.255"
    private const val HDLC_P4_OBIS = "0.3.22.0.0.255"
    private const val OPERATING_TIME_OBIS = "0.0.96.8.0.255"

    /** Пустая карточка прибора: до первого разобранного буфера. */
    fun blankMeterInfo(activeSerialNumber: String?): MeterInfo =
        MeterInfo(model = "—", serialNumber = activeSerialNumber ?: "—", firmwareVersion = "—")

    /** Поле буфера по OBIS-коду; при дубликатах берётся первое. */
    private fun List<ObisReading>.byObis(obis: String) = firstOrNull { it.obisCode == obis }

    /**
     * Текст поля буфера. РиМ отдаёт текст как VisibleString, AKROS — как OCTET STRING
     * («1.02» приходит байтами [49,46,48,50]). Октеты принимаются только если все байты
     * печатные: иначе это, например, дата-время, и в «Версию ПО» его подставлять нельзя.
     */
    private fun ObisReading?.asText(): String? {
        val reading = this ?: return null
        reading.textValue?.let { return it }
        val bytes = reading.octetValue ?: return null
        if (bytes.isEmpty() || bytes.any { (it.toInt() and 0xFF) !in 0x20..0x7E }) return null
        return String(bytes, Charsets.US_ASCII).trim().takeIf { it.isNotEmpty() }
    }

    /**
     * control_state размыкателя приходит позиционным полем буфера: прямое чтение
     * Disconnect Control без ассоциации отвечает 0x0D. Значения: 0=disconnected, 1=connected,
     * 2=ready_for_reconnection. null — поля в буфере нет.
     */
    fun relayState(all: List<ObisReading>, prev: RelayState): RelayState? {
        val controlState = all.byObis(RELAY_CONTROL_STATE_OBIS)?.rawValue ?: return null
        return prev.copy(
            isOn = controlState == 1L,
            remoteTurnOnAllowed = controlState == 2L,
            source = RelaySource.METER_READ,
        )
    }

    /** Пломбы и датчики — позиционное поле «Значки ДД» (раскладка бит — в [TamperState]). */
    fun tamperState(all: List<ObisReading>): TamperState? {
        val status = all.byObis(TAMPER_STATUS_OBIS)?.rawValue ?: return null
        return TamperState.decode(status)
    }

    /**
     * Накопленная энергия по тарифам. null — в буфере нет ни одного энергетического кода.
     * Показания за предыдущий период (F=101 и старые D=17) помечаются [Reading.isRdch].
     */
    fun energyReadings(all: List<ObisReading>, now: Instant): List<Reading>? {
        val energy = all.filter { r ->
            val g = r.obisCode.split(".")
            // C=1..4 — активная/реактивная энергия; D=8 — накопленная (F=255 текущий период,
            // F=101 расчётный день), D=17 — старые коды РДЧ прежних версий ПО прибора.
            g.size == 6 && (g[2].toIntOrNull() in 1..4) &&
                    ((g[3] == "8" && (g[5] == "255" || g[5] == "101")) || g[3] == "17")
        }
        if (energy.isEmpty()) return null
        return energy.map {
            Reading(
                obisId = it.obisCode,
                tariff = it.tariff,
                valueKwh = it.valueKwh,
                timestamp = now,
                // valueKwh получен делением на 1000, поэтому единицу прибора (Вт·ч) переводим в «кило».
                unitLabel = kiloUnitLabelOf(it.unit),
                isRdch = it.obisCode.split(".").let { g -> g.getOrNull(5) == "101" || g.getOrNull(3) == "17" },
            )
        }
    }

    /**
     * Однофазные приборы (189.xx, AKROS) отдают напряжение и ток под суммарными OBIS
     * (1.0.12.7 / 1.0.11.7), трёхфазный 489 — только пофазными (1.0.32.7 / 52.7 / 72.7).
     * Берём и те и другие: total у трёхфазного — L1, чтобы поле не пустовало.
     * null — нет ни напряжения, ни тока, ни мощности.
     */
    fun networkParams(all: List<ObisReading>): NetworkParams? {
        fun phase(total: String, l1: String, l2: String, l3: String, divide: Double = 1.0): PhaseValues? {
            val t = all.byObis(total)?.value
            val a = all.byObis(l1)?.value
            val b = all.byObis(l2)?.value
            val c = all.byObis(l3)?.value
            if (t == null && a == null && b == null && c == null) return null
            return PhaseValues(
                total = (t ?: a ?: 0.0) / divide,
                l1 = a?.div(divide),
                l2 = b?.div(divide),
                l3 = c?.div(divide),
            )
        }

        val voltage = phase("1.0.12.7.0.255", "1.0.32.7.0.255", "1.0.52.7.0.255", "1.0.72.7.0.255")
        val current = phase("1.0.11.7.0.255", "1.0.31.7.0.255", "1.0.51.7.0.255", "1.0.71.7.0.255")
        // Мощности прибор отдаёт в Вт/вар/ВА, на экране — кВт/квар/кВА.
        val power = phase("1.0.1.7.0.255", "1.0.21.7.0.255", "1.0.41.7.0.255", "1.0.61.7.0.255", divide = 1000.0)
        val reactivePower = phase("1.0.3.7.0.255", "1.0.23.7.0.255", "1.0.43.7.0.255", "1.0.63.7.0.255", divide = 1000.0)
        val apparentPower = phase("1.0.9.7.0.255", "1.0.29.7.0.255", "1.0.49.7.0.255", "1.0.69.7.0.255", divide = 1000.0)
        val cosPhi = phase("1.0.13.7.0.255", "1.0.33.7.0.255", "1.0.53.7.0.255", "1.0.73.7.0.255")

        // Линейные напряжения: 1.0.124/125/126.7, у прежних версий ПО — 1.0.12.7.1/2/3.
        val newL12 = all.byObis("1.0.124.7.0.255")?.value
        val newL23 = all.byObis("1.0.125.7.0.255")?.value
        val newL13 = all.byObis("1.0.126.7.0.255")?.value
        val oldL12 = all.byObis("1.0.12.7.1.255")?.value
        val oldL23 = all.byObis("1.0.12.7.2.255")?.value
        val oldL13 = all.byObis("1.0.12.7.3.255")?.value
        val lineVoltages = when {
            newL12 != null || newL23 != null || newL13 != null -> LineVoltages(newL12, newL23, newL13)
            oldL12 != null || oldL23 != null || oldL13 != null -> LineVoltages(oldL12, oldL23, oldL13, legacyCodes = true)
            else -> null
        }

        if (voltage == null && current == null && power == null) return null
        return NetworkParams(
            voltage = voltage ?: PhaseValues(total = 0.0),
            current = current ?: PhaseValues(total = 0.0),
            power = power ?: PhaseValues(total = 0.0),
            frequencyHz = all.byObis("1.0.14.7.0.255")?.value,
            reactivePowerKvar = reactivePower?.total,
            apparentPowerKva = apparentPower?.total,
            neutralCurrentA = all.byObis("1.0.91.7.0.255")?.value,
            cosPhi = cosPhi,
            lineVoltages = lineVoltages,
        )
    }

    /** Разобранный date-time (12 байт) объекта часов 0.0.1.0.0.255; любая часть может быть null. */
    private class ClockFields(val date: LocalDate?, val time: LocalTime?, val status: Int?)

    /**
     * date-time объекта часов 0.0.1.0.0.255 (12 байт): год(2), месяц, день, день недели, часы,
     * минуты, секунды, сотые, отклонение(2), статус. Год 0xFFFF — «не задан» (как в прошивке
     * пульта) — такие часы пропускаются; статус 0xFF — «не указан». Дату читает [decodeCosemDate]
     * (байты 0–4), время — [decodeCosemTime] (байты 5–8).
     */
    private fun clockFieldsOf(bytes: ByteArray?): ClockFields? {
        if (bytes == null || bytes.size != 12) return null
        if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFF.toByte()) return null
        val status = (bytes[11].toInt() and 0xFF).takeIf { it != 0xFF }
        return ClockFields(
            date = decodeCosemDate(bytes.copyOfRange(0, 5)),
            time = decodeCosemTime(bytes.copyOfRange(5, 9)),
            status = status,
        )
    }

    /**
     * Сведения о приборе. Поля, не пришедшие в этом цикле, сохраняют прежнее значение из [prev];
     * OBIS-подпись хранится вместе со значением, чтобы не отвязаться от источника.
     *
     * @param activeSerialNumber номер, разобранный из BLE-имени (запасной вариант).
     * @param knownReportedSerial номер, о котором прибор уже сообщал (только для лога).
     * @return null — в буфере нет ни одного поля сведений.
     */
    fun meterInfo(
        all: List<ObisReading>,
        prev: MeterInfo?,
        activeSerialNumber: String?,
        knownReportedSerial: String?,
        now: Instant,
        log: (String) -> Unit = {},
    ): MeterInfoResult? {
        val temperatureC = all.byObis("0.0.96.9.0.255")?.value
        val backupVoltageV = all.byObis("0.0.96.6.3.255")?.value

        // Дата и время прибора. Одни приборы присылают их отдельными объектами (0.0.0.9.2.255 дата,
        // 0.0.0.9.1.255 время — иногда только один из двух), современные — одним объектом часов
        // 0.0.1.0.0.255. Каждая часть берётся независимо: отдельный объект приоритетнее (как в
        // прошивке пульта), недостающая часть добирается из часов 0.0.1.0.0.255. OBIS-источник
        // запоминается для подписи на экране.
        val separateDate = all.byObis(DATE_OBIS)?.octetValue?.let { decodeCosemDate(it) }
        val separateTime = all.byObis(TIME_OBIS)?.octetValue?.let { decodeCosemTime(it) }
        val clock = clockFieldsOf(all.firstOrNull { it.obisCode == CLOCK_STATUS_OBIS && it.octetValue != null }?.octetValue)
        val deviceDate = separateDate ?: clock?.date
        val deviceDateObis = when {
            separateDate != null -> DATE_OBIS
            clock?.date != null -> CLOCK_STATUS_OBIS
            else -> null
        }
        val deviceTime = separateTime ?: clock?.time
        val deviceTimeObis = when {
            separateTime != null -> TIME_OBIS
            clock?.time != null -> CLOCK_STATUS_OBIS
            else -> null
        }

        // Состав буфера зависит от модели; текстовые поля логируем, чтобы версию ПО под
        // незнакомым OBIS было видно сразу.
        val textReadings = all.filter { it.textValue != null }
        if (textReadings.isNotEmpty()) {
            log("В буфере найдены текстовые поля: ${textReadings.joinToString { "${it.obisCode}=\"${it.textValue}\"" }}")
        }
        val firmwareEntry = FIRMWARE_VERSION_OBIS_CANDIDATES
            .firstNotNullOfOrNull { obis -> all.byObis(obis).asText()?.let { obis to it } }
        val firmwareVersion = firmwareEntry?.second
        // Имя модели из буфера надёжнее разбора BLE-имени, которое остаётся запасным вариантом.
        val modelEntry = MODEL_NAME_OBIS_CANDIDATES
            .firstNotNullOfOrNull { obis -> all.byObis(obis).asText()?.let { obis to it } }
        val modelName = modelEntry?.second

        val serialFromBuffer = all.byObis(SERIAL_NUMBER_OBIS).asText()?.trim()?.takeIf { it.isNotEmpty() }
        if (serialFromBuffer != null && serialFromBuffer != knownReportedSerial) {
            log("Прибор сообщил свой серийный номер ($SERIAL_NUMBER_OBIS): \"$serialFromBuffer\"")
        }

        val currentTariff = all.byObis(CURRENT_TARIFF_OBIS)?.rawValue?.toInt()
        // Тот же OBIS у AKROS несёт ещё и сами часы (octetValue != null) — статус только у позиционного поля.
        // Достоверность часов: статус часов (позиционное поле буфера) либо, если его нет, байт статуса
        // из date-time объекта 0.0.1.0.0.255.
        val clockValid = (all.firstOrNull { it.obisCode == CLOCK_STATUS_OBIS && it.octetValue == null }?.rawValue
            ?: clock?.status?.toLong())?.let { isClockStatusValid(it) }

        // Раскладка «как в прошивке пульта»: направление энергии — байт 3 поля «Значки ДД»,
        // статус фаз — отдельное позиционное поле.
        val energyDirection = all.byObis(TAMPER_STATUS_OBIS)?.rawValue?.let { EnergyDirection.decode(it) }
        val phaseStatus = all.byObis(DD_PHASE_DISPLAY_OBIS)?.rawValue?.let { PhaseStatus.decode(it) }
        if (phaseStatus != null) {
            log("Буфер: статус фаз ($DD_PHASE_DISPLAY_OBIS) = $phaseStatus, направление энергии = $energyDirection")
        }

        val rdchDate = all.byObis("0.0.0.9.2.101")?.octetValue?.let { decodeCosemDate(it) }
        val rdchTime = all.byObis("0.0.0.9.1.101")?.octetValue?.let { decodeCosemTime(it) }
        val rdchDateTime = rdchDate?.let { LocalDateTime.of(it, rdchTime ?: LocalTime.MIDNIGHT) }

        val hdlcP1 = all.byObis(HDLC_P1_OBIS)?.let { describeHdlcSetup(it, log) }
        val hdlcP2 = all.byObis(HDLC_P2_OBIS)?.let { describeHdlcSetup(it, log) }
        val hdlcP3 = all.byObis(HDLC_P3_OBIS)?.let { describeHdlcSetup(it, log) }
        val hdlcP4 = all.byObis(HDLC_P4_OBIS)?.let { describeHdlcSetup(it, log) }
        val operatingTimeReading = all.byObis(OPERATING_TIME_OBIS)
        val operatingTime = operatingTimeReading?.value
        val operatingTimeUnit = unitLabelOf(operatingTimeReading?.unit)

        if (temperatureC == null && backupVoltageV == null && deviceDate == null && deviceTime == null && firmwareVersion == null &&
            modelName == null && currentTariff == null && clockValid == null && serialFromBuffer == null &&
            hdlcP1 == null && hdlcP2 == null && hdlcP3 == null && hdlcP4 == null && operatingTime == null &&
            rdchDateTime == null && phaseStatus == null && energyDirection == null
        ) return null

        val info = (prev ?: blankMeterInfo(activeSerialNumber)).copy(
            model = modelName ?: prev?.model ?: "—",
            // Номер от самого прибора важнее разобранного из BLE-имени.
            serialNumber = serialFromBuffer ?: activeSerialNumber ?: prev?.serialNumber ?: "—",
            temperatureC = temperatureC,
            backupVoltageV = backupVoltageV,
            deviceDate = deviceDate,
            deviceDateObis = deviceDateObis,
            deviceTime = deviceTime,
            deviceTimeObis = deviceTimeObis,
            lastSeenAt = now,
            firmwareVersion = firmwareVersion ?: prev?.firmwareVersion ?: "—",
            currentTariff = currentTariff ?: prev?.currentTariff,
            clockValid = clockValid ?: prev?.clockValid,
            modelObis = modelEntry?.first ?: prev?.modelObis,
            firmwareVersionObis = firmwareEntry?.first ?: prev?.firmwareVersionObis,
            serialNumberObis = if (serialFromBuffer != null) SERIAL_NUMBER_OBIS else prev?.serialNumberObis,
            hdlcP1 = hdlcP1 ?: prev?.hdlcP1,
            hdlcP2 = hdlcP2 ?: prev?.hdlcP2,
            hdlcP3 = hdlcP3 ?: prev?.hdlcP3,
            hdlcP4 = hdlcP4 ?: prev?.hdlcP4,
            operatingTime = operatingTime ?: prev?.operatingTime,
            operatingTimeUnit = operatingTimeUnit ?: prev?.operatingTimeUnit,
            rdchDateTime = rdchDateTime ?: prev?.rdchDateTime,
            phaseStatus = phaseStatus ?: prev?.phaseStatus,
            energyDirection = energyDirection ?: prev?.energyDirection,
        )
        return MeterInfoResult(info, serialFromBuffer)
    }

    /**
     * Текст для объекта HDLC-настройки (класс 23): скорость (enum атрибута 2) и адрес устройства
     * (атрибут 8). Формат значения в буфере у разных приборов может отличаться, поэтому разбор
     * терпимый: Structure → enum как скорость, число как адрес; OctetString → hex; иначе число.
     * Сырое значение пишется в [log], чтобы можно было уточнить раскладку по реальному ответу.
     */
    internal fun describeHdlcSetup(r: ObisReading, log: (String) -> Unit = {}): String {
        val items = r.structItems
        val text = when {
            // Раскладка буфера: [speed, window_tx, window_rx, max_info_tx, max_info_rx,
            // inter_octet_timeout, inactivity_timeout, device_address].
            items != null && items.size >= 8 && items.first() is DlmsValue.EnumVal -> {
                val speed = hdlcSpeedLabel((items.first() as DlmsValue.EnumVal).value)
                val address = when (val last = items.last()) {
                    is DlmsValue.U16 -> last.value.toLong()
                    is DlmsValue.U32 -> last.value
                    is DlmsValue.U8 -> last.value.toLong()
                    else -> null
                }
                if (address != null) "$speed, адрес $address" else speed
            }
            items != null -> items.joinToString(", ") { describeDlmsValue(it) }
            r.textValue != null -> r.textValue
            r.octetValue != null -> r.octetValue.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
            else -> r.rawValue.toString()
        }
        log("HDLC ${r.obisCode}: атрибуты=${items?.joinToString(prefix = "[", postfix = "]") { describeDlmsValue(it) } ?: "raw=${r.rawValue}"} → \"$text\"")
        return text
    }

    /** Значение enum comm_speed из IEC HDLC setup (IEC 62056-6-2, класс 23) в бодах. */
    internal fun hdlcSpeedLabel(code: Int): String {
        val baud = when (code) {
            0 -> 300
            1 -> 600
            2 -> 1200
            3 -> 2400
            4 -> 4800
            5 -> 9600
            6 -> 19200
            7 -> 38400
            8 -> 57600
            9 -> 115200
            else -> return "скорость код $code"
        }
        return "$baud бод"
    }
}