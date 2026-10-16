package ru.rim.dd.feature.journal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import ru.rim.dd.data.repository.MeterRepository
import javax.inject.Inject

/** Кнопка «+» рядом с «Обновить»: добавляет текущие данные прибора в журнал. */
@HiltViewModel
class AddToJournalViewModel @Inject constructor(
    private val repository: MeterRepository,
) : ViewModel() {

    /** [onResult] вызывается в главном потоке: true — добавлено, false — целых данных ещё нет. */
    fun add(onResult: (Boolean) -> Unit) {
        viewModelScope.launch { onResult(repository.addToJournal()) }
    }
}
