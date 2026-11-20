package ru.rim.dd.data.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.rim.dd.core.dlms.decodeCosemDate
import ru.rim.dd.core.dlms.decodeCosemTime
import ru.rim.dd.core.model.EnergyCategory
import ru.rim.dd.core.model.RelaySource
import ru.rim.dd.core.model.TamperCondition
import ru.rim.dd.data.dlms.DlmsSessionClient
import ru.rim.dd.data.dlms.MainBufferResult
import ru.rim.dd.testutil.DlmsFixtures
import ru.rim.dd.testutil.FakeNativeDlmsApi
import ru.rim.dd.testutil.RealAkrosBuffer
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertNotNull
import ru.rim.dd.core.dlms.longOrBoolValueOf

/**
 * Настоящий ответ AKROS из лога (подключение 29.10.2026) на путь пульта: разбор пакета и вся
 * дальнейшая обработка — от байтов до состояний, которые видят экраны.
 */
class RealAkrosBufferTest {

    private val now = Instant.parse("2026-10-29T10:00:00Z")
    private val byObis get() = RealAkrosBuffer.readings().associateBy { it.obisCode }

    // ---------- разбор пакета ----------

    @Test
    fun threeHdlcFrames_assembleIntoOneGetResponse() {
        val raw = RealAkrosBuffer.rawResponse()
        assertEquals("информационные поля 3 кадров: 128 + 128 + 120 байт", 376, raw.size)
        assertEquals(listOf(0xE6, 0xE7, 0x00, 0xC4, 0x01), raw.take(5).map { it.toInt() and 0xFF })
    }

    @Test
    fun positionalFields_clockControlPhasesTariffTamper() {
        val all = RealAkrosBuffer.readings()
        assertEquals(19, all.size)
        assertEquals(2L, all.first { it.obisCode == "0.0.1.0.0.255" }.rawValue)         // статус часов
        assertEquals(2L, byObis.getValue("0.0.96.3.10.255").rawValue)                    // control_state реле
        assertEquals(-128L, byObis.getValue("1.0.96.50.0.255").rawValue)                 // фазы ДД, 0x80
        assertEquals(8L, byObis.getValue("0.0.96.14.0.255").rawValue)                    // текущий тариф
        assertEquals(0x1140A000L, byObis.getValue("0.0.96.50.170.255").rawValue)         // «Значки ДД»
    }

    @Test
    fun energy_isFloat_withUnits() {
        fun check(obis: String, expected: Double, unit: Int) {
            val r = byObis.getValue(obis)
            assertEquals("$obis", expected, r.floatValue!!, expected * 1e-6)
            assertEquals("$obis unit", unit, r.unit)
        }
        check("1.0.1.8.0.255", 31_482_402.0, 30)     // Вт·ч
        check("1.0.2.8.0.255", 166_556.36, 30)
        check("1.0.3.8.0.255", 331_449.03, 32)       // вар·ч
        check("1.0.4.8.0.255", 59_181.016, 32)
    }

    @Test
    fun instantaneous_arePresentWithUnits_andZeroWithoutLoad() {
        val expectedUnits = mapOf(
            "1.0.12.7.0.255" to 35,   // В
            "1.0.11.7.0.255" to 33,   // А
            "1.0.1.7.0.255" to 27,    // Вт
            "1.0.3.7.0.255" to 29,    // вар
            "1.0.9.7.0.255" to 28,    // ВА
            "1.0.14.7.0.255" to 44,   // Гц
        )
        for ((obis, unit) in expectedUnits) {
            assertEquals(obis, unit, byObis.getValue(obis).unit)
            assertEquals(obis, 0.0, byObis.getValue(obis).floatValue!!, 0.0)
        }
    }

    @Test
    fun deviceClock_dateTimeBytesDecode() {
        val bytes = RealAkrosBuffer.readings().first { it.octetValue?.size == 12 }.octetValue!!
        assertEquals(LocalDate.of(2026, 10, 29), decodeCosemDate(bytes.copyOfRange(0, 5)))
        assertEquals(LocalTime.of(16, 14, 11), decodeCosemTime(bytes.copyOfRange(5, 9)))
    }

