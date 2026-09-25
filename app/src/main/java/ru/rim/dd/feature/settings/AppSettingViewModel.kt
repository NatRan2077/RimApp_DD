package ru.rim.dd.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.rim.dd.data.local.AppSettings
import javax.inject.Inject

/**
 * [Android-патч] Настройки вида (тема + показ OBIS) на уровне всего приложения. Живёт выше
 * экранов (создаётся в AppNavHost), чтобы: тема применялась ко ВСЕМУ дереву (RimTheme в
 * MainActivity/AppNavHost), а тумблеры на экране «Настройки» и подписи OBIS на остальных
 * экранах читали одно и то же состояние.
 */
@HiltViewModel
class AppSettingsViewModel @Inject constructor(
    private val settings: AppSettings,
) : ViewModel() {

    val showObis: StateFlow<Boolean> = settings.showObis
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val darkTheme: StateFlow<Boolean> = settings.darkTheme
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun setShowObis(value: Boolean) {
        viewModelScope.launch { settings.setShowObis(value) }
    }

    fun setDarkTheme(value: Boolean) {
        viewModelScope.launch { settings.setDarkTheme(value) }
    }
}
