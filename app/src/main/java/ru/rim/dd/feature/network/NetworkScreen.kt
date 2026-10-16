package ru.rim.dd.feature.network

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import ru.rim.dd.core.model.LineVoltages
import ru.rim.dd.core.model.PhaseValues
import ru.rim.dd.core.model.RelaySource
import ru.rim.dd.ui.components.DataCard
import ru.rim.dd.ui.components.Divider
import ru.rim.dd.ui.components.MonoText
import ru.rim.dd.ui.components.RimShapes
import ru.rim.dd.ui.components.ScreenHeader
import ru.rim.dd.ui.components.SectionLabel
import ru.rim.dd.ui.components.StatusChip
import ru.rim.dd.ui.components.formatNumber
import ru.rim.dd.ui.theme.RimError
import ru.rim.dd.ui.theme.RimFonts
import ru.rim.dd.ui.theme.RimSuccess
import ru.rim.dd.ui.theme.RimTheme

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

        val phaseColors = listOf(p.accent, p.accent, p.accent)
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
                SingleMetric("Напряжение", formatNumber(params.voltage.total, 2), "В", p.accent, params.voltage.total < 50 && params.voltage.total > 0, Modifier.weight(1f))
                SingleMetric("Ток", formatNumber(params.current.total, 3), "А", p.accent, false, Modifier.weight(1f))
            }
        }

        SectionLabel("Мощность")
        DataCard {
            PowerRow("Активная", formatNumber(params.power.total, 3), "кВт", "1.0.1.7.0.255", showObis)
            RowDivider()
            PowerRow("Реактивная", formatNumber(params.reactivePowerKvar ?: 0.0, 3), "квар", "1.0.3.7.0.255", showObis)
            RowDivider()
            PowerRow("Полная", formatNumber(params.apparentPowerKva ?: 0.0, 3), "кВА", "1.0.9.7.0.255", showObis)
        }

        Box(Modifier.padding(top = 4.dp))
        SectionLabel("Дополнительно")
        DataCard {
            params.neutralCurrentA?.let {
                ExtraRow("Ток нейтрали", "${formatNumber(it, 3)} А", "1.0.91.7.0.255", showObis)
                RowDivider()
            }
            // cos φ и линейные напряжения: строк нет, пока прибор не прислал значения.
            params.cosPhi?.let { cp ->
                ExtraRow("cos φ", formatNumber(cp.total, 3), "1.0.13.7.0.255", showObis)
                RowDivider()
                if (cp.isThreePhase) {
                    ExtraRow("cos φ A / B / C", "${formatNumber(cp.l1 ?: 0.0, 3)} / ${formatNumber(cp.l2 ?: 0.0, 3)} / ${formatNumber(cp.l3 ?: 0.0, 3)}", "1.0.33/53/73.7.0.255", showObis)
                    RowDivider()
                }
            }
            params.lineVoltages?.let { lv ->
                if (lv.l12 != null || lv.l23 != null || lv.l13 != null) {
                    LineVoltagesBlock(lv, if (lv.legacyCodes) "1.0.12.7.1/2/3.255" else "1.0.124/125/126.7.0.255", showObis)
                    RowDivider()
                }
            }
            ExtraRow("Частота", "${formatNumber(params.frequencyHz ?: 0.0, 2)} Гц", "1.0.14.7.0.255", showObis)
            RowDivider()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                LabelWithObis("Реле", "0.0.96.3.10.255", showObis)
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

/** Карточка фазы/метрики: тонкая рамка и фирменная полоса сверху, скругление 4 dp. */
private fun Modifier.accentCard(color: Color, surface: Color, border: Color): Modifier = this
    .clip(RimShapes.card)
    .background(surface)
    .border(1.dp, border, RimShapes.card)
    .drawBehind { drawRect(color, size = androidx.compose.ui.geometry.Size(size.width, 4.dp.toPx())) }

@Composable
private fun PhaseCard(ph: String, voltage: Double, current: Double, color: Color, modifier: Modifier) {
    val p = RimTheme.palette
    Column(
        modifier = modifier
            .accentCard(color, p.cardSurface, p.border)
            .padding(start = 6.dp, end = 6.dp, top = 14.dp, bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text("Фаза $ph", color = p.accentText, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, fontFamily = RimFonts.Raleway)
        Text("U, В", color = p.textSecondary, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
        MonoText(formatNumber(voltage, 1), color = if (voltage in 0.001..50.0) RimError else p.textPrimary, fontSize = 30.sp, weight = FontWeight.SemiBold)
        Text("I, А", color = p.textSecondary, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
        MonoText(formatNumber(current, 3), color = p.textPrimary, fontSize = 23.sp, weight = FontWeight.SemiBold)
    }
}

@Composable
private fun SingleMetric(label: String, value: String, unit: String, color: Color, warn: Boolean, modifier: Modifier) {
    val p = RimTheme.palette
    Column(
        modifier = modifier
            .accentCard(color, p.cardSurface, p.border)
            .padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("$label, $unit", color = p.textSecondary, fontSize = 15.sp)
        MonoText(value, color = if (warn) RimError else p.textPrimary, fontSize = 34.sp, weight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun RowDivider() {
    Divider(Modifier.padding(vertical = 10.dp))
}

@Composable
private fun LabelWithObis(label: String, obis: String, showObis: Boolean) {
    Column {
        Text(label, color = RimTheme.palette.textSecondary, fontSize = 16.sp)
        if (showObis) MonoText(obis, color = RimTheme.palette.textFaint, fontSize = 12.sp, weight = FontWeight.Normal)
    }
}

@Composable
private fun PowerRow(label: String, value: String, unit: String, obis: String, showObis: Boolean) {
    val p = RimTheme.palette
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        LabelWithObis(label, obis, showObis)
        Row(verticalAlignment = Alignment.Bottom) {
            MonoText(value, color = p.textPrimary, fontSize = 24.sp, weight = FontWeight.SemiBold)
            Text(" $unit", color = p.textSecondary, fontSize = 15.sp, modifier = Modifier.padding(bottom = 3.dp))
        }
    }
}

/**
 * Строка «название — значение». Название занимает остаток ширины (weight), поэтому слово не
 * «ломается» по буквам, когда значение длинное; длинное значение (cos φ по трём фазам) мельче.
 */
@Composable
private fun ExtraRow(label: String, value: String, obis: String, showObis: Boolean) {
    val p = RimTheme.palette
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).padding(end = 8.dp)) { LabelWithObis(label, obis, showObis) }
        MonoText(value, color = p.textPrimary, fontSize = if (value.length > 12) 17.sp else 22.sp, weight = FontWeight.SemiBold)
    }
}

/** Линейные напряжения трёхфазного ПУ: три колонки L1–L2 / L2–L3 / L1–L3 вместо трёх длинных строк. */
@Composable
private fun LineVoltagesBlock(lv: LineVoltages, obis: String, showObis: Boolean) {
    val p = RimTheme.palette
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LabelWithObis("Линейные напряжения, В", obis, showObis)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("L1–L2" to lv.l12, "L2–L3" to lv.l23, "L1–L3" to lv.l13).forEach { (name, value) ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(name, color = p.textSecondary, fontSize = 14.sp)
                    MonoText(value?.let { formatNumber(it, 1) } ?: "—", color = p.textPrimary, fontSize = 22.sp, weight = FontWeight.SemiBold)
                }
            }
        }
    }
}
