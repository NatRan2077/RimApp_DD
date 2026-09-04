package ru.rim.dd.feature.readings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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

    fun refresh() {
        viewModelScope.launch { repository.refreshReadings() }
    }
}
