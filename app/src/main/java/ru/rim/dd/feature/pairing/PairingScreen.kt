package ru.rim.dd.feature.pairing

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel

/** Разрешения, нужные для BLE-сканирования/подключения — разный набор до и после Android 12. */
private val blePermissions: Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

/** Экран «Подключение к ПУ» — соответствует макету wf1_pairing.png из ТЗ. */
@Composable
fun PairingScreen(
    onConnected: () -> Unit,
    viewModel: PairingViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    var serialNumber by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }

    val context = LocalContext.current
    var permissionsGranted by remember {
        mutableStateOf(
            blePermissions.all {
                ContextCompat.checkSelfPermission(context, it) == android.content.pm.PackageManager.PERMISSION_GRANTED
            }
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results -> permissionsGranted = results.values.all { it } }

    LaunchedEffect(state.isConnected) {
        if (state.isConnected) onConnected()
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Подключение к ПУ", style = MaterialTheme.typography.headlineSmall)

        if (!permissionsGranted) {
            Text(
                "Для поиска и подключения к прибору нужен доступ к Bluetooth" +
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) " и геолокации" else "",
                color = MaterialTheme.colorScheme.error,
            )
            Button(onClick = { permissionLauncher.launch(blePermissions) }) {
                Text("Предоставить разрешения")
            }
        } else {
            Button(onClick = { viewModel.scan() }, enabled = !state.isConnecting) {
                Text(if (state.isScanning) "Сканирование..." else "Сканировать эфир")
            }

            LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                items(state.foundDevices) { device ->
                    Text(
                        "${device.name ?: device.address}  (${device.rssi} dBm)",
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !state.isConnecting) {
                                viewModel.selectDevice(device, pin, remember = true)
                            }
                            .padding(vertical = 8.dp),
                    )
                }
            }

            OutlinedTextField(
                value = serialNumber,
                onValueChange = { serialNumber = it },
                label = { Text("Серийный номер ПУ") },
                enabled = !state.isConnecting,
            )
            OutlinedTextField(
                value = pin,
                onValueChange = { pin = it },
                label = { Text("PIN-код") },
                enabled = !state.isConnecting,
            )
            Text(
                "PIN-код нужен и для подключения из списка выше — введи его перед тем, как выбрать прибор.",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(
                onClick = { viewModel.connectByNumber(serialNumber, pin, remember = true) },
                enabled = !state.isConnecting,
            ) {
                Text("Подключиться по номеру")
            }

            if (state.isConnecting) {
                CircularProgressIndicator()
            }
        }

        state.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
