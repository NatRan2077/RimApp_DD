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

/**
 * [Android-патч] Отдельная маленькая ViewModel ТОЛЬКО для того, чтобы сам навигационный граф
 * (AppNavHost — не конкретный Compose-экран) знал о состоянии связи и мог сам уводить
 * пользователя на экран «Подключение», когда связь разорвана (см. LaunchedEffect в AppNavHost).
 * Без неё пришлось бы дублировать подписку на connectionState на каждом из четырёх основных
 * экранов (Показания/Сеть/Инфо/Настройки) — а переход нужен ровно один раз, на уровне графа.
 */

@HiltViewModel
class ConnectionWatcherViewModel @Inject constructor(
    repository: MeterRepository,
) : ViewModel() {
    val connectionState: StateFlow<ConnectionState> = repository.connectionState()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConnectionState.Idle)
}
