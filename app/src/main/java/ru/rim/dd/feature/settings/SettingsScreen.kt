package ru.rim.dd.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

/** Экран «Настройки» — соответствует макету wf5_settings.png из ТЗ. */
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Настройки", style = MaterialTheme.typography.headlineSmall)
        Text("Управление сопряжением")
        Text("История показаний")
        Text("Синхронизация с ЛК энергосбыта")
        Text("О приложении")
        // TODO: реальные списки/переключатели — после того как заработают
        //  реальные данные из MeterRepository (UC-10, UC-12).
    }
}
