package ru.rim.dd.feature.network

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import ru.rim.dd.core.model.PhaseValues
import ru.rim.dd.core.model.RelaySource
import ru.rim.dd.ui.components.DataCard
import ru.rim.dd.ui.components.Divider
import ru.rim.dd.ui.components.MonoText
import ru.rim.dd.ui.components.ScreenHeader
import ru.rim.dd.ui.components.SectionLabel
import ru.rim.dd.ui.components.StatusChip
import ru.rim.dd.ui.components.formatNumber
import ru.rim.dd.ui.theme.RimError
import ru.rim.dd.ui.theme.RimLilac
import ru.rim.dd.ui.theme.RimPrimaryDeep
import ru.rim.dd.ui.theme.RimPrimaryLight
import ru.rim.dd.ui.theme.RimSuccess
import ru.rim.dd.ui.theme.RimTheme
import ru.rim.dd.ui.theme.RimViolet

@Composable
fun NetworkScreen(showObis: Boolean, viewModel: NetworkViewModel = hiltViewModel()) {
    val params by viewModel.networkParams.collectAsState()
    val relay by viewModel.relayState.collectAsState()
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
        ScreenHeader("Параметры сети", onRefresh = { refreshing = true; viewModel.refresh() }, refreshing = refreshing)

        // ---- Фазы ----
        val phaseColors = listOf(RimPrimaryDeep, RimViolet, RimLilac)
        if (params.voltage.isThreePhase) {
            Row(Modifier.fillMaxWidth().padding(bottom = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("A", "B", "C").forEachIndexed { i, ph ->
                    PhaseCard(
                        ph = ph,
                        voltage = phaseValue(params.voltage, i),
                        current = phaseValue(params.current, i),
                        color = phaseColors[i],
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(bottom = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SingleMetric("Напряжение", formatNumber(params.voltage.total, 2), "В", RimPrimaryDeep, params.voltage.total < 50 && params.voltage.total > 0, Modifier.weight(1f))
                SingleMetric("Ток", formatNumber(params.current.total, 3), "А", RimViolet, false, Modifier.weight(1f))
            }
        }

        // ---- Мощность ----
        SectionLabel("Мощность")
        DataCard {
            PowerRow("Активная", formatNumber(params.power.total, 3), "кВт", "1.0.1.7.0.255", showObis)
            Divider(Modifier.padding(vertical = 12.dp))
            PowerRow("Реактивная", formatNumber(params.reactivePowerKvar ?: 0.0, 3), "квар", "1.0.3.7.0.255", showObis)
            Divider(Modifier.padding(vertical = 12.dp))
            PowerRow("Полная", formatNumber(params.apparentPowerKva ?: 0.0, 3), "кВА", "1.0.9.7.0.255", showObis)
        }

        Box(Modifier.padding(top = 4.dp))
        // ---- Дополнительно ----
        SectionLabel("Дополнительно")
        DataCard {
            params.neutralCurrentA?.let {
                ExtraRow("Ток нейтрали", "${formatNumber(it, 3)} А", "1.0.91.7.0.255", showObis)
                Divider(Modifier.padding(vertical = 12.dp))
            }
            ExtraRow("Частота", "${formatNumber(params.frequencyHz ?: 0.0, 2)} Гц", "1.0.14.7.0.255", showObis)
            Divider(Modifier.padding(vertical = 12.dp))
            // Реле — статус
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("Реле", color = p.textSecondary, fontSize = 19.sp)
                    if (showObis) MonoText("0.0.96.3.10.255", color = RimPrimaryLight, fontSize = 15.sp, weight = FontWeight.Normal)
                }
                when {
                    relay.source == RelaySource.UNKNOWN -> StatusChip("НЕИЗВЕСТНО", p.textSecondary)
                    relay.isOn -> StatusChip("ВКЛЮЧЕНО", RimSuccess)
                    else -> StatusChip("ОТКЛЮЧЕНО", RimError)
                }
            }
        }
    }
}

private fun phaseValue(pv: PhaseValues, index: Int): Double = when (index) {
    0 -> pv.l1 ?: pv.total
    1 -> pv.l2 ?: 0.0
    else -> pv.l3 ?: 0.0
}

@Composable
private fun PhaseCard(ph: String, voltage: Double, current: Double, color: Color, modifier: Modifier) {
    val p = RimTheme.palette
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Brush.linearGradient(listOf(color.copy(alpha = if (p.dark) 0.20f else 0.10f), color.copy(alpha = 0.04f))))
            .border(1.5.dp, color.copy(alpha = 0.25f), RoundedCornerShape(18.dp))
            .padding(horizontal = 10.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Box(
            Modifier.size(28.dp).clip(CircleShape).background(color.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) { Text("~$ph", color = color, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold) }
        Text("Напр.", color = p.textSecondary, fontSize = 20.sp, modifier = Modifier.padding(top = 6.dp))
        MonoText(formatNumber(voltage, 1), color = if (voltage in 0.001..50.0) RimError else p.textPrimary, fontSize = 27.sp)
        Text("В", color = p.textFaint, fontSize = 20.sp)
        Divider(Modifier.padding(vertical = 6.dp))
        Text("Ток", color = p.textSecondary, fontSize = 20.sp)
        MonoText(formatNumber(current, 3), color = p.textPrimary, fontSize = 27.sp)
        Text("А", color = p.textFaint, fontSize = 20.sp)
    }
}

@Composable
private fun SingleMetric(label: String, value: String, unit: String, color: Color, warn: Boolean, modifier: Modifier) {
    val p = RimTheme.palette
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Brush.linearGradient(listOf(color.copy(alpha = if (p.dark) 0.20f else 0.10f), color.copy(alpha = 0.04f))))
            .border(1.5.dp, color.copy(alpha = 0.25f), RoundedCornerShape(18.dp))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, color = p.textSecondary, fontSize = 11.sp)
        MonoText(value, color = if (warn) RimError else p.textPrimary, fontSize = 24.sp, modifier = Modifier.padding(top = 4.dp))
        Text(unit, color = p.textFaint, fontSize = 10.sp)
    }
}

@Composable
private fun PowerRow(label: String, value: String, unit: String, obis: String, showObis: Boolean) {
    val p = RimTheme.palette
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column {
            Text(label, color = p.textSecondary, fontSize = 19.sp)
            if (showObis) MonoText(obis, color = RimPrimaryLight, fontSize = 15.sp, weight = FontWeight.Normal)
        }
        Row(verticalAlignment = Alignment.Bottom) {
            MonoText(value, color = p.textPrimary, fontSize = 23.sp)
            Text(" $unit", color = p.textPrimary.copy(alpha = 0.5f), fontSize = 12.sp)
        }
    }
}

@Composable
private fun ExtraRow(label: String, value: String, obis: String, showObis: Boolean) {
    val p = RimTheme.palette
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column {
            Text(label, color = p.textSecondary, fontSize = 19.sp)
            if (showObis) MonoText(obis, color = RimPrimaryLight, fontSize = 15.sp, weight = FontWeight.Normal)
        }
        MonoText(value, color = p.textPrimary, fontSize = 23.sp)
    }
}

