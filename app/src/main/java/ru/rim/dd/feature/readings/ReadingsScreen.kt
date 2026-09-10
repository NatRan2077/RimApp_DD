package ru.rim.dd.feature.readings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import ru.rim.dd.core.model.EnergyCategory
import ru.rim.dd.core.model.Reading

/** Порядок карточек на экране — активная энергия сначала, реактивная и «прочее» — следом. */
private val CATEGORY_ORDER = listOf(
    EnergyCategory.ACTIVE_IMPORT,
    EnergyCategory.ACTIVE_EXPORT,
    EnergyCategory.REACTIVE_Q1,
    EnergyCategory.REACTIVE_Q4,
    EnergyCategory.OTHER,
)

private fun categoryTitle(category: EnergyCategory): String = when (category) {
    EnergyCategory.ACTIVE_IMPORT -> "Активная энергия, потребление"
    EnergyCategory.ACTIVE_EXPORT -> "Активная энергия, отдача"
    EnergyCategory.REACTIVE_Q1 -> "Реактивная энергия Q1"
    EnergyCategory.REACTIVE_Q4 -> "Реактивная энергия Q4"
    EnergyCategory.OTHER -> "Прочее"
}

private fun categoryUnit(category: EnergyCategory): String = when (category) {
    EnergyCategory.ACTIVE_IMPORT, EnergyCategory.ACTIVE_EXPORT -> "кВт·ч"
    EnergyCategory.REACTIVE_Q1, EnergyCategory.REACTIVE_Q4 -> "квар·ч"
    EnergyCategory.OTHER -> ""
}

/**
 * Главный экран — соответствует макету wf2_readings.png из ТЗ. Показания сгруппированы по
 * категориям энергии (см. Reading.category) — это отражает реальный буфер счётчика, где
 * одновременно приходят и активная, и реактивная энергия по 8 тарифам (см. историю
 * диагностики: полный разбор объекта 0.0.21.0.2.255 сравнением с логом реального пульта).
 */
@Composable
fun ReadingsScreen(viewModel: ReadingsViewModel = hiltViewModel()) {
    val readings by viewModel.readings.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Текущие показания", style = MaterialTheme.typography.headlineSmall)
        Button(onClick = { viewModel.refresh() }) { Text("Обновить") }

        if (readings.isEmpty()) {
            Text("Нет данных — нажмите «Обновить»", style = MaterialTheme.typography.bodyMedium)
        } else {
            val byCategory = readings.groupBy { it.category }
            CATEGORY_ORDER.forEach { category ->
                val items = byCategory[category].orEmpty()
                if (items.isNotEmpty()) {
                    EnergyCategoryCard(category, items)
                }
            }
        }
    }
}

@Composable
private fun EnergyCategoryCard(category: EnergyCategory, items: List<Reading>) {
    val unit = categoryUnit(category)
    val total = items.firstOrNull { it.tariff == null }?.valueKwh ?: items.sumOf { it.valueKwh }
    val byTariff = items.filter { it.tariff != null }.sortedBy { it.tariff }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(categoryTitle(category), style = MaterialTheme.typography.titleMedium)
            Text("Всего: ${"%.3f".format(total)} $unit", style = MaterialTheme.typography.titleLarge)
            byTariff.forEach { r ->
                Text("Т${r.tariff}: ${"%.3f".format(r.valueKwh)} $unit", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
