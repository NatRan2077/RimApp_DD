package ru.rim.dd.core.dlms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import ru.rim.dd.testutil.RealMeterBuffer
import java.io.File

/** Справочник OBIS-кодов: названия из паспортов приборов, подписи для экранов. */
class ObisCatalogTest {

    @Test
    fun knownCodes_giveRussianNames() {
        assertEquals("Температура", ObisCatalog.nameOf("0.0.96.9.0.255"))
        assertEquals("Напряжение батарейки", ObisCatalog.nameOf("0.0.96.6.3.255"))
        assertEquals("Номинальное напряжение", ObisCatalog.nameOf("1.0.0.6.0.255"))
    }

    @Test
    fun unknownCode_hasNoName_andCaptionIsTheCodeItself() {
        assertNull(ObisCatalog.nameOf("9.9.9.9.9.9"))
        assertEquals("9.9.9.9.9.9", ObisCatalog.caption("9.9.9.9.9.9"))
    }

    @Test
    fun caption_isNameDotCode() {
        assertEquals("Температура · 0.0.96.9.0.255", ObisCatalog.caption("0.0.96.9.0.255"))
    }

    @Test
    fun codesFromRealBuffer_exceptPositionalFields_haveNames() {
        // Позиционные поля буфера (часы, реле, фазы, тариф, «Значки ДД») паспортных названий не имеют.
        val positional = setOf(CLOCK_STATUS_OBIS, RELAY_CONTROL_STATE_OBIS, DD_PHASE_DISPLAY_OBIS, CURRENT_TARIFF_OBIS, TAMPER_STATUS_OBIS)
        val missing = RealMeterBuffer.readings().map { it.obisCode }.filter { it !in positional && ObisCatalog.nameOf(it) == null }
        assertTrue("нет названия для: $missing", missing.isEmpty())
    }

    @Test
    fun sourceHasNoAccidentalDuplicateKeys() {
        // mapOf молча оставляет последнее значение при повторе ключа — опечатка в коде осталась бы незамеченной.
        // Два повтора намеренные: в конце таблицы названия из прошивки пульта заменяют паспортные.
        val intentionalOverrides = setOf("0.0.0.9.1.255", "0.0.0.9.2.255")
        val file = File("src/main/java/ru/rim/dd/core/dlms/ObisCatalog.kt")
        assumeTrue("исходник не найден из рабочей папки теста", file.exists())
        val keys = Regex("^\\s*\"([0-9.]+)\" to ", RegexOption.MULTILINE).findAll(file.readText()).map { it.groupValues[1] }.toList()
        assertTrue("в справочнике должно быть много записей", keys.size > 300)
        val duplicates = keys.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        assertEquals("повторяющиеся OBIS-коды сверх намеренных", emptySet<String>(), duplicates - intentionalOverrides)
        assertEquals("Время прибора", ObisCatalog.nameOf("0.0.0.9.1.255"))
        assertEquals("Дата прибора", ObisCatalog.nameOf("0.0.0.9.2.255"))
        assertNotNull(ObisCatalog.nameOf(keys.first()))
    }
}