    @Test
    fun firmwareVersion_andHdlcSetupObjects() {
        assertEquals("1.02", byObis.getValue("0.0.96.1.2.255").textValue)
        assertEquals(8, byObis.getValue("0.0.22.0.0.255").structItems!!.size)
        assertEquals(8, byObis.getValue("0.1.22.0.0.255").structItems!!.size)
    }

    // ---------- протокольный клиент ----------

    @Test
    fun readMainBuffer_decodesRealAkrosAnswer_onRimPath() {
        val native = FakeNativeDlmsApi()
        native.onFlatGet = { _, _, _ -> RealAkrosBuffer.rawResponse() }

        val result = DlmsSessionClient(native).readMainBuffer()

        assertTrue(result is MainBufferResult.Decoded)
        assertEquals(19, (result as MainBufferResult.Decoded).readings.size)
        assertEquals("ассоциация не нужна: SNRM и AARQ не вызывались", listOf("flat-get 7 0.0.21.0.2.255 2"), native.calls)
    }

    // ---------- состояния экранов ----------

    private fun applied(): MeterState =
        MeterState().also { it.applyBuffer(RealAkrosBuffer.readings(), "07200090", null, now) }

    @Test
    fun relay_readFromMeter() {
        val relay = applied().relay.value
        assertFalse(relay.isOn)
        assertTrue(relay.remoteTurnOnAllowed)
        assertEquals(RelaySource.METER_READ, relay.source)
    }

    @Test
    fun tamper_decodedFromFieldValue() {
        val t = applied().tamper.value          // 0x1140A000
        assertEquals(1, t.lcdVersion)
        assertEquals(TamperCondition.ACTIVE, t.eqi)
        assertEquals(TamperCondition.FLAG, t.selfDiagnosticsFault)
        assertTrue(t.powerLimitExceeded)
        assertEquals(TamperCondition.OK, t.cover)
        assertEquals(TamperCondition.OK, t.magnet)
        assertFalse("пломбы целы, активных вскрытий нет", t.anyActive)
    }

    @Test
    fun energy_shownInKiloUnits_byCategory() {
        val readings = applied().readings.value
        assertEquals(4, readings.size)
        assertTrue(readings.none { it.isRdch })
        fun kwh(category: EnergyCategory) = readings.single { it.category == category }.valueKwh
        assertEquals(31_482.402, kwh(EnergyCategory.ACTIVE_IMPORT), 1e-3)
        assertEquals(166.556, kwh(EnergyCategory.ACTIVE_EXPORT), 1e-3)
        assertEquals(331.449, kwh(EnergyCategory.REACTIVE_Q1), 1e-3)
        assertEquals(59.181, kwh(EnergyCategory.REACTIVE_Q4), 1e-3)
    }

    @Test
    fun meterInfo_clockTariffFirmwareAndHdlc() {
        val info = applied().info.value!!
        assertEquals("07200090", info.serialNumber)
        assertEquals("1.02", info.firmwareVersion)
        assertEquals(LocalDate.of(2026, 10, 29), info.deviceDate)
        assertEquals(LocalTime.of(16, 14, 11), info.deviceTime)
        assertEquals(8, info.currentTariff)
        assertFalse("статус часов 2 («сомнительное значение») → часы недостоверны", info.clockValid!!)
        assertTrue(info.phaseStatus!!.threePhase)
        assertEquals("19200 бод, адрес 16", info.hdlcP1)
        assertEquals("300 бод, адрес 16", info.hdlcP2)
        assertNull("AKROS не присылает температуру и напряжение батарейки", info.temperatureC)
        assertNull(info.backupVoltageV)
    }

    @Test
    fun network_zeroWithoutLoad_notMissing() {
        val net = applied().network.value!!
        assertEquals(0.0, net.voltage.total, 0.0)
        assertEquals(0.0, net.current.total, 0.0)
        assertEquals(0.0, net.power.total, 0.0)
        assertEquals(0.0, net.frequencyHz!!, 0.0)
    }


    //Склейка кадров: ровно один LLC

    @Test
    fun rawResponse_hasExactlyOneLlcHeader() {
        // Если infoField ошибётся на байт (захватит HCS/FCS), склейка сдвинется,
        // и LLC (E6 E7 00) может оказаться дважды. Проверяем, что он один.
        val raw = RealAkrosBuffer.rawResponse()
        val count = (0..raw.size - 3).count { i ->
            (raw[i].toInt() and 0xFF) == 0xE6 &&
                    (raw[i + 1].toInt() and 0xFF) == 0xE7 &&
                    (raw[i + 2].toInt() and 0xFF) == 0x00
        }
        assertEquals("один LLC на склеенный ответ", 1, count)
    }

