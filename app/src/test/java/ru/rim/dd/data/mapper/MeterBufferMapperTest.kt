package ru.rim.dd.data.mapper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.rim.dd.core.dlms.kiloUnitLabelOf
import ru.rim.dd.core.model.MeterInfo
import ru.rim.dd.core.model.RelaySource
import ru.rim.dd.core.model.RelayState
import ru.rim.dd.core.model.TamperState
import ru.rim.dd.testutil.RealMeterBuffer
import java.time.Instant
import ru.rim.dd.core.dlms.ObisReading
import ru.rim.dd.core.dlms.CLOCK_STATUS_OBIS
import java.time.LocalDate
import java.time.LocalDateTime
import ru.rim.dd.core.dlms.DlmsValue
import ru.rim.dd.core.dlms.DD_PHASE_DISPLAY_OBIS
import ru.rim.dd.core.model.PhaseStatus

/**
 * Проверка разбора буфера индикации на реальном ответе прибора (3-кадровый HDLC-ответ на
 * GET 0.0.21.0.2.255, снятый с трёхфазного РиМ). Кадры собираются так же, как это делает
 * приложение: из каждого берётся информационное поле, поля склеиваются и разбираются целиком.
 */
class MeterBufferMapperTest {

    private val now = Instant.parse("2026-10-15T12:00:00Z")

    private fun realBuffer() = RealMeterBuffer.readings()

    private fun relay0() = RelayState(isOn = false, powerLimitKw = 0.0, source = RelaySource.UNKNOWN)

    @Test
    fun realBuffer_isFullyDecoded() {
        val all = realBuffer()
        assertEquals(22, all.size)
        assertTrue(all.any { it.obisCode == "1.0.1.8.0.255" })
        assertTrue(all.none { it.obisCode.startsWith("1.0.0.6.") })
    }

    @Test
    fun relayState_readsControlStateFromBuffer() {
        // control_state = 2 (ready_for_reconnection): реле выключено, но включение разрешено
        val relay = MeterBufferMapper.relayState(realBuffer(), relay0())
        assertNotNull(relay)
        assertFalse(relay!!.isOn)
        assertTrue(relay.remoteTurnOnAllowed)
        assertEquals(RelaySource.METER_READ, relay.source)
    }

    @Test
    fun relayState_nullWhenBufferHasNoField() {
        assertNull(MeterBufferMapper.relayState(emptyList(), relay0()))
    }

    @Test
    fun tamperState_decodesStatusField() {
        assertEquals(TamperState.decode(826871552L), MeterBufferMapper.tamperState(realBuffer()))
        assertNull(MeterBufferMapper.tamperState(emptyList()))
    }

    @Test
    fun energyReadings_splitCurrentAndRdch() {
        val readings = MeterBufferMapper.energyReadings(realBuffer(), now)!!
        assertEquals(9, readings.size)
        assertEquals(5, readings.count { !it.isRdch })
        assertEquals(4, readings.count { it.isRdch })

        val t1 = readings.first { it.obisId == "1.0.1.8.1.255" }
        assertEquals(1, t1.tariff)
        assertEquals(1.74, t1.valueKwh, 1e-9)
        assertFalse(t1.isRdch)
        assertEquals(kiloUnitLabelOf(30), t1.unitLabel)

        val total = readings.first { it.obisId == "1.0.1.8.0.255" }
        assertNull(total.tariff)
        assertEquals(1.74, total.valueKwh, 1e-9)

        val reactive = readings.first { it.obisId == "1.0.4.8.0.255" }
        assertEquals(0.41, reactive.valueKwh, 1e-9)
        assertEquals(now, reactive.timestamp)

        assertTrue(readings.first { it.obisId == "1.0.1.8.0.101" }.isRdch)
    }

    @Test
    fun energyReadings_nullWithoutEnergyCodes() {
        assertNull(MeterBufferMapper.energyReadings(emptyList(), now))
    }

