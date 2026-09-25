package ru.rim.dd.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import ru.rim.dd.core.model.ConnectionState
import ru.rim.dd.ui.components.DataCard
import ru.rim.dd.ui.components.Divider
import ru.rim.dd.ui.components.MonoText
import ru.rim.dd.ui.components.SectionLabel
import ru.rim.dd.ui.components.ToggleRow
import ru.rim.dd.ui.theme.RimError
import ru.rim.dd.ui.theme.RimIconGradient
import ru.rim.dd.ui.theme.RimTheme

/**
 * [Android-патч] Экран «Настройки» по макету Figma: карточка соединения с кнопкой «Отключиться»,
 * тумблеры «OBIS коды» и «Тёмная тема», ссылки раздела «Приложение», подпись версии.
 * Тумблеры управляют глобальными настройками ([showObis]/[dark] приходят из AppSettingsViewModel).
 */
@Composable
fun SettingsScreen(
    showObis: Boolean,
    onShowObisChange: (Boolean) -> Unit,
    dark: Boolean,
    onDarkChange: (Boolean) -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val connection by viewModel.connectionState.collectAsState()
    val signal by viewModel.signalStrengthDbm.collectAsState()
    val info by viewModel.meterInfo.collectAsState()
    val p = RimTheme.palette
    val connected = connection is ConnectionState.Connected || connection is ConnectionState.Reconnecting

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(p.background)
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 24.dp),
    ) {
        Column(Modifier.padding(bottom = 20.dp)) {
            Text("Настройки", color = p.textPrimary, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
            Text("Управление подключением и видом", color = p.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(top = 3.dp))
        }

        // ---- Соединение ----
        SectionLabel("Соединение")
        DataCard(noPad = true) {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(
                    Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(Brush.linearGradient(RimIconGradient)),
                    contentAlignment = Alignment.Center,
                ) { Text("⚡", fontSize = 18.sp) }
                Column(Modifier.weight(1f)) {
                    Text(info?.model ?: "Не подключено", color = p.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    val sig = signal?.let { " · $it дБм" } ?: ""
                    MonoText("№${info?.serialNumber ?: "—"}$sig", color = p.textSecondary, fontSize = 11.sp, weight = FontWeight.Normal)
                }
            }
            if (connected) {
                Divider()
                Box(Modifier.padding(16.dp)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(RimError.copy(alpha = 0.12f))
                            .clickable { viewModel.disconnect() }
                            .padding(vertical = 11.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Отключиться", color = RimError, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        Box(Modifier.padding(top = 4.dp))
        // ---- Отображение ----
        SectionLabel("Отображение")
        DataCard(noPad = true) {
            ToggleRow("OBIS коды", "Показывать коды параметров", showObis, onShowObisChange)
            Divider()
            ToggleRow("Тёмная тема", "Переключить цветовую схему", dark, onDarkChange)
        }

        Text(
            "Дистанционный дисплей РиМ · v0.0.1 · 2026",
            color = p.textFaint, fontSize = 12.sp,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}