    // HDLC-объекты: не «съедают» друг друга

    @Test
    fun hdlcSetup_bothObjectsHaveExactly8Attributes_andDoNotOverlap() {
        // В буфере AKROS два HDLC-объекта (0.0.22 и 0.1.22). Если один не последний,
        // код, забирающий «остаток», съест атрибуты второго. Проверяем: оба по 8,
        // и последний элемент каждого — device_address = 16 (не элементы соседа).
        val first = byObis.getValue("0.0.22.0.0.255").structItems!!
        val second = byObis.getValue("0.1.22.0.0.255").structItems!!

        assertEquals("0.0.22 - 8 атрибутов", 8, first.size)
        assertEquals("0.1.22 - 8 атрибутов", 8, second.size)
        assertEquals("device_address 0.0.22", 16L, longOrBoolValueOf(first.last()))
        assertEquals("device_address 0.1.22", 16L, longOrBoolValueOf(second.last()))
    }

    //  Сеть при нулевой нагрузке: не только total
    // под вопросом пока

//    @Test
//    fun network_zeroWithoutLoad_allFieldsPresent() {
//        val net = applied().network.value!!
//
//        assertEquals(0.0, net.voltage.total, 0.0)
//        assertEquals(0.0, net.current.total, 0.0)
//        assertEquals(0.0, net.power.total, 0.0)
//        assertEquals(0.0, net.frequencyHz!!, 0.0)
//
//        // Трёхфазный AKROS: три фазы присутствуют (не null), по нулям.
//        assertTrue("прибор трёхфазный", net.voltage.isThreePhase)
//        assertNotNull("фаза L1", net.voltage.l1)
//        assertNotNull("фаза L2", net.voltage.l2)
//        assertNotNull("фаза L3", net.voltage.l3)
//        assertEquals(0.0, net.voltage.l1!!, 0.0)
//        assertEquals(0.0, net.voltage.l2!!, 0.0)
//        assertEquals(0.0, net.voltage.l3!!, 0.0)
//    }

    //Энергия: единица не теряется при маппинге

    @Test
    fun energy_keepsUnitBeforeMapping() {
        // unit проверяем на сырых ObisReading (до маппинга в Reading),
        // потому что у Reading единица зашита в category.
        val raw = RealAkrosBuffer.readings().associateBy { it.obisCode }
        assertEquals(30, raw.getValue("1.0.1.8.0.255").unit)
        assertEquals(30, raw.getValue("1.0.2.8.0.255").unit)
        assertEquals(32, raw.getValue("1.0.3.8.0.255").unit)
        assertEquals(32, raw.getValue("1.0.4.8.0.255").unit)
    }

    //  Протокольный клиент: пустой и ошибочный ответ

    @Test
    fun readMainBuffer_emptyAnswer_returnsNotDecoded() {
        // Пустой ответ не должен ронять клиент - должен вернуть «не разобрано».
        val native = FakeNativeDlmsApi()
        native.onFlatGet = { _, _, _ -> ByteArray(0) }

        val result = DlmsSessionClient(native).readMainBuffer()

        assertFalse("пустой ответ - не Decoded", result is MainBufferResult.Decoded)
    }

    @Test
    fun readMainBuffer_errorAnswer_returnsNotDecoded() {
        // data-access-result вместо данных — тоже «не разобрано», без краха.
        val native = FakeNativeDlmsApi()
        native.onFlatGet = { _, _, _ -> DlmsFixtures.getResponseError(code = 0x0D) }

        val result = DlmsSessionClient(native).readMainBuffer()

        assertFalse("ошибка доступа - не Decoded", result is MainBufferResult.Decoded)
    }

    // Серийный номер: из буфера или переданный

    @Test
    fun meterInfo_serialNumber_comesFromBufferOrGiven() {
        val info = applied().info.value!!
        assertEquals("07200090", info.serialNumber)
        // Проверяем, что в буфере AKROS поля серийного номера действительно нет.
        assertNull("AKROS не присылает 0.0.96.1.0.255", byObis["0.0.96.1.0.255"])
    }
}