    @Test
    fun networkParams_realBufferHasNoVoltageButHasCurrentAndPower() {
        val net = MeterBufferMapper.networkParams(realBuffer())
        assertNotNull(net)
        // 1.0.12.7 / 32.7 / 52.7 / 72.7 в этом буфере нет — напряжение остаётся нулевым
        assertEquals(0.0, net!!.voltage.total, 0.0)
        assertNull(net.voltage.l1)
        // ток пришёл по фазам L2 (51.7) и L1 (31.7)
        assertEquals(0.0, net.current.l1!!, 0.0)
        assertEquals(0.0, net.current.l2!!, 0.0)
        assertNull(net.current.l3)
        // мощности — все четыре кода (сумма и три фазы)
        assertEquals(0.0, net.power.l3!!, 0.0)
        assertNull(net.frequencyHz)
        assertNull(net.lineVoltages)
    }

    @Test
    fun networkParams_nullWhenNothingMeasured() {
        assertNull(MeterBufferMapper.networkParams(emptyList()))
    }

    @Test
    fun meterInfo_realBuffer() {
        val result = MeterBufferMapper.meterInfo(
            all = realBuffer(),
            prev = null,
            activeSerialNumber = "04314652",
            knownReportedSerial = null,
            now = now,
        )
        assertNotNull(result)
        val info = result!!.info
        assertEquals(28.34, info.temperatureC!!, 1e-9)
        assertEquals(3.637, info.backupVoltageV!!, 1e-9)
        assertEquals(1, info.currentTariff)
        assertEquals(true, info.clockValid)
        assertTrue(info.phaseStatus!!.threePhase)
        assertEquals(now, info.lastSeenAt)
        // серийный номер в буфере не пришёл — берётся номер из BLE-имени
        assertNull(result.reportedSerial)
        assertEquals("04314652", info.serialNumber)
        assertEquals("—", info.model)
    }

    @Test
    fun meterInfo_keepsPreviousValuesForMissingFields() {
        val prev = MeterBufferMapper.blankMeterInfo("04314652")
            .copy(model = "489", firmwareVersion = "1.02", operatingTime = 12.0, operatingTimeUnit = "ч")
        val info = MeterBufferMapper.meterInfo(realBuffer(), prev, "04314652", null, now)!!.info
        assertEquals("489", info.model)
        assertEquals("1.02", info.firmwareVersion)
        assertEquals(12.0, info.operatingTime!!, 0.0)
        assertEquals("ч", info.operatingTimeUnit)
    }

    @Test
    fun meterInfo_nullWhenBufferHasNoInfoFields() {
        assertNull(MeterBufferMapper.meterInfo(emptyList(), null, null, null, now))
    }

    @Test
    fun blankMeterInfo_usesActiveSerialOrDash() {
        val blank: MeterInfo = MeterBufferMapper.blankMeterInfo(null)
        assertEquals("—", blank.serialNumber)
        assertEquals("12345678", MeterBufferMapper.blankMeterInfo("12345678").serialNumber)
    }

    @Test
    fun hdlcSpeedLabel_knownAndUnknownCodes() {
        assertEquals("9600 бод", MeterBufferMapper.hdlcSpeedLabel(5))
        assertEquals("115200 бод", MeterBufferMapper.hdlcSpeedLabel(9))
        assertEquals("скорость код 42", MeterBufferMapper.hdlcSpeedLabel(42))
    }

    //доп
    //на покрытие
    //hdlcSpeedLabel
    @Test
    fun hdlcSpeedLabel_knownAndUnknownCodesAll() {
        assertEquals("300 бод", MeterBufferMapper.hdlcSpeedLabel(0))
        assertEquals("600 бод", MeterBufferMapper.hdlcSpeedLabel(1))
        assertEquals("1200 бод", MeterBufferMapper.hdlcSpeedLabel(2))
        assertEquals("2400 бод", MeterBufferMapper.hdlcSpeedLabel(3))
        assertEquals("4800 бод", MeterBufferMapper.hdlcSpeedLabel(4))
        assertEquals("19200 бод", MeterBufferMapper.hdlcSpeedLabel(6))
        assertEquals("38400 бод", MeterBufferMapper.hdlcSpeedLabel(7))
        assertEquals("57600 бод", MeterBufferMapper.hdlcSpeedLabel(8))
    }
    //на ошибки
    @Test
    fun hdlcSpeedLabel_Negative() {
        //скорость может быть отриуательной? Нет
        assertNotEquals("скорость код -12", MeterBufferMapper.hdlcSpeedLabel(-12))
    }

