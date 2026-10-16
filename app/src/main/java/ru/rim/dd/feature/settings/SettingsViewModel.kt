package ru.rim.dd.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.rim.dd.core.model.ConnectionState
import ru.rim.dd.core.model.MeterInfo
import ru.rim.dd.core.model.Reading
import ru.rim.dd.data.repository.MeterRepository
import javax.inject.Inject

/** UC-10 (история) и UC-12 (управление сопряжёнными устройствами). Экран «Настройки». */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: MeterRepository,
) : ViewModel() {

    val connectionState: StateFlow<ConnectionState> = repository.connectionState()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConnectionState.Idle)

    val signalStrengthDbm: StateFlow<Int?> = repository.signalStrengthDbm()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val meterInfo: StateFlow<MeterInfo?> = repository.meterInfo()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun history(serialNumber: String): StateFlow<List<Reading>> =
        repository.history(serialNumber)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun forgetDevice(serialNumber: String) {
        viewModelScope.launch { repository.forgetDevice(serialNumber) }
    }

    /** Закрывает BLE-сеанс, не забывая прибор (в отличие от forgetDevice). */
    fun disconnect() {
        viewModelScope.launch { repository.disconnect() }
    }
}
