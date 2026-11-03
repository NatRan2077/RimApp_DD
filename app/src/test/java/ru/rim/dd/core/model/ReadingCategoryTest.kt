package ru.rim.dd.core.model

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

/** Категория показания по группе C его OBIS-кода: от неё зависят заголовок, единица и порядок строк. */
class ReadingCategoryTest {

    private fun category(obis: String) = Reading(obisId = obis, valueKwh = 0.0, timestamp = Instant.EPOCH).category

    @Test
    fun activeEnergy() {
        assertEquals(EnergyCategory.ACTIVE_IMPORT, category("1.0.1.8.1.255"))
        assertEquals(EnergyCategory.ACTIVE_IMPORT, category("1.0.1.8.0.255"))
        assertEquals(EnergyCategory.ACTIVE_EXPORT, category("1.0.2.8.1.255"))
    }

    @Test
    fun reactiveEnergy() {
        assertEquals(EnergyCategory.REACTIVE_Q1, category("1.0.3.8.0.255"))
        assertEquals(EnergyCategory.REACTIVE_Q4, category("1.0.4.8.0.255"))
        assertEquals(EnergyCategory.REACTIVE_Q4, category("1.0.4.8.8.101"))
    }

    @Test
    fun everythingElse_isOther() {
        assertEquals(EnergyCategory.OTHER, category("1.0.9.8.0.255"))
        assertEquals(EnergyCategory.OTHER, category("0.0.96.9.0.255"))
        assertEquals(EnergyCategory.OTHER, category("abc"))
        assertEquals(EnergyCategory.OTHER, category(""))
    }
}