    @Test
    fun hdlcSpeedLabel_Overflow() {
        //скорость может быть отриуательной? Нет
        val trash: Int = Int.MAX_VALUE + 1;
        assertNotEquals("скорость код -2147483648", MeterBufferMapper.hdlcSpeedLabel(trash))
    }

    //покрытие energyReadings
    @Test
    fun energyReadings_filterBranches() {
        fun r(obis: String) = ObisReading(obisCode = obis, rawValue = 1000, scaler = 0, unit = 30)
        val all = listOf(
            r("1.0.1.8.0.255"),   // A+ сумма (g[2]=1, g[3]=8, g[5]=255)
            r("1.0.4.8.1.255"),   // R- Т1 (g[2]=4, g[5]=255)
            r("1.0.9.8.0.255"),   //  g[2]=9 вне 1..4
            r("1.0.X.8.0.255"),   //  g[2] не число
            r("1.0.1"),           // size != 6
            r("1.0.1.8.0.100"),   // F=100 не 255/101
            r("1.0.1.17.0.255"),  // РДЧ старого формата (g[3]=17)
            r("1.0.4.8.0.101"),   //  РДЧ (F=101)
            r("1.0.1.9.0.255"),   //  g[3]=9 (не 8 и не 17)
        )
        val result = MeterBufferMapper.energyReadings(all, now)!!

        assertEquals(4, result.size)
        assertEquals(
            listOf("1.0.1.8.0.255", "1.0.4.8.1.255", "1.0.1.17.0.255", "1.0.4.8.0.101"),
            result.map { it.obisId },
        )
        // isRdch: F=101 или D=17
        assertFalse(result.first { it.obisId == "1.0.1.8.0.255" }.isRdch)
        assertFalse(result.first { it.obisId == "1.0.4.8.1.255" }.isRdch)
        assertTrue(result.first { it.obisId == "1.0.1.17.0.255" }.isRdch)
        assertTrue(result.first { it.obisId == "1.0.4.8.0.101" }.isRdch)
    }

    //покрытие networkParams
    @Test
    fun networkParams_totalFallsBackToL1() {
        // Нет total (1.0.12.7), есть только L1 (1.0.32.7)
        val all = listOf(
            ObisReading(obisCode = "1.0.32.7.0.255", rawValue = 2300, scaler = -1, unit = 35), // 230.0
        )
        val net = MeterBufferMapper.networkParams(all)!!
        assertEquals(230.0, net.voltage.total, 1e-9)   // total = a (fallback)
        assertEquals(230.0, net.voltage.l1!!, 1e-9)
        assertNull(net.voltage.l2)
        assertNull(net.voltage.l3)
    }

    @Test
    fun networkParams_totalIsZeroWhenOnlyPower() {
        // Есть только мощность - voltage/current = PhaseValues(0.0)
        val all = listOf(
            ObisReading(obisCode = "1.0.1.7.0.255", rawValue = 1000, scaler = 0, unit = 27), // 1000 Вт
        )
        val net = MeterBufferMapper.networkParams(all)!!
        assertEquals(0.0, net.voltage.total, 1e-9)
        assertEquals(0.0, net.current.total, 1e-9)
        assertEquals(1.0, net.power.total, 1e-9)  // 1000 / 1000
    }

    @Test
    fun networkParams_partialPhases() {
        // L1 есть, L2 нет, L3 есть
        val all = listOf(
            ObisReading(obisCode = "1.0.12.7.0.255", rawValue = 2300, scaler = -1, unit = 35),
            ObisReading(obisCode = "1.0.32.7.0.255", rawValue = 2300, scaler = -1, unit = 35),
            ObisReading(obisCode = "1.0.72.7.0.255", rawValue = 2298, scaler = -1, unit = 35),
        )
        val net = MeterBufferMapper.networkParams(all)!!
        assertEquals(230.0, net.voltage.l1!!, 1e-9)
        assertNull(net.voltage.l2)               // ветка b == null
        assertEquals(229.8, net.voltage.l3!!, 1e-9)
    }


