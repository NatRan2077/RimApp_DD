package ru.rim.dd.feature.network

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

/**
 * Экран «Сеть» — соответствует макету wf3_network_relay.png из ТЗ. Помимо изначальных
 * напряжения/тока/мощности/частоты добавлены реактивная и полная мощность, ток нейтрали —
 * реально найдены в буфере счётчика (см. историю диагностики, OBIS 1.0.3.7.0.255 /
 * 1.0.9.7.0.255 / 1.0.91.7.0.255).
 */
@Composable
fun NetworkScreen(viewModel: NetworkViewModel = hiltViewModel()) {
    val params by viewModel.networkParams.collectAsState()
    val relay by viewModel.relayState.collectAsState()
    val countdown by viewModel.turnOnCountdown.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Параметры сети", style = MaterialTheme.typography.headlineSmall)
        Button(onClick = { viewModel.refresh() }) { Text("Обновить") }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Напряжение: ${"%.2f".format(params.voltage.total)} В")
                Text("Ток: ${"%.3f".format(params.current.total)} А")
                params.neutralCurrentA?.let { Text("Ток нейтрали: ${"%.3f".format(it)} А") }
                Text("Активная мощность: ${"%.3f".format(params.power.total)} кВт")
                params.reactivePowerKvar?.let { Text("Реактивная мощность: ${"%.3f".format(it)} квар") }
                params.apparentPowerKva?.let { Text("Полная мощность: ${"%.3f".format(it)} кВА") }
                params.frequencyHz?.let { Text("Частота: ${"%.2f".format(it)} Гц") }
            }
        }

        Text("Состояние реле", style = MaterialTheme.typography.titleMedium)
        Text(if (relay.isOn) "ВКЛЮЧЕНО" else "ОТКЛЮЧЕНО")
        Text("Лимит мощности: ${relay.powerLimitKw} кВт")

        countdown?.let { Text("Включение через: $it c") }

        Button(onClick = viewModel::turnRelayOff) { Text("Отключить") }
        Button(onClick = viewModel::turnRelayOn, enabled = relay.remoteTurnOnAllowed || !relay.isOn) {
            Text("Включить")
        }
    }
}
