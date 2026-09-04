package ru.rim.dd.feature.pairing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ru.rim.dd.core.ble.BleDevice
import ru.rim.dd.data.repository.MeterRepository
import javax.inject.Inject

data class PairingUiState(
    val isScanning: Boolean = false,
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

    fun scan() {
        _uiState.value = _uiState.value.copy(isScanning = true, foundDevices = emptyList())
        viewModelScope.launch {
            repository.scanDevices().collect { device ->
                val current = _uiState.value.foundDevices
                if (current.none { it.address == device.address }) {
                    _uiState.value = _uiState.value.copy(foundDevices = current + device)
                }
            }
        }
    }

    fun selectDevice(device: BleDevice, pin: String, remember: Boolean) {
        viewModelScope.launch {
            runCatching { repository.connectByAddress(device.address, pin, remember) }
                .onFailure { e -> _uiState.value = _uiState.value.copy(errorMessage = e.message) }
        }
    }

    fun connectByNumber(serialNumber: String, pin: String, remember: Boolean) {
        viewModelScope.launch {
            runCatching { repository.connectBySerialNumber(serialNumber, pin, remember) }
                .onFailure { e -> _uiState.value = _uiState.value.copy(errorMessage = e.message) }
        }
    }
}