    //покрытие добавить еще!!! (линейное напряжение)
    @Test
    fun networkParams_lineVoltagesNewCodes() {
        val all = listOf(
            ObisReading(obisCode = "1.0.12.7.0.255", rawValue = 2300, scaler = -1, unit = 35),
            ObisReading(obisCode = "1.0.124.7.0.255", rawValue = 4000, scaler = -1, unit = 35),
        )
        val net = MeterBufferMapper.networkParams(all)!!
        assertNotNull(net.lineVoltages)
        assertEquals(400.0, net.lineVoltages!!.l12!!, 1e-9)
        assertFalse(net.lineVoltages!!.legacyCodes)
    }

    @Test
    fun networkParams_lineVoltagesLegacyCodes() {
        val all = listOf(
            ObisReading(obisCode = "1.0.12.7.0.255", rawValue = 2300, scaler = -1, unit = 35),
            ObisReading(obisCode = "1.0.12.7.1.255", rawValue = 4000, scaler = -1, unit = 35),
        )
        val net = MeterBufferMapper.networkParams(all)!!
        assertTrue(net.lineVoltages!!.legacyCodes)
        assertEquals(400.0, net.lineVoltages!!.l12!!, 1e-9)
    }

    @Test
    fun networkParams_lineVoltagesNullWhenAbsent() {
        val all = listOf(
            ObisReading(obisCode = "1.0.12.7.0.255", rawValue = 2300, scaler = -1, unit = 35),
        )
        assertNull(MeterBufferMapper.networkParams(all)!!.lineVoltages)
    }

    // попробовать с приватной, не выйдет пох

    private fun clockBytes(year: Int = 2026, status: Int = 0x00): ByteArray {
        val y = year
        return byteArrayOf(
            (y shr 8).toByte(), (y and 0xFF).toByte(),
            10, 15, 0,          // month, day, dayOfWeek
            12, 0, 0, 0,        // hour, min, sec, hundredths
            0, 0,               // deviation
            status.toByte(),
        )
    }

    @Test
    fun clockFields_yearFFFF_ignoredButStatusStillUsed() {
        val bytes = clockBytes(year = 0xFFFF, status = 0xFF)
        val info = MeterBufferMapper.meterInfo(
            all = listOf(ObisReading(obisCode = CLOCK_STATUS_OBIS, rawValue = 0, scaler = 0, unit = null, octetValue = bytes)),
            prev = null, activeSerialNumber = null, knownReportedSerial = null, now = now,
        )
        // clock.date = null, clock.time = null, clock.status = null - всё null - meterInfo = null
        assertNull(info)
    }

    @Test
    fun clockFields_statusFF_meansNull() {
        val bytes = clockBytes(year = 2026, status = 0xFF)
        val info = MeterBufferMapper.meterInfo(
            all = listOf(ObisReading(obisCode = CLOCK_STATUS_OBIS, rawValue = 0, scaler = 0, unit = null, octetValue = bytes)),
            prev = null, activeSerialNumber = null, knownReportedSerial = null, now = now,
        )
        // date/time есть, status = null - clockValid = null
        assertNotNull(info)
        assertEquals(LocalDate.of(2026, 10, 15), info!!.info.deviceDate)
        assertNull(info.info.clockValid)
    }

    @Test
    fun clockFields_wrongSize_ignored() {
        val bytes = ByteArray(5) { 0 }
        val info = MeterBufferMapper.meterInfo(
            all = listOf(ObisReading(obisCode = CLOCK_STATUS_OBIS, rawValue = 0, scaler = 0, unit = null, octetValue = bytes)),
            prev = null, activeSerialNumber = null, knownReportedSerial = null, now = now,
        )
        assertNull(info)  // size != 12 - clockFields = null - всё null
    }

    @Test
    fun clockFields_statusDecoded() {
        val bytes = clockBytes(year = 2026, status = 0x00)
        val info = MeterBufferMapper.meterInfo(
            all = listOf(ObisReading(obisCode = CLOCK_STATUS_OBIS, rawValue = 0, scaler = 0, unit = null, octetValue = bytes)),
            prev = null, activeSerialNumber = null, knownReportedSerial = null, now = now,
        )
        assertNotNull(info!!.info.clockValid)  // status=0 - isClockStatusValid(0) = true (обычно)
    }

