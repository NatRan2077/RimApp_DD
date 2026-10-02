package ru.rim.dd.feature.info

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import ru.rim.dd.core.model.PhaseCondition
import ru.rim.dd.core.model.PhaseStatus
import ru.rim.dd.core.model.TamperCondition
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

@Composable
fun InfoScreen(showObis: Boolean, viewModel: InfoViewModel = hiltViewModel()) {
    val info by viewModel.meterInfo.collectAsState()
    val tamper by viewModel.tamperState.collectAsState()
    val signal by viewModel.signalStrengthDbm.collectAsState()
    val p = RimTheme.palette
    // Локальный снимок: info — делегат collectAsState(), для него smart cast невозможен.
    val inf = info
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

        DataCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(
                    Modifier.size(62.dp).clip(RoundedCornerShape(16.dp)).background(Brush.linearGradient(RimIconGradient)),
                    contentAlignment = Alignment.Center,
                ) { Text("⚡", fontSize = 26.sp) }
                Column(Modifier.weight(1f)) {
                    MonoText(info?.model ?: "—", color = p.textPrimary, fontSize = 22.sp, weight = FontWeight.ExtraBold)
                    MonoText("№ ${info?.serialNumber ?: "—"}", color = p.textSecondary, fontSize = 12.sp, weight = FontWeight.Normal, modifier = Modifier.padding(top = 2.dp))
                    Box(Modifier.padding(top = 8.dp)) {
                        val sig = signal?.let { " · $it дБм" } ?: ""
                        StatusChip("Подключён$sig", RimSuccess)
                    }
                }
            }
        }

        Box(Modifier.padding(top = 4.dp))
        SectionLabel("Параметры прибора")
        DataCard {
            InfoRow("Температура", info?.temperatureC?.let { "${formatNumber(it, 1)} °C" } ?: "—", info?.let { "0.0.96.9.0.255" }, showObis)
            RowDivider()
            InfoRow("Напряжение батарейки", info?.backupVoltageV?.let { "${formatNumber(it, 3)} В" } ?: "—", "0.0.96.6.3.255", showObis)
            RowDivider()
            InfoRow("Часы прибора", formatDateTime(info?.deviceClock), "0.0.0.9.1.255", showObis, mono = true)
            inf?.clockValid?.let { valid ->
                RowDivider()
                InfoRow("Достоверность часов", if (valid) "Достоверны" else "Недостоверны", "0.0.1.0.0.255", showObis)
            }
            inf?.rdchDateTime?.let { dt ->
                RowDivider()
                InfoRow("Расчётный день (РДЧ)", formatDateTime(dt), "0.0.0.9.2.101", showObis, mono = true)
            }
            RowDivider()
            InfoRow("Версия ПО", info?.firmwareVersion?.takeIf { it.isNotBlank() && it != "—" } ?: "—", info?.firmwareVersionObis, showObis)
            RowDivider()
            InfoRow("Текущий тариф", tariffLabel(info?.currentTariff), "0.0.96.14.0.255", showObis)
            // HDLC-счётчики и время работы: строка появляется, только если прибор прислал значение.
            info?.operatingTime?.let {
                RowDivider()
                val u = info?.operatingTimeUnit?.let { unit -> " $unit" } ?: ""
                InfoRow("Время работы", "${formatNumber(it, 0)}$u", "0.0.96.8.0.255", showObis, mono = true)
            }
            info?.hdlcP1?.let {
                RowDivider()
                InfoRow("HDLC P1", it.toString(), "0.0.22.0.0.255", showObis, mono = true)
            }
            info?.hdlcP2?.let {
                RowDivider()
                InfoRow("HDLC P2", it.toString(), "0.1.22.0.0.255", showObis, mono = true)
            }
            // Версия ЖКИ — биты 6..7 поля «Значки ДД»; от неё зависит раскладка датчиков.
            if (!tamper.unknown) {
                RowDivider()
                val lcd = when (tamper.lcdVersion) {
                    0 -> "0 (до 890 пост.)"
                    1 -> "1 (890 пост.)"
                    else -> tamper.lcdVersion.toString()
                }
                InfoRow("Версия ЖКИ", lcd, "0.0.96.50.170.255", showObis)
            }
        }

        val grid = inf?.phaseStatus
        val dir = inf?.energyDirection
        if (grid != null || dir != null) {
            Box(Modifier.padding(top = 4.dp))
            SectionLabel("Состояние сети")
            DataCard {
                if (grid != null) {
                    InfoRow("Исполнение", if (grid.threePhase) "Трёхфазный" else "Однофазный", "1.0.96.50.0.255", showObis)
                    if (grid.threePhase) {
                        RowDivider()
                        ChipRow("Фазы", "1.0.96.50.0.255", showObis) {
                            PhaseChip("A", grid.a)
                            PhaseChip("B", grid.b)
                            PhaseChip("C", grid.c)
                        }
                        phaseProblemText(grid)?.let {
                            Text(it, color = RimError, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                        }
                        RowDivider()
                        ChipRow("Чередование фаз", "1.0.96.50.0.255", showObis) {
                            if (grid.sequenceError) StatusChip("Нарушено", RimError) else StatusChip("Норма", RimSuccess)
                        }
                    }
                }
                if (dir != null) {
                    if (grid != null) RowDivider()
                    ChipRow("Направление энергии", "0.0.96.50.170.255", showObis) {
                        if (dir.none) {
                            StatusChip("Нет", p.textSecondary)
                        } else {
                            if (dir.activePlus) StatusChip("A+", RimPrimaryLight)
                            if (dir.activeMinus) StatusChip("A−", RimPrimaryLight)
                            if (dir.reactivePlus) StatusChip("R+", RimPrimaryLight)
                            if (dir.reactiveMinus) StatusChip("R−", RimPrimaryLight)
                        }
                    }
                }
            }
        }

        Box(Modifier.padding(top = 4.dp))
        // Набор датчиков зависит от версии ЖКИ: у версии 0 только магнит и ПКЭ, у версии ≥ 1 — полный набор.
        SectionLabel("Пломбы и датчики")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (tamper.lcdVersion == 0 && !tamper.unknown) {
                SensorRow("Магнитное поле", tamper.magnet, tamper.unknown)
                SensorRow("ПКЭ", tamper.eqi, tamper.unknown)
            } else {
                SensorRow("Пломба корпуса", tamper.cover, tamper.unknown)
                SensorRow("Пломба клеммника", tamper.meter, tamper.unknown)
                SensorRow("Магнитное поле", tamper.magnet, tamper.unknown)
                SensorRow("Батарея", tamper.battery, tamper.unknown)
                SensorRow("ВЧ поле", tamper.rf, tamper.unknown)
                SensorRow("Самодиагностика", tamper.selfDiagnosticsFault, tamper.unknown)
                SensorRow("ПКЭ", tamper.eqi, tamper.unknown)
                SensorRow("Небаланс", tamper.unbalance, tamper.unknown)
            }
            if (tamper.powerLimitExceeded) SensorRow("Превышен лимит мощности", TamperCondition.ACTIVE, false)
        }
    }
}

