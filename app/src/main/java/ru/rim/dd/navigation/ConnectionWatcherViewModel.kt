package ru.rim.dd.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import ru.rim.dd.core.model.ConnectionState
import ru.rim.dd.data.repository.MeterRepository
import javax.inject.Inject

/** Даёт AppNavHost состояние связи, чтобы граф сам уводил на экран «Подключение» при разрыве. */
@HiltViewModel
class ConnectionWatcherViewModel @Inject constructor(
    repository: MeterRepository,
) : ViewModel() {
    val connectionState: StateFlow<ConnectionState> = repository.connectionState()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConnectionState.Idle)
}