    private fun dateBytes(y: Int = 2026, m: Int = 10, d: Int = 15): ByteArray =
        byteArrayOf((y shr 8).toByte(), (y and 0xFF).toByte(), m.toByte(), d.toByte(), 0)

    private fun timeBytes(h: Int = 12, min: Int = 30): ByteArray =
        byteArrayOf(h.toByte(), min.toByte(), 0, 0)

    @Test
    fun rdchDateTime_onlyDate_usesMidnight() {
        val all = listOf(
            ObisReading(obisCode = "0.0.0.9.2.101", rawValue = 0, scaler = 0, unit = null, octetValue = dateBytes()),
        )
        val info = MeterBufferMapper.meterInfo(all, null, null, null, now)!!
        assertEquals(LocalDateTime.of(2026, 10, 15, 0, 0), info.info.rdchDateTime)
    }

    @Test
    fun rdchDateTime_onlyTime_ignored() {
        val all = listOf(
            ObisReading(obisCode = "0.0.0.9.1.101", rawValue = 0, scaler = 0, unit = null, octetValue = timeBytes()),
        )
        // Время без даты не даёт rdchDateTime. Но даёт ли оно info?
        // В большом if: rdchDateTime == null, но deviceTime == null (это .101, не .255)
        // - скорее всего info = null.
        val info = MeterBufferMapper.meterInfo(all, null, null, null, now)
        assertNull(info)
    }

    @Test
    fun rdchDateTime_bothDateAndTime() {
        val all = listOf(
            ObisReading(obisCode = "0.0.0.9.2.101", rawValue = 0, scaler = 0, unit = null, octetValue = dateBytes()),
            ObisReading(obisCode = "0.0.0.9.1.101", rawValue = 0, scaler = 0, unit = null, octetValue = timeBytes()),
        )
        val info = MeterBufferMapper.meterInfo(all, null, null, null, now)!!
        assertEquals(LocalDateTime.of(2026, 10, 15, 12, 30), info.info.rdchDateTime)
    }

    @Test
    fun describeHdlcSetup_structWithSpeedAndAddress() {
        val items = listOf(
            DlmsValue.EnumVal(5),      // 9600
            DlmsValue.U32(0), DlmsValue.U32(0),
            DlmsValue.U16(0), DlmsValue.U16(0),
            DlmsValue.U16(0), DlmsValue.U16(0),
            DlmsValue.U16(16),         // адрес
        )
        val r = ObisReading(obisCode = "0.0.22.0.0.255", rawValue = 0, scaler = 0, unit = null, structItems = items)
        assertEquals("9600 бод, адрес 16", MeterBufferMapper.describeHdlcSetup(r))
    }

    @Test
    fun describeHdlcSetup_structWithoutAddress() {
        val items = listOf(
            DlmsValue.EnumVal(5),
            DlmsValue.U32(0), DlmsValue.U32(0),
            DlmsValue.U16(0), DlmsValue.U16(0),
            DlmsValue.U16(0), DlmsValue.U16(0),
            DlmsValue.BoolVal(true),     // не U16/U32/U8 - address = null
        )
        val r = ObisReading(obisCode = "0.0.22.0.0.255", rawValue = 0, scaler = 0, unit = null, structItems = items)
        assertEquals("9600 бод", MeterBufferMapper.describeHdlcSetup(r))
    }

    @Test
    fun describeHdlcSetup_shortStruct() {
        val items = listOf(DlmsValue.U8(1), DlmsValue.U8(2))
        val r = ObisReading(obisCode = "0.0.22.0.0.255", rawValue = 0, scaler = 0, unit = null, structItems = items)
        // items.size < 8 → joinToString через describeDlmsValue
        val text = MeterBufferMapper.describeHdlcSetup(r)
        assertTrue(text.contains("1"))
        assertTrue(text.contains("2"))
    }

