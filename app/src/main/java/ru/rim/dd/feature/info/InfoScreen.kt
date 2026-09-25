package ru.rim.dd.feature.info

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import ru.rim.dd.core.model.MeterInfo
import ru.rim.dd.core.model.TamperCondition
import ru.rim.dd.core.model.TamperState
import ru.rim.dd.ui.components.DataCard
import ru.rim.dd.ui.components.Divider
import ru.rim.dd.ui.components.MonoText
import ru.rim.dd.ui.components.ScreenHeader
import ru.rim.dd.ui.components.SectionLabel
import ru.rim.dd.ui.components.StatusChip
import ru.rim.dd.ui.components.formatDateTime
import ru.rim.dd.ui.components.formatNumber
import ru.rim.dd.ui.components.tariffLabel
import ru.rim.dd.ui.theme.RimError
import ru.rim.dd.ui.theme.RimIconGradient
import ru.rim.dd.ui.theme.RimPrimaryLight
import ru.rim.dd.ui.theme.RimSuccess
import ru.rim.dd.ui.theme.RimTheme
import ru.rim.dd.ui.theme.RimWarn

/**
 * [Android-патч] Экран «Инфо» (О счётчике) по макету Figma: карточка прибора, параметры,
 * пломбы/датчики, кнопка диагностики журналов. Данные реальные (ViewModel).
 */
@Composable
fun InfoScreen(showObis: Boolean, viewModel: InfoViewModel = hiltViewModel()) {
    val info by viewModel.meterInfo.collectAsState()
    val tamper by viewModel.tamperState.collectAsState()
    val signal by viewModel.signalStrengthDbm.collectAsState()
    val p = RimTheme.palette
    var refreshing by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(p.background)
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 24.dp),
    ) {
        LaunchedEffect(refreshing) { if (refreshing) { kotlinx.coroutines.delay(900); refreshing = false } }
        ScreenHeader("О счётчике", onRefresh = { refreshing = true; viewModel.refresh() }, refreshing = refreshing)

        // ---- Карточка прибора ----
        DataCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(
                    Modifier.size(62.dp).clip(RoundedCornerShape(16.dp)).background(Brush.linearGradient(RimIconGradient)),
                    contentAlignment = Alignment.Center,
                ) { Text("⚡", fontSize = 26.sp) }
                Column(Modifier.weight(1f)) {
                    MonoText(info?.model ?: "—", color = p.textPrimary, fontSize = 22.sp, weight = FontWeight.ExtraBold)
                    MonoText("№ ${info?.serialNumber ?: "—"}", color = p.textSecondary, fontSize = 19.sp, weight = FontWeight.Normal, modifier = Modifier.padding(top = 2.dp))
                    Box(Modifier.padding(top = 8.dp)) {
                        val sig = signal?.let { " · $it дБм" } ?: ""
                        StatusChip("Подключён$sig", RimSuccess)
                    }
                }
            }
        }

        Box(Modifier.padding(top = 4.dp))
        // ---- Параметры прибора ----
        SectionLabel("Параметры прибора")
        DataCard {
            InfoRow("Температура", info?.temperatureC?.let { "${formatNumber(it, 1)} °C" } ?: "—", info?.let { "0.0.96.9.0.255" }, showObis)
            Divider(Modifier.padding(vertical = 12.dp))
            InfoRow("Напряжение батарейки", info?.backupVoltageV?.let { "${formatNumber(it, 3)} В" } ?: "—", "0.0.96.6.3.255", showObis)
            Divider(Modifier.padding(vertical = 12.dp))
            InfoRow("Часы прибора", formatDateTime(info?.deviceClock), "0.0.0.9.1.255", showObis, mono = true)
            Divider(Modifier.padding(vertical = 12.dp))
            InfoRow("Версия ПО", info?.firmwareVersion?.takeIf { it.isNotBlank() && it != "—" } ?: "—", info?.firmwareVersionObis, showObis)
            Divider(Modifier.padding(vertical = 12.dp))
            InfoRow("Текущий тариф", tariffLabel(info?.currentTariff), "0.0.96.14.0.255", showObis)
        }

        Box(Modifier.padding(top = 4.dp))
        // ---- Пломбы и датчики ----
        SectionLabel("Пломбы и датчики")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SensorRow("Пломба корпуса", tamper.cover, tamper.unknown)
            SensorRow("Пломба клеммника", tamper.meter, tamper.unknown)
            SensorRow("Магнитное поле", tamper.magnet, tamper.unknown)
            SensorRow("Батарея", tamper.battery, tamper.unknown)
            SensorRow("ВЧ поле", tamper.rf, tamper.unknown)
            if (tamper.powerLimitExceeded) SensorRow("Превышен лимит мощности", TamperCondition.ACTIVE, false)
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, obis: String?, showObis: Boolean, mono: Boolean = false) {
    val p = RimTheme.palette
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            Text(label, color = p.textSecondary, fontSize = 19.sp)
            if (showObis && obis != null) MonoText(obis, color = RimPrimaryLight, fontSize = 15.sp, weight = FontWeight.Normal, modifier = Modifier.padding(top = 1.dp))
        }
        if (mono) {
            MonoText(value, color = p.textPrimary, fontSize = 19.sp)
        } else {
            Text(value, color = p.textPrimary, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun SensorRow(label: String, condition: TamperCondition, unknown: Boolean) {
    val p = RimTheme.palette
    val (color, text) = when {
        unknown -> p.textSecondary to "—"
        condition == TamperCondition.ACTIVE -> RimError to "Нарушение"
        condition == TamperCondition.FLAG -> RimWarn to "Была сработка"
        else -> RimSuccess to "Норма"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(color.copy(alpha = 0.08f))
            .border(1.5.dp, color.copy(alpha = 0.22f), RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (condition == TamperCondition.ACTIVE && !unknown) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
            }
            Text(label, color = p.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(text, color = color, fontSize = 19.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Default)
    }
}
