package ru.rim.dd.feature.info

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

/** Экран «Инфо» — соответствует макету wf4_device_info.png из ТЗ. */
@Composable
fun InfoScreen(viewModel: InfoViewModel = hiltViewModel()) {
    val info by viewModel.meterInfo.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("О счётчике", style = MaterialTheme.typography.headlineSmall)
        Text("Модель: ${info?.model ?: "—"}")
        Text("Серийный номер: ${info?.serialNumber ?: "—"}")
        Text("Версия ПО: ${info?.firmwareVersion ?: "—"}")
        info?.signalLevelDbm?.let { Text("Уровень сигнала: $it дБм") }
    }
}
