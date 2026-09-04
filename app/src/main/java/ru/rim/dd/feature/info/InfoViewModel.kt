package ru.rim.dd.feature.info

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import ru.rim.dd.core.model.MeterInfo
import ru.rim.dd.data.repository.MeterRepository
import javax.inject.Inject

/** UC-06 — экран «Инфо». */
@HiltViewModel
class InfoViewModel @Inject constructor(
    repository: MeterRepository,
) : ViewModel() {

    val meterInfo: StateFlow<MeterInfo?> = repository.meterInfo()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}
