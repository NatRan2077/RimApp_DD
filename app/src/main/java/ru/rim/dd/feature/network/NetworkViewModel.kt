package ru.rim.dd.feature.network

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.rim.dd.core.model.NetworkParams
import ru.rim.dd.core.model.PhaseValues
import ru.rim.dd.core.model.RelaySource
import ru.rim.dd.core.model.RelayState
import ru.rim.dd.data.repository.MeterDataRepository
import ru.rim.dd.data.repository.RelayRepository
import javax.inject.Inject

/** UC-05 (параметры сети), UC-07/UC-08 (состояние и управление реле). Экран «Сеть». */
@HiltViewModel
class NetworkViewModel @Inject constructor(
    private val meterData: MeterDataRepository,
    private val relay: RelayRepository,
) : ViewModel() {

    val networkParams: StateFlow<NetworkParams> = meterData.networkParams()
        .stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5_000),
            NetworkParams(PhaseValues(0.0), PhaseValues(0.0), PhaseValues(0.0)),
        )

    val relayState: StateFlow<RelayState> = relay.relayState()
        .stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5_000),
            RelayState(isOn = false, powerLimitKw = 0.0, source = RelaySource.UNKNOWN),
        )

    /** Секунды, оставшиеся до включения реле (UC-08: обратный отсчёт 60 c, как в прошивке ДД). */
    private val _turnOnCountdown = MutableStateFlow<Int?>(null)
    val turnOnCountdown: StateFlow<Int?> = _turnOnCountdown

    /** Выполняется ли сейчас отправка команды включения на прибор. */
    private val _isSendingCommand = MutableStateFlow(false)
    val isSendingCommand: StateFlow<Boolean> = _isSendingCommand

    private var turnOnJob: Job? = null

    /** Кнопка «Обновить» — переспрашивает тот же буфер счётчика, что обновляет и «Показания». */
    fun refresh() {
        viewModelScope.launch { meterData.refreshReadings() }
    }

    fun turnRelayOff() {
        viewModelScope.launch { relay.turnRelayOff() }
    }

    fun turnRelayOn() {
        turnOnJob?.cancel()
        turnOnJob = viewModelScope.launch {
            try {
                for (secondsLeft in 60 downTo 1) {
                    _turnOnCountdown.value = secondsLeft
                    delay(1_000)
                }
                _turnOnCountdown.value = null
                _isSendingCommand.value = true
                relay.turnRelayOn()
            } finally {
                _isSendingCommand.value = false
                if (coroutineContext[Job]?.isCancelled == true) {
                    _turnOnCountdown.value = null
                }
            }
        }
    }

    fun cancelTurnOn() {
        turnOnJob?.cancel()
        turnOnJob = null
        _turnOnCountdown.value = null
    }
}
