package ru.rim.dd.feature.pairing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import ru.rim.dd.core.ble.BleDevice
import ru.rim.dd.data.repository.MeterRepository
import javax.inject.Inject

data class PairingUiState(
    val isScanning: Boolean = false,
    val isConnecting: Boolean = false,
    val isConnected: Boolean = false,
    val foundDevices: List<BleDevice> = emptyList(),
    val errorMessage: String? = null,
)

/** UC-01 (сканирование) и UC-02 (ввод номера). */
@HiltViewModel
class PairingViewModel @Inject constructor(
    private val repository: MeterRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PairingUiState())
    val uiState: StateFlow<PairingUiState> = _uiState.asStateFlow()

    private var scanJob: Job? = null

    fun scan() {
        scanJob?.cancel()
        _uiState.value = _uiState.value.copy(isScanning = true, foundDevices = emptyList(), errorMessage = null)
        scanJob = viewModelScope.launch {
            repository.scanDevices()

                .filter { it.name?.startsWith(NAME_PREFIX, ignoreCase = true) == true }
                .collect { device ->
                    val current = _uiState.value.foundDevices
                    if (current.none { it.address == device.address }) {
                        _uiState.value = _uiState.value.copy(foundDevices = current + device)
                    }
                }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        _uiState.value = _uiState.value.copy(isScanning = false)
    }

    fun selectDevice(device: BleDevice, pin: String, remember: Boolean) {
        stopScan()
        _uiState.value = _uiState.value.copy(isConnecting = true, errorMessage = null)
        viewModelScope.launch {
            runCatching { repository.connectByAddress(device.address, pin, remember) }
                .onSuccess { _uiState.value = _uiState.value.copy(isConnecting = false, isConnected = true) }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(isConnecting = false, errorMessage = e.message ?: "Ошибка подключения")
                }
        }
    }

    fun connectByNumber(serialNumber: String, pin: String, remember: Boolean) {
        stopScan()
        _uiState.value = _uiState.value.copy(isConnecting = true, errorMessage = null)
        viewModelScope.launch {
            runCatching { repository.connectBySerialNumber(serialNumber, pin, remember) }
                .onSuccess { _uiState.value = _uiState.value.copy(isConnecting = false, isConnected = true) }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(isConnecting = false, errorMessage = e.message ?: "Ошибка подключения")
                }
        }
    }

    private companion object {
        /** Пульты РиМ 040.40 рекламируются в эфире как "RIM ..." */
        const val NAME_PREFIX = "RIM"
    }
}
