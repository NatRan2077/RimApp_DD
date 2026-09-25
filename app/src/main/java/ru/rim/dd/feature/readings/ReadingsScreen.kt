package ru.rim.dd.feature.readings

import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import ru.rim.dd.core.model.EnergyCategory
import ru.rim.dd.core.model.Reading
import ru.rim.dd.ui.components.Divider
import ru.rim.dd.ui.components.MonoText
import ru.rim.dd.ui.components.ScreenHeader
import ru.rim.dd.ui.components.SectionLabel
import ru.rim.dd.ui.components.formatDate
import ru.rim.dd.ui.components.formatNumber
import ru.rim.dd.ui.components.formatTime
import ru.rim.dd.ui.components.tariffLabel
import ru.rim.dd.ui.theme.RimGroupActiveExport
import ru.rim.dd.ui.theme.RimGroupActiveImport
import ru.rim.dd.ui.theme.RimGroupReactiveQ1
import ru.rim.dd.ui.theme.RimGroupReactiveQ4
import ru.rim.dd.ui.theme.RimHeroGradient
import ru.rim.dd.ui.theme.RimTheme

/**
 * [Android-патч] Экран «Показания» по обновлённому макету Figma: «геро»-карточка (текущий тариф,
 * дата/время, 4 мини-плитки итогов) и раскрывающиеся аккордеоны по видам энергии с сеткой
 * тарифов. Данные реальные (ViewModel), OBIS-подписи по флагу [showObis].
 */

/** Описание группы энергии для экрана: заголовок, короткое имя, цвет и база OBIS-кода. */
private data class GroupSpec(
    val category: EnergyCategory,
    val title: String,
    val short: String,
    val unit: String,
    val color: Color,
    val obisBase: String,   // напр. "1.0.1.8" → тариф n = "1.0.1.8.n.255", итог = "1.0.1.8.0.255"
)

private val GROUPS = listOf(
    GroupSpec(EnergyCategory.ACTIVE_IMPORT, "Активная · Потребление", "Акт. потр.", "кВт·ч", RimGroupActiveImport, "1.0.1.8"),
    GroupSpec(EnergyCategory.ACTIVE_EXPORT, "Активная · Отдача", "Акт. отд.", "кВт·ч", RimGroupActiveExport, "1.0.2.8"),
    GroupSpec(EnergyCategory.REACTIVE_Q1, "Реактивная Q1 · Потребление", "Реакт. Q1", "квар·ч", RimGroupReactiveQ1, "1.0.3.8"),
    GroupSpec(EnergyCategory.REACTIVE_Q4, "Реактивная Q4 · Отдача", "Реакт. Q4", "квар·ч", RimGroupReactiveQ4, "1.0.4.8"),
)

@Composable
fun ReadingsScreen(showObis: Boolean, viewModel: ReadingsViewModel = hiltViewModel()) {
    val readings by viewModel.readings.collectAsState()
    val meterInfo by viewModel.meterInfo.collectAsState()
    val p = RimTheme.palette
    var refreshing by remember { mutableStateOf(false) }
    val expanded = remember { mutableStateMapOf(EnergyCategory.ACTIVE_IMPORT to true) }

    val byCat = readings.groupBy { it.category }
    fun items(c: EnergyCategory) = byCat[c].orEmpty()
    fun total(items: List<Reading>): Double =
        items.firstOrNull { it.tariff == null }?.valueKwh ?: items.sumOf { it.valueKwh }
    fun unitOf(spec: GroupSpec) = items(spec.category).firstNotNullOfOrNull { it.unitLabel } ?: spec.unit

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(p.background)
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 24.dp),
    ) {
        LaunchedEffect(refreshing) { if (refreshing) { kotlinx.coroutines.delay(900); refreshing = false } }
        ScreenHeader("Показания", onRefresh = { refreshing = true; viewModel.refresh() }, refreshing = refreshing)

        // ---- Геро-карточка ----
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(Brush.linearGradient(RimHeroGradient))
                .padding(20.dp),
        ) {
            Column {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                    Column {
                        Text("ТЕКУЩИЙ ТАРИФ", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.7.sp)
                        MonoText(tariffLabel(meterInfo?.currentTariff), color = Color.White, fontSize = 48.sp, weight = FontWeight.ExtraBold)
                    }
                    Column(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White.copy(alpha = 0.15f))
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.End,
                    ) {
                        Text(formatDate(meterInfo?.deviceClock), color = Color.White.copy(alpha = 0.7f), fontSize = 10.sp)
                        MonoText(formatTime(meterInfo?.deviceClock), color = Color.White, fontSize = 19.sp)
                    }
                }
                // 4 мини-плитки итогов
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                    GROUPS.forEachIndexed { i, spec ->
                        QuickStat(
                            short = spec.short,
                            value = formatNumber(total(items(spec.category)), 3),
                            unit = unitOf(spec),
                            bg = if (i == 0) Color.White.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.07f),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        Box(Modifier.padding(top = 20.dp))
        SectionLabel("Группы по видам энергии")

        // ---- Аккордеоны по видам энергии ----
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            GROUPS.forEach { spec ->
                EnergyAccordion(
                    spec = spec,
                    items = items(spec.category),
                    unit = unitOf(spec),
                    total = total(items(spec.category)),
                    open = expanded[spec.category] == true,
                    onToggle = { expanded[spec.category] = !(expanded[spec.category] ?: false) },
                    showObis = showObis,
                )
            }
        }

        if (readings.isEmpty()) {
            Text("Нет данных — потяните «Обновить»", color = p.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
        }
    }
}