    @Test
    fun describeHdlcSetup_textValue() {
        val r = ObisReading(obisCode = "0.0.22.0.0.255", rawValue = 0, scaler = 0, unit = null, textValue = "hello")
        assertEquals("hello", MeterBufferMapper.describeHdlcSetup(r))
    }

    @Test
    fun describeHdlcSetup_octetValue() {
        val r = ObisReading(obisCode = "0.0.22.0.0.255", rawValue = 0, scaler = 0, unit = null,
            octetValue = byteArrayOf(0x01, 0xAB.toByte()))
        assertEquals("01 AB", MeterBufferMapper.describeHdlcSetup(r))
    }

    @Test
    fun describeHdlcSetup_rawValue() {
        val r = ObisReading(obisCode = "0.0.22.0.0.255", rawValue = 42, scaler = 0, unit = null)
        assertEquals("42", MeterBufferMapper.describeHdlcSetup(r))
    }

    @Test
    fun obisReading_valueFromRawAndScaler() {
        val r = ObisReading(obisCode = "1.0.1.8.0.255", rawValue = 1740, scaler = -3, unit = 30)
        assertEquals(1.74, r.value, 1e-9)     // 1740 * 10^-3
        assertEquals(0.00174, r.valueKwh, 1e-9)
        assertNull(r.tariff)                   // 5-я группа = "0" - null
    }

    @Test
    fun obisReading_valueFromFloatHasPriority() {
        val r = ObisReading(obisCode = "1.0.1.8.0.255", rawValue = 999, scaler = 0, unit = 30, floatValue = 1.74)
        assertEquals(1.74, r.value, 1e-9)     // floatValue важнее
    }

    @Test
    fun obisReading_tariff() {
        assertEquals(1, ObisReading("1.0.1.8.1.255", 0, 0, null).tariff)
        assertEquals(3, ObisReading("1.0.1.8.3.255", 0, 0, null).tariff)
        assertNull(ObisReading("1.0.1.8.0.255", 0, 0, null).tariff)
    }

    // Для покрытия еще некоторых веток

    @Test
    fun meterInfo_hdlcAndOperatingTime_parsedAndFallbacks() {
        // Передаем HDLC настройки и время работы без unit
        val all = listOf(
            ObisReading("0.0.22.0.0.255", 0, 0, null, textValue = "P1"),
            ObisReading("0.1.22.0.0.255", 0, 0, null, textValue = "P2"),
            ObisReading("0.2.22.0.0.255", 0, 0, null, textValue = "P3"),
            ObisReading("0.3.22.0.0.255", 0, 0, null, textValue = "P4"),
            ObisReading("0.0.96.8.0.255", rawValue = 120, scaler = 0, unit = null)
        )
        val info = MeterBufferMapper.meterInfo(all, null, null, null, now)!!.info

        assertEquals("P1", info.hdlcP1)
        assertEquals("P2", info.hdlcP2)
        assertEquals("P3", info.hdlcP3)
        assertEquals("P4", info.hdlcP4)
        assertEquals(120.0, info.operatingTime!!, 0.0)
        assertNull(info.operatingTimeUnit)
    }

    @Test
    fun meterInfo_rdchDateOnly_usesMidnight() {
        val dateBytes = byteArrayOf(0x07, 0xEA.toByte(), 10, 15, 0) // 2026-10-15
        val all = listOf(ObisReading("0.0.0.9.2.101", 0, 0, null, octetValue = dateBytes))

        val info = MeterBufferMapper.meterInfo(all, null, null, null, now)!!
        assertEquals(LocalDateTime.of(2026, 10, 15, 0, 0), info.info.rdchDateTime)
    }

    @Test
    fun meterInfo_onlyOneFieldNotNull_passesBigIf() {
        val all = listOf(ObisReading(DD_PHASE_DISPLAY_OBIS, 0x07, 0, null))
        val info = MeterBufferMapper.meterInfo(all, null, null, null, now)
        assertNotNull(info)
        assertNotNull(info!!.info.phaseStatus)
    }

