package ru.rim.dd.feature.info

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import java.time.format.DateTimeFormatter

private val CLOCK_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss")

/**
 * Экран «Инфо» — соответствует макету wf4_device_info.png из ТЗ. Температура/резервное
 * питание/часы прибора добавлены по факту находки в реальном буфере счётчика (см. историю
 * диагностики, OBIS 0.0.96.9.0.255 / 0.0.96.6.3.255 / 0.0.0.9.x).
 */
@Composable
fun InfoScreen(viewModel: InfoViewModel = hiltViewModel()) {
    val info by viewModel.meterInfo.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("О счётчике", style = MaterialTheme.typography.headlineSmall)
        Button(onClick = { viewModel.refresh() }) { Text("Обновить") }
        // [Android-патч] Диагностическая кнопка (см. InfoViewModel.probeLogs()) — пробует все
        // известные из паспорта прибора "журналы"; результат смотреть в logcat (MeterRepository),
        // отдельного UI для журналов пока нет — формат записей заранее неизвестен.
        Button(onClick = { viewModel.probeLogs() }) { Text("Проверить журналы (диагностика)") }
        Text("Модель: ${info?.model ?: "—"}")
        Text("Серийный номер: ${info?.serialNumber ?: "—"}")
        Text("Версия ПО: ${info?.firmwareVersion ?: "—"}")
        info?.signalLevelDbm?.let { Text("Уровень сигнала: $it дБм") }
        info?.temperatureC?.let { Text("Температура прибора: ${"%.1f".format(it)} °C") }
        info?.backupVoltageV?.let { Text("Напряжение резервного питания: ${"%.3f".format(it)} В") }
        info?.deviceClock?.let { Text("Часы прибора: ${it.format(CLOCK_FORMATTER)}") }
    }
}
