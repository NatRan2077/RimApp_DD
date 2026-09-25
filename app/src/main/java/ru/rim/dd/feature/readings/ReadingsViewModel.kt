package ru.rim.dd.feature.readings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.rim.dd.core.model.MeterInfo
import ru.rim.dd.core.model.Reading
import ru.rim.dd.data.repository.MeterRepository
import javax.inject.Inject

/** UC-03 (просмотр показаний) и UC-04 (обновление). Экран «Показания». */
@HiltViewModel
class ReadingsViewModel @Inject constructor(
    private val repository: MeterRepository,
) : ViewModel() {

    val readings: StateFlow<List<Reading>> = repository.readings()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // [Android-патч] Нужно для «геро»-карточки экрана (перенос дизайна): текущий тариф и часы
    // прибора берутся из того же буфера, что и показания (см. MeterInfo.currentTariff/deviceClock).
    val meterInfo: StateFlow<MeterInfo?> = repository.meterInfo()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun refresh() {
        viewModelScope.launch { repository.refreshReadings() }
    }
}
