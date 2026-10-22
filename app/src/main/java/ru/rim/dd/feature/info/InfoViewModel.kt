package ru.rim.dd.feature.info

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.rim.dd.core.model.MeterInfo
import ru.rim.dd.core.model.TamperState
import ru.rim.dd.data.repository.ConnectionRepository
import ru.rim.dd.data.repository.MeterDataRepository
import javax.inject.Inject

/** UC-06 — экран «Инфо». */
@HiltViewModel
class InfoViewModel @Inject constructor(
    private val meterData: MeterDataRepository,
    private val connection: ConnectionRepository,
) : ViewModel() {

    val meterInfo: StateFlow<MeterInfo?> = meterData.meterInfo()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val tamperState: StateFlow<TamperState> = meterData.tamperState()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TamperState.UNKNOWN)

    val signalStrengthDbm: StateFlow<Int?> = connection.signalStrengthDbm()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun refresh() {
        viewModelScope.launch { meterData.refreshReadings() }
    }
}
