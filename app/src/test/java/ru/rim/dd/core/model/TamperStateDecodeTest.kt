package ru.rim.dd.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Расшифровка поля «Значки ДД» (0.0.96.50.170.255): пломбы, магнит, ПКЭ, лимит мощности.
 * Раскладка бит — как в read_status() прошивки пульта РиМ 040.40.
 */
class TamperStateDecodeTest {

    private fun decode(raw: Long) = TamperState.decode(raw)

    @Test
    fun allQuiet_lcdVersion1() {
        val s = decode(0x00400000)
        assertEquals(1, s.lcdVersion)
        assertFalse(s.unknown)
        assertFalse(s.anyActive)
        assertEquals(TamperCondition.OK, s.cover)
        assertEquals(TamperCondition.OK, s.meter)
        assertEquals(TamperCondition.OK, s.magnet)
        assertEquals(TamperCondition.OK, s.battery)
        assertEquals(TamperCondition.OK, s.rf)
        assertEquals(TamperCondition.OK, s.eqi)
        assertFalse(s.powerLimitExceeded)
    }

    @Test
    fun coverOpen_isActive_andRaisesAnyActive() {
        val s = decode(0x00410000)
        assertEquals(TamperCondition.ACTIVE, s.cover)
        assertTrue(s.anyActive)
    }

    @Test
    fun coverWasOpened_isFlagOnly_notAnActiveAlarm() {
        val s = decode(0x00400100)
        assertEquals(TamperCondition.FLAG, s.cover)
        assertFalse("«было, сейчас нет» не считается активной аварией", s.anyActive)
    }

    @Test
    fun eachSensorHasItsOwnBit() {
        assertEquals(TamperCondition.ACTIVE, decode(0x00420000).meter)
        assertEquals(TamperCondition.ACTIVE, decode(0x00440000).magnet)
        assertEquals(TamperCondition.ACTIVE, decode(0x00480000).battery)
        assertEquals(TamperCondition.ACTIVE, decode(0x00500000).rf)
        assertEquals(TamperCondition.ACTIVE, decode(0x00600000).selfDiagnosticsFault)

        assertEquals(TamperCondition.FLAG, decode(0x00400200).meter)
        assertEquals(TamperCondition.FLAG, decode(0x00400400).magnet)
        assertEquals(TamperCondition.FLAG, decode(0x00400800).battery)
        assertEquals(TamperCondition.FLAG, decode(0x00401000).rf)
        assertEquals(TamperCondition.FLAG, decode(0x00402000).selfDiagnosticsFault)
    }

    @Test
    fun activeWinsOverFlag_whenBothBitsSet() {
        assertEquals(TamperCondition.ACTIVE, decode(0x00440400).magnet)
    }

    @Test
    fun powerLimitExceeded_comesFromByte2Bit7() {
        assertTrue(decode(0x00408000).powerLimitExceeded)
        assertFalse(decode(0x00400000).powerLimitExceeded)
    }

    @Test
    fun powerQualityAndUnbalance_comeFromByte0() {
        assertEquals(TamperCondition.ACTIVE, decode(0x01400000).eqi)
        assertEquals(TamperCondition.FLAG, decode(0x10400000).eqi)
        assertEquals(TamperCondition.ACTIVE, decode(0x02400000).unbalance)
        assertEquals(TamperCondition.FLAG, decode(0x20400000).unbalance)
    }

    @Test
    fun lcdVersion0_hasOnlyPowerQualityAndMagnet() {
        val quiet = decode(0x00000000)
        assertEquals(0, quiet.lcdVersion)
        assertEquals(TamperCondition.OK, quiet.eqi)
        assertEquals(TamperCondition.OK, quiet.magnet)
        assertFalse(quiet.unknown)

        assertEquals(TamperCondition.ACTIVE, decode(0x00010000).magnet)   // любая сработка в байте 1
        assertEquals(TamperCondition.ACTIVE, decode(0x00000100).magnet)   // или в байте 2
        assertEquals(TamperCondition.ACTIVE, decode(0x01000000).eqi)
        assertEquals(TamperCondition.OK, decode(0x00010000).cover)        // других датчиков в версии 0 нет
    }

    @Test
    fun realMeterValue_fromLog() {
        // 826871552 = 0x31490F00 — значение из настоящего буфера РиМ (см. RealMeterBuffer).
        val s = decode(826871552L)
        assertEquals(1, s.lcdVersion)
        assertEquals(TamperCondition.ACTIVE, s.cover)
        assertEquals(TamperCondition.ACTIVE, s.battery)
        assertEquals(TamperCondition.ACTIVE, s.eqi)
        assertEquals(TamperCondition.FLAG, s.unbalance)
        assertTrue(s.anyActive)
    }

    @Test
    fun unknownState_isNeverReportedAsAlarm() {
        assertTrue(TamperState.UNKNOWN.unknown)
        assertFalse(TamperState.UNKNOWN.anyActive)
        assertFalse(decode(0x00400000).unknown)
    }
}
