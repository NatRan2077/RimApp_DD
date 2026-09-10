package ru.rim.dd.feature.info

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.rim.dd.core.model.MeterInfo
import ru.rim.dd.data.repository.MeterRepository
import javax.inject.Inject

/** UC-06 — экран «Инфо». */
@HiltViewModel
class InfoViewModel @Inject constructor(
    private val repository: MeterRepository,
) : ViewModel() {

    val meterInfo: StateFlow<MeterInfo?> = repository.meterInfo()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Кнопка «Обновить» — переспрашивает тот же буфер счётчика, что обновляет и «Показания». */
    fun refresh() {
        viewModelScope.launch { repository.refreshReadings() }
    }

    /**
     * [Android-патч] Диагностическая кнопка — пробует прочитать буфер каждого известного из
     * паспорта прибора "журнала" (см. MeterRepositoryImpl.LOG_CANDIDATES). Результат пока только
     * в logcat (формат записей неизвестен заранее), поэтому здесь нет отдельного StateFlow —
     * как появится реальный лог с прибора, на его основе добавится нормальный парсер и экран.
     */
    fun probeLogs() {
        viewModelScope.launch { repository.probeAllLogs() }
    }
}
