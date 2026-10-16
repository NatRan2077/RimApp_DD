package ru.rim.dd.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import ru.rim.dd.ui.components.DataCard
import ru.rim.dd.ui.components.Divider
import ru.rim.dd.ui.components.MonoText
import ru.rim.dd.ui.components.RimShapes
import ru.rim.dd.ui.components.SectionLabel
import ru.rim.dd.ui.components.ToggleRow
import ru.rim.dd.ui.theme.RimError
import ru.rim.dd.ui.theme.RimFonts
import ru.rim.dd.ui.theme.RimTheme

@Composable
fun SettingsScreen(
    showObis: Boolean,
    onShowObisChange: (Boolean) -> Unit,
    dark: Boolean,
    onDarkChange: (Boolean) -> Unit,
    onOpenJournal: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val signal by viewModel.signalStrengthDbm.collectAsState()
    val info by viewModel.meterInfo.collectAsState()
    val p = RimTheme.palette

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(p.background)
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 24.dp),
    ) {
        Column(Modifier.padding(bottom = 20.dp)) {
            Text("Настройки", color = p.textPrimary, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, fontFamily = RimFonts.Raleway)
            Text("Управление подключением и видом", color = p.textSecondary, fontSize = 15.sp, modifier = Modifier.padding(top = 3.dp))
        }

        SectionLabel("Соединение")
        DataCard(noPad = true) {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(info?.model ?: "Не подключено", color = p.textSecondary, fontSize = 17.sp, fontWeight = FontWeight.Bold, fontFamily = RimFonts.Raleway)
                    MonoText("№ ${info?.serialNumber ?: "—"}", color = p.textPrimary, fontSize = 24.sp, weight = FontWeight.SemiBold)
                    signal?.let { MonoText("Сигнал $it дБм", color = p.textSecondary, fontSize = 15.sp, weight = FontWeight.Normal) }
                }
            }
            // Кнопка есть всегда: при потере связи (переподключение, ошибка) выйти из сеанса нужно так же,
            // как и при нормальной работе. Экран «Настройки» доступен только внутри сеанса.
            Divider()
            Box(Modifier.padding(16.dp)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RimShapes.control)
                        .border(1.5.dp, RimError, RimShapes.control)
                        .clickable { viewModel.disconnect() }
                        .padding(vertical = 13.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Отключиться", color = RimError, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        Box(Modifier.padding(top = 4.dp))
        SectionLabel("Данные")
        DataCard(noPad = true) {
            Row(
                Modifier.fillMaxWidth().clickable { onOpenJournal() }.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Журнал", color = p.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text("Приборы, к которым вы подключались", color = p.textSecondary, fontSize = 14.sp)
                }
                Text("›", color = p.textSecondary, fontSize = 26.sp)
            }
        }

        Box(Modifier.padding(top = 16.dp))
        SectionLabel("Отображение")
        DataCard(noPad = true) {
            ToggleRow("OBIS коды", "Показывать коды параметров", showObis, onShowObisChange)
            Divider()
            ToggleRow("Тёмная тема", "Переключить цветовую схему", dark, onDarkChange)
        }

        Text(
            "Дистанционный дисплей РиМ · v0.0.1 · 2026",
            color = p.textFaint, fontSize = 14.sp,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}