@Composable
private fun ChipRow(label: String, obis: String?, showObis: Boolean, chips: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        LabelWithObis(label, obis, showObis)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) { chips() }
    }
}

@Composable
private fun PhaseChip(name: String, condition: PhaseCondition) {
    val color = when (condition) {
        PhaseCondition.PRESENT -> RimSuccess
        PhaseCondition.ABSENT -> RimError
        PhaseCondition.REVERSE -> RimWarn
    }
    StatusChip(name, color)
}

private fun phaseProblemText(g: PhaseStatus): String? {
    val names = listOf("A" to g.a, "B" to g.b, "C" to g.c)
    val absent = names.filter { it.second == PhaseCondition.ABSENT }.joinToString(", ") { it.first }
    val reverse = names.filter { it.second == PhaseCondition.REVERSE }.joinToString(", ") { it.first }
    val parts = buildList {
        if (absent.isNotEmpty()) add("нет фазы: $absent")
        if (reverse.isNotEmpty()) add("переток: $reverse")
    }
    return parts.joinToString(" · ").takeIf { it.isNotEmpty() }?.replaceFirstChar { it.uppercase() }
}

@Composable
private fun RowDivider() {
    Divider(Modifier.padding(vertical = 12.dp))
}

@Composable
private fun RowScope.LabelWithObis(label: String, obis: String?, showObis: Boolean) {
    Column(Modifier.weight(1f)) {
        Text(label, color = RimTheme.palette.textSecondary, fontSize = 13.sp)
        if (showObis && obis != null) MonoText(obis, color = RimPrimaryLight, fontSize = 9.sp, weight = FontWeight.Normal, modifier = Modifier.padding(top = 1.dp))
    }
}

@Composable
private fun InfoRow(label: String, value: String, obis: String?, showObis: Boolean, mono: Boolean = false) {
    val p = RimTheme.palette
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
        LabelWithObis(label, obis, showObis)
        if (mono) {
            MonoText(value, color = p.textPrimary, fontSize = 14.sp)
        } else {
            Text(value, color = p.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
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
        Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Default)
    }
}
