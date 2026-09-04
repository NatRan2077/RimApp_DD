package ru.rim.dd.feature.readings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

/** Главный экран — соответствует макету wf2_readings.png из ТЗ. */
@Composable
fun ReadingsScreen(viewModel: ReadingsViewModel = hiltViewModel()) {
    val readings by viewModel.readings.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Текущие показания", style = MaterialTheme.typography.headlineSmall)
        Button(onClick = { viewModel.refresh() }) { Text("Обновить") }

        val total = readings.filter { it.tariff == null }.sumOf { it.valueKwh }
        Text("$total кВт·ч", style = MaterialTheme.typography.displaySmall)

        Text("Детализация по тарифам", style = MaterialTheme.typography.titleMedium)
        LazyColumn {
            items(readings.filter { it.tariff != null }) { r ->
                Text("Т${r.tariff}: ${r.valueKwh} кВт·ч")
            }
        }
    }
}
