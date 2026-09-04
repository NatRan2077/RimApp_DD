package ru.rim.dd.feature.network

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.rim.dd.core.model.NetworkParams
import ru.rim.dd.core.model.PhaseValues
import ru.rim.dd.core.model.RelayState
import ru.rim.dd.data.repository.MeterRepository
import javax.inject.Inject

/** UC-05 (параметры сети), UC-07/UC-08 (состояние и управление реле). Экран «Сеть». */
@HiltViewModel
class NetworkViewModel @Inject constructor(
    private val repository: MeterRepository,
) : ViewModel() {

    val networkParams: StateFlow<NetworkParams> = repository.networkParams()
        .stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5_000),
            NetworkParams(PhaseValues(0.0), PhaseValues(0.0), PhaseValues(0.0)),
        )

    val relayState: StateFlow<RelayState> = repository.relayState()
        .stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5_000),
            RelayState(isOn = false, powerLimitKw = 0.0, source = ru.rim.dd.core.model.RelaySource.UNKNOWN),
        )

    /** Секунды, оставшиеся до включения реле (UC-08: обратный отсчёт 60 c, как в прошивке ДД). */
    private val _turnOnCountdown = MutableStateFlow<Int?>(null)
    val turnOnCountdown: StateFlow<Int?> = _turnOnCountdown

    fun turnRelayOff() {
        viewModelScope.launch { repository.turnRelayOff() }
    }

    fun turnRelayOn() {
        viewModelScope.launch {
            for (secondsLeft in 60 downTo 1) {
                _turnOnCountdown.value = secondsLeft
                delay(1_000)
            }
            _turnOnCountdown.value = null
            repository.turnRelayOn()
        }
    }
}