@Composable
private fun QuickStat(short: String, value: String, unit: String, bg: Color, modifier: Modifier) {
    Column(
        modifier = modifier.background(bg).padding(horizontal = 8.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(short, color = Color.White.copy(alpha = 0.55f), fontSize = 15.sp, maxLines = 1)
        MonoText(value, color = Color.White, fontSize = 17.sp, modifier = Modifier.padding(top = 3.dp))
        Text(unit, color = Color.White.copy(alpha = 0.45f), fontSize = 15.sp)
    }
}

@Composable
private fun EnergyAccordion(
    spec: GroupSpec,
    items: List<Reading>,
    unit: String,
    total: Double,
    open: Boolean,
    onToggle: () -> Unit,
    showObis: Boolean,
) {
    val p = RimTheme.palette
    val chevron by animateFloatAsState(if (open) 90f else 0f, label = "chevron")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(p.cardSurface)
            .border(1.dp, p.border, RoundedCornerShape(16.dp)),
    ) {
        // Шапка
        Row(
            modifier = Modifier.fillMaxWidth().clickable { onToggle() }.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(spec.color.copy(alpha = 0.13f)), contentAlignment = Alignment.Center) {
                Text("⚡", fontSize = 16.sp)
            }
            Column(Modifier.weight(1f)) {
                Text(spec.title, color = p.textPrimary, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                if (showObis) MonoText("${spec.obisBase}.0.255", color = spec.color.copy(alpha = 0.7f), fontSize = 15.sp, weight = FontWeight.Normal, modifier = Modifier.padding(top = 1.dp))
            }
            Column(horizontalAlignment = Alignment.End) {
                MonoText(formatNumber(total, 3), color = spec.color, fontSize = 16.sp)
                Text(unit, color = p.textFaint, fontSize = 10.sp)
            }
            Text("›", color = p.textFaint, fontSize = 40.sp, modifier = Modifier.padding(start = 4.dp).rotate(chevron))
        }
        // Раскрытая сетка тарифов
        if (open) {
            Divider()
            Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val tariffs = (1..8).map { t -> t to items.firstOrNull { it.tariff == t } }
                tariffs.chunked(4).forEach { rowItems ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        rowItems.forEach { (t, r) ->
                            TariffCell(t, r?.valueKwh ?: 0.0, spec, showObis, Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TariffCell(tariff: Int, value: Double, spec: GroupSpec, showObis: Boolean, modifier: Modifier) {
    val p = RimTheme.palette
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (p.dark) Color.White.copy(alpha = 0.05f) else spec.color.copy(alpha = 0.05f))
            .border(1.dp, spec.color.copy(alpha = 0.13f), RoundedCornerShape(10.dp))
            .padding(horizontal = 6.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(tariffLabel(tariff), color = spec.color, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        MonoText(formatNumber(value, 3), color = p.textPrimary, fontSize = 19.sp, modifier = Modifier.padding(top = 3.dp))
        if (showObis) Text("${spec.obisBase}.$tariff.255", color = spec.color.copy(alpha = 0.5f), fontSize = 10.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 3.dp))
    }
}