    @Test
    fun meterInfo_firmwareAndModelNull_fallbacks() {
        // Покрывает: 256 и 262 (firmwareEntry?.second и modelEntry?.second)
        // В буфере нет версии ПО и модели, но есть температура (чтобы не выйти в null)
        val all = listOf(ObisReading("0.0.96.9.0.255", rawValue = 25, scaler = 0, unit = 33))
        val info = MeterBufferMapper.meterInfo(all, null, null, null, now)!!.info

        assertEquals("—", info.firmwareVersion)
        assertEquals("—", info.model)
    }

    @Test
    fun meterInfo_keepsAllPreviousValues_whenMissingInBuffer() {
        // Покрывает: 316-327 (ВСЕ ветки "?: prev?....")
        val prev = MeterBufferMapper.blankMeterInfo("11111111").copy(
            modelObis = "0.0.96.1.1.255",
            firmwareVersionObis = "0.0.96.1.2.255",
            serialNumberObis = "0.0.96.1.0.255",
            hdlcP1 = "9600 бод",
            hdlcP2 = "19200 бод",
            hdlcP3 = "38400 бод",
            hdlcP4 = "57600 бод",
            operatingTime = 12.0,
            operatingTimeUnit = "ч",
            rdchDateTime = LocalDateTime.of(2026, 1, 1, 0, 0),
            phaseStatus = PhaseStatus.decode(1L)
        )

        // Буфер содержит только температуру, остального нет
        val all = listOf(ObisReading("0.0.96.9.0.255", rawValue = 25, scaler = 0, unit = 33))
        val info = MeterBufferMapper.meterInfo(all, prev, "11111111", null, now)!!.info

        assertEquals("0.0.96.1.1.255", info.modelObis)
        assertEquals("0.0.96.1.2.255", info.firmwareVersionObis)
        assertEquals("0.0.96.1.0.255", info.serialNumberObis)
        assertEquals("9600 бод", info.hdlcP1)
        assertEquals("19200 бод", info.hdlcP2)
        assertEquals("38400 бод", info.hdlcP3)
        assertEquals("57600 бод", info.hdlcP4)
        assertEquals(12.0, info.operatingTime!!, 0.0)
        assertEquals("ч", info.operatingTimeUnit)
        assertEquals(LocalDateTime.of(2026, 1, 1, 0, 0), info.rdchDateTime)
        assertNotNull(info.phaseStatus)
    }

    //хотела покрыть где только 3, не вышло, тк функция сразу 0 возращает из-за того что это просто доп.
    @Test
    fun networkParams_lineVoltages_onlyOneCode() {
        // Только новый L23
        val newCodes = listOf(
            ObisReading(obisCode = "1.0.12.7.0.255", rawValue = 2300, scaler = -1, unit = 35),
            ObisReading(obisCode = "1.0.125.7.0.255", rawValue = 4000, scaler = -1, unit = 35)
        )
        val netNew = MeterBufferMapper.networkParams(newCodes)!!
        assertNotNull(netNew.lineVoltages)
        assertNull(netNew.lineVoltages!!.l12)
        assertEquals(400.0, netNew.lineVoltages!!.l23!!, 1e-9)

        // Только старый L13
        val oldCodes = listOf(
            ObisReading(obisCode = "1.0.12.7.0.255", rawValue = 2300, scaler = -1, unit = 35),
            ObisReading(obisCode = "1.0.12.7.3.255", rawValue = 4000, scaler = -1, unit = 35)
        )
        val netOld = MeterBufferMapper.networkParams(oldCodes)!!
        assertNotNull(netOld.lineVoltages)
        assertEquals(400.0, netOld.lineVoltages!!.l13!!, 1e-9)
        assertTrue(netOld.lineVoltages!!.legacyCodes)
    }

    @Test
    fun networkParams_zeroFallbacks() {
        // Есть только мощность, нет напряжения и тока
        val all = listOf(ObisReading("1.0.1.7.0.255", 1000, 0, 27))
        val net = MeterBufferMapper.networkParams(all)!!

        assertEquals(0.0, net.voltage.total, 1e-9) // voltage ?: 0.0
        assertEquals(0.0, net.current.total, 1e-9)  // current ?: 0.0
        assertEquals(1.0, net.power.total, 1e-9)    // power есть
    }
}