package ru.rim.dd.feature.pairing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
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
        _uiState.value = _uiState.value.copy(
            isScanning = true,
            foundDevices = emptyList(),
            errorMessage = null
        )

        scanJob = viewModelScope.launch {
            repository.scanDevices()
                // Пульты РиМ 040.40 рекламируются в эфире как "RIM ..." или "AKROS ..."
                .filter { device ->
                    device.name?.let { name ->
                        NAME_PREFIXES.any { prefix ->
                            name.startsWith(prefix, ignoreCase = true)
                        }
                    } == true
                }
                .collect { device ->
                    val current = _uiState.value.foundDevices
                    if (current.none { it.address == device.address }) {
                        _uiState.value = _uiState.value.copy(
                            foundDevices = current + device
                        )
                    }
                }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        _uiState.value = _uiState.value.copy(isScanning = false)
    }

    /**
     * [Android-патч] НАЙДЕНА причина странного бага "обновление работает при подключении по
     * номеру, но не при подключении из списка сканирования": stopScan() выше делает
     * scanJob?.cancel() — это АСИНХРОННАЯ отмена, Job.cancel() не ждёт, пока корутина реально
     * остановится. А фактическая остановка радио-скана (scanner.stopScan()) происходит в блоке
     * awaitClose ВНУТРИ MeterBleClient.scan() — он выполняется только когда отмена долетит до
     * этой корутины, а это не мгновенно. Раньше selectDevice()/connectByNumber() вызывали
     * stopScan() и СРАЗУ ЖЕ (без ожидания) стартовали GATT-подключение — то есть физический BLE-
     * скан мог ещё идти в эфире ПАРАЛЛЕЛЬНО с началом GATT-соединения и первым обменом SNRM/GET.
     * Одновременные скан+GATT — известная проблема стека BLE на Android (нестабильные/потерянные
     * notify, обрезанные кадры), и именно это, похоже, портило ПЕРВЫЙ буфер после подключения
     * ЧЕРЕЗ СПИСОК (а дальше "не удалось разобрать DLMS-данные" тянулось на каждое "Обновить",
     * потому что нативный rx-буфер, скорее всего, остался с "хвостом" от повреждённого кадра).
     * Подключение по номеру (connectBySerialNumber → ble.connectBySerialNumber()) НЕ страдало этим,
     * потому что там скан честно suspend-запущенный: scan().first{} не возвращает управление,
     * пока сам скан не остановлен ПОЛНОСТЬЮ (включая awaitClose) — гонки просто не возникает.
     *
     * Исправлено: вместо cancel() используем cancelAndJoin() — он РЕАЛЬНО ждёт, пока предыдущий
     * scanJob (со всем его awaitClose) завершится, и только потом продолжаем к GATT-подключению.
     * Вызывается перед ОБОИМИ способами подключения — не только через список.
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
            stopScanAndAwait()
            runCatching { repository.connectBySerialNumber(serialNumber, pin, remember) }
                .onSuccess { _uiState.value = _uiState.value.copy(isConnecting = false, isConnected = true) }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(isConnecting = false, errorMessage = e.message ?: "Ошибка подключения")
                }
        }
    }

    private companion object {
        /** Пульты РиМ 040.40 рекламируются в эфире как "RIM ..." */
        val NAME_PREFIXES = setOf("RIM", "AKROS")
    }
}
