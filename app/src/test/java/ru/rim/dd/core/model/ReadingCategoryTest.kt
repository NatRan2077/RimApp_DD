package ru.rim.dd.core.model

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

/** Категория показания по группе C его OBIS-кода: от неё зависят заголовок, единица и порядок строк . */
class ReadingCategoryTest {

    private fun category(obis: String) = Reading(obisId = obis, valueKwh = 0.0, timestamp = Instant.EPOCH).category

    @Test
    fun activeEnergyImport() {
        assertEquals(EnergyCategory.ACTIVE_IMPORT, category("1.0.1.8.0.255"))
        assertEquals(EnergyCategory.ACTIVE_IMPORT, category("1.0.1.8.1.254"))
        assertEquals(EnergyCategory.ACTIVE_IMPORT, category("1.0.1.8.2.155"))
        assertEquals(EnergyCategory.ACTIVE_IMPORT, category("1.0.1.8.3.100"))
        assertEquals(EnergyCategory.ACTIVE_IMPORT, category("1.0.1.8.4.2"))
        assertEquals(EnergyCategory.ACTIVE_IMPORT, category("1.0.1.8.5.25"))
        assertEquals(EnergyCategory.ACTIVE_IMPORT, category("1.0.1.8.6.55"))
        assertEquals(EnergyCategory.ACTIVE_IMPORT, category("1.0.1.8.7.205"))
        assertEquals(EnergyCategory.ACTIVE_IMPORT, category("1.0.1.8.8.0"))
    }

    @Test
    fun activeEnergyExport() {
        assertEquals(EnergyCategory.ACTIVE_EXPORT, category("1.0.2.8.0.255"))
        assertEquals(EnergyCategory.ACTIVE_EXPORT, category("1.0.2.8.1.254"))
        assertEquals(EnergyCategory.ACTIVE_EXPORT, category("1.0.2.8.2.155"))
        assertEquals(EnergyCategory.ACTIVE_EXPORT, category("1.0.2.8.3.100"))
        assertEquals(EnergyCategory.ACTIVE_EXPORT, category("1.0.2.8.4.2"))
        assertEquals(EnergyCategory.ACTIVE_EXPORT, category("1.0.2.8.5.25"))
        assertEquals(EnergyCategory.ACTIVE_EXPORT, category("1.0.2.8.6.55"))
        assertEquals(EnergyCategory.ACTIVE_EXPORT, category("1.0.2.8.7.205"))
        assertEquals(EnergyCategory.ACTIVE_EXPORT, category("1.0.2.8.8.0"))
    }


    @Test
    fun reactiveEnergyQ1() {
        assertEquals(EnergyCategory.REACTIVE_Q1, category("1.0.3.8.0.255"))
        assertEquals(EnergyCategory.REACTIVE_Q1, category("1.0.3.8.1.101"))
        assertEquals(EnergyCategory.REACTIVE_Q1, category("1.0.3.8.2.25"))
        assertEquals(EnergyCategory.REACTIVE_Q1, category("1.0.3.8.3.10"))
        assertEquals(EnergyCategory.REACTIVE_Q1, category("1.0.3.8.4.55"))
        assertEquals(EnergyCategory.REACTIVE_Q1, category("1.0.3.8.5.1"))
        assertEquals(EnergyCategory.REACTIVE_Q1, category("1.0.3.8.6.254"))
        assertEquals(EnergyCategory.REACTIVE_Q1, category("1.0.3.8.7.11"))
        assertEquals(EnergyCategory.REACTIVE_Q1, category("1.0.3.8.8.111"))
    }

    @Test
    fun reactiveEnergyQ4() {
        assertEquals(EnergyCategory.REACTIVE_Q4, category("1.0.4.8.0.255"))
        assertEquals(EnergyCategory.REACTIVE_Q4, category("1.0.4.8.1.101"))
        assertEquals(EnergyCategory.REACTIVE_Q4, category("1.0.4.8.2.25"))
        assertEquals(EnergyCategory.REACTIVE_Q4, category("1.0.4.8.3.10"))
        assertEquals(EnergyCategory.REACTIVE_Q4, category("1.0.4.8.4.55"))
        assertEquals(EnergyCategory.REACTIVE_Q4, category("1.0.4.8.5.1"))
        assertEquals(EnergyCategory.REACTIVE_Q4, category("1.0.4.8.6.254"))
        assertEquals(EnergyCategory.REACTIVE_Q4, category("1.0.4.8.7.11"))
        assertEquals(EnergyCategory.REACTIVE_Q4, category("1.0.4.8.8.111"))
    }

    @Test
    fun everythingElse_isOtherCode() {
        assertEquals(EnergyCategory.OTHER, category("1.0.9.8.0.255"))
        assertEquals(EnergyCategory.OTHER, category("0.0.96.9.0.255"))
        assertEquals(EnergyCategory.OTHER, category("abc"))
    }

    @Test
    fun everythingElse_isOtherNull() {
        assertEquals(EnergyCategory.OTHER, category(" "))
        assertEquals(EnergyCategory.OTHER, category(""))
    }

    @Test

    fun everythingElse_ShortCode() {
        assertEquals(EnergyCategory.OTHER, category("1.0.1"))
    }
    @Test

    fun everythingElse_IncompleteCode() {
        assertEquals(EnergyCategory.OTHER, category("1.0.4.8.8"))
    }

    @Test

    fun everythingElse_LongCode() {
        assertEquals(EnergyCategory.OTHER, category("1.0.2.8.8.9.1"))
    }

    @Test

    fun everythingElse_isIncorrectExceptThirdNumber() {
        assertEquals(EnergyCategory.OTHER, category("9.9.4.9.9.111"))
    }

    @Test

    fun everythingElse_RussianText() {
        assertEquals(EnergyCategory.OTHER, category("РиМ"))
    }

    @Test

    fun everythingElse_TextAndNumber() {
        assertEquals(EnergyCategory.OTHER, category("r.i.4.m.love.111"))
    }

    @Test

    fun everythingElse_Points() {
        assertEquals(EnergyCategory.OTHER, category("....4....."))
    }
    @Test

    fun everythingElse_PointsAndSpace() {
        assertEquals(EnergyCategory.OTHER, category(" . .4. . ."))
    }

}
