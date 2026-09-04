package ru.rim.dd.feature.pairing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

/** Экран «Подключение к ПУ» — соответствует макету wf1_pairing.png из ТЗ. */
@Composable
fun PairingScreen(
    onConnected: () -> Unit,
    viewModel: PairingViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    var serialNumber by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Подключение к ПУ", style = androidx.compose.material3.MaterialTheme.typography.headlineSmall)

        Button(onClick = { viewModel.scan() }) { Text("Сканировать эфир") }

        LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
            items(state.foundDevices) { device ->
                Text("${device.name ?: device.address}  (${device.rssi} dBm)")
            }
        }

        OutlinedTextField(
            value = serialNumber,
            onValueChange = { serialNumber = it },
            label = { Text("Серийный номер ПУ") },
        )
        OutlinedTextField(
            value = pin,
            onValueChange = { pin = it },
            label = { Text("PIN-код") },
        )
        Button(onClick = { viewModel.connectByNumber(serialNumber, pin, remember = true) }) {
            Text("Подключиться по номеру")
        }

        state.errorMessage?.let { Text(it, color = androidx.compose.material3.MaterialTheme.colorScheme.error) }
    }
}
