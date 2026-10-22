package ru.rim.dd.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.rim.dd.core.model.ConnectionState
import ru.rim.dd.data.repository.ConnectionRepository
import javax.inject.Inject

/**
 * Даёт AppNavHost состояние связи, чтобы граф сам уводил на экран «Подключение» при разрыве.
 *
 * Следит за потерей связи: если после успешного подключения прибор не отвечает дольше
 * [LOSS_TIMEOUT_MS] (ни «Подключено», ни обычного отключения), сеанс закрывается, а на экране
 * подключения показывается предупреждение об обрыве. Короткие провалы связи (переподключение
 * успело за 8 секунд) пользователь не затрагивают.
 */
@HiltViewModel
class ConnectionWatcherViewModel @Inject constructor(
    private val repository: ConnectionRepository,
    private val notice: ConnectionNotice,
) : ViewModel() {

    val connectionState: StateFlow<ConnectionState> = repository.connectionState()
        .stateIn(viewModelScope, SharingStarted.Eagerly, ConnectionState.Idle)

    /** true между «Подключено» и явным отключением: только тогда пропажу связи считаем обрывом. */
    private var sessionActive = false
    private var lossJob: Job? = null

    init {
        viewModelScope.launch {
            repository.connectionState().collect { state ->
                when (state) {
                    is ConnectionState.Connected -> {
                        sessionActive = true
                        cancelLossTimer()
                    }
                    is ConnectionState.Idle -> {
                        sessionActive = false
                        cancelLossTimer()
                    }
                    // Reconnecting / Connecting / Error во время сеанса — это и есть обрыв связи.
                    // Таймер запускается один раз: смены состояний внутри попыток его не сбрасывают.
                    else -> if (sessionActive && lossJob == null) startLossTimer()
                }
            }
        }
    }

    private fun startLossTimer() {
        lossJob = viewModelScope.launch {
            delay(LOSS_TIMEOUT_MS)
            lossJob = null
            sessionActive = false
            notice.post(
                "Связь с прибором потеряна более ${LOSS_TIMEOUT_MS / 1000} секунд. " +
                        "Соединение закрыто — подключитесь снова.",
            )
            repository.disconnect()
        }
    }

    private fun cancelLossTimer() {
        lossJob?.cancel()
        lossJob = null
    }

    /** Явный выход пользователя (кнопка «Назад»): закрывает сеанс, предупреждение не показывается. */
    fun disconnect() {
        sessionActive = false
        cancelLossTimer()
        viewModelScope.launch { repository.disconnect() }
    }

    private companion object {
        const val LOSS_TIMEOUT_MS = 15_000L
    }
}
