package ru.rim.dd.feature.pairing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.rim.dd.core.ble.BleDevice
import ru.rim.dd.core.model.MeterDeviceName
import ru.rim.dd.data.local.DeviceStore
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
    private val deviceStore: DeviceStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PairingUiState())
    val uiState: StateFlow<PairingUiState> = _uiState.asStateFlow()

    /** Три последних вручную введённых номера ПУ — для быстрого набора. */
    val recentSerials: StateFlow<List<String>> = deviceStore.recentEnteredSerials
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private var scanJob: Job? = null

    fun reportError(message: String) {
        _uiState.value = _uiState.value.copy(errorMessage = message)
    }

    fun scan() {
        scanJob?.cancel()
        _uiState.value = _uiState.value.copy(isScanning = true, foundDevices = emptyList(), errorMessage = null)
        scanJob = viewModelScope.launch {
            repository.scanDevices()
                .filter { MeterDeviceName.looksLikeMeter(it.name) }
                .collect { device ->
                    val current = _uiState.value.foundDevices
                    if (current.none { it.address == device.address }) {
                        _uiState.value = _uiState.value.copy(foundDevices = current + device)
                    }
                }
            _uiState.value = _uiState.value.copy(
                isScanning = false,
                errorMessage = "Не удалось начать поиск — проверьте, что Bluetooth включён",
            )
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        _uiState.value = _uiState.value.copy(isScanning = false)
    }

    /**
     * cancelAndJoin вместо cancel: радио-скан должен реально остановиться до GATT-подключения,
     * иначе параллельные скан и GATT на Android дают потерянные notify и обрезанные кадры.
     */
    private suspend fun stopScanAndAwait() {
        scanJob?.cancelAndJoin()
        scanJob = null
        _uiState.value = _uiState.value.copy(isScanning = false)
    }

    fun selectDevice(device: BleDevice, pin: String, remember: Boolean) {
        _uiState.value = _uiState.value.copy(isConnecting = true, errorMessage = null)
        viewModelScope.launch {
            stopScanAndAwait()
            runCatching { repository.connectByAddress(device.address, pin, remember, device.name) }
                .onSuccess { _uiState.value = _uiState.value.copy(isConnecting = false, isConnected = true) }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(isConnecting = false, errorMessage = e.message ?: "Ошибка подключения")
                }
        }
    }

    fun connectByNumber(serialNumber: String, pin: String, remember: Boolean) {
        _uiState.value = _uiState.value.copy(isConnecting = true, errorMessage = null)
        viewModelScope.launch {
            deviceStore.addRecentEnteredSerial(serialNumber)
            stopScanAndAwait()
            runCatching { repository.connectBySerialNumber(serialNumber, pin, remember) }
                .onSuccess { _uiState.value = _uiState.value.copy(isConnecting = false, isConnected = true) }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(isConnecting = false, errorMessage = e.message ?: "Ошибка подключения")
                }
        }
    }
}
