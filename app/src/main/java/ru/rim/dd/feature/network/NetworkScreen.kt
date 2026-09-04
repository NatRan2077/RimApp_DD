package ru.rim.dd.feature.network

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

/** Экран «Сеть» — соответствует макету wf3_network_relay.png из ТЗ. */
@Composable
fun NetworkScreen(viewModel: NetworkViewModel = hiltViewModel()) {
    val params by viewModel.networkParams.collectAsState()
    val relay by viewModel.relayState.collectAsState()
    val countdown by viewModel.turnOnCountdown.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Параметры сети", style = MaterialTheme.typography.headlineSmall)
        Text("Напряжение: ${params.voltage.total} В")
        Text("Ток: ${params.current.total} А")
        Text("Мощность: ${params.power.total} кВт")
        params.frequencyHz?.let { Text("Частота: $it Гц") }

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
