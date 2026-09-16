package ru.rim.dd.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import ru.rim.dd.core.model.ConnectionState

/** Экран «Настройки» — соответствует макету wf5_settings.png из ТЗ. */
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val connectionState by viewModel.connectionState.collectAsState()
    val signalStrengthDbm by viewModel.signalStrengthDbm.collectAsState()
    // [Android-патч] Уровень сигнала показываем только при реально активном соединении (см.
    // disconnect()/signalStrengthDbm() в SettingsViewModel).
    val isConnected = connectionState is ConnectionState.Connected
    // [Android-патч] см. ConnectionState.Reconnecting — механизм переподключения (MeterBleClient/
    // MeterRepositoryImpl): после неожиданного разрыва связь автоматически восстанавливается сама,
    // без участия пользователя, поэтому показываем это отдельным статусом, а не просто "Не подключено".
    val isReconnecting = connectionState is ConnectionState.Reconnecting
    // [Android-патч] Кнопку «Отключиться» держим доступной и во время Reconnecting — пользователь
    // должен иметь возможность прервать ожидание автопереподключения и вручную уйти на экран
    // «Подключение» выбрать другой прибор, а не ждать неопределённое время.
    val canDisconnect = connectionState !is ConnectionState.Idle

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

        Text("Соединение", style = MaterialTheme.typography.titleMedium)
        Text(
            when {
                isConnected -> "Подключено"
                isReconnecting -> "Переподключение..."
                else -> "Не подключено"
            }
        )
        // [Android-патч] см. MeterRepository.signalStrengthDbm() — null, пока нет соединения
        // или ни одного успешного чтения RSSI ещё не было (сразу после подключения).
        if (isConnected) {
            Text(signalStrengthDbm?.let { "Уровень сигнала: $it дБм" } ?: "Уровень сигнала: —")
        }
        // [Android-патч] см. canDisconnect выше — доступна и во время Reconnecting (прервать
        // ожидание автопереподключения). После нажатия приложение само уведёт на экран
        // «Подключение» (см. AppNavHost/ConnectionWatcherViewModel — реагируют на ConnectionState.Idle).
        Button(onClick = viewModel::disconnect, enabled = canDisconnect) { Text("Отключиться") }
    }
}
