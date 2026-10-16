package ru.rim.dd.feature.readings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import ru.rim.dd.core.model.EnergyCategory
import ru.rim.dd.core.model.Reading
import ru.rim.dd.ui.components.Divider
import ru.rim.dd.ui.components.MonoText
import ru.rim.dd.ui.components.RimShapes
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
import ru.rim.dd.ui.theme.RimTheme

private data class GroupSpec(
    val category: EnergyCategory,
    val title: String,
    val unit: String,
    val color: Color,
    val obisBase: String,   // "1.0.1.8" → тариф n = "1.0.1.8.n.<period>", итог = "1.0.1.8.0.<period>"
)

private val ACTIVE_GROUPS = listOf(
    GroupSpec(EnergyCategory.ACTIVE_IMPORT, "A+ · Потребление", "кВт·ч", RimGroupActiveImport, "1.0.1.8"),
    GroupSpec(EnergyCategory.ACTIVE_EXPORT, "A− · Отдача", "кВт·ч", RimGroupActiveExport, "1.0.2.8"),
)
private val REACTIVE_GROUPS = listOf(
    GroupSpec(EnergyCategory.REACTIVE_Q1, "R+ · Q1, потребление", "квар·ч", RimGroupReactiveQ1, "1.0.3.8"),
    GroupSpec(EnergyCategory.REACTIVE_Q4, "R− · Q4, отдача", "квар·ч", RimGroupReactiveQ4, "1.0.4.8"),
)

private fun total(items: List<Reading>): Double =
    items.firstOrNull { it.tariff == null }?.valueKwh ?: items.filter { it.tariff != null }.sumOf { it.valueKwh }

private fun unitOf(items: List<Reading>, fallback: String): String =
    items.firstNotNullOfOrNull { it.unitLabel } ?: fallback

/** Длинное число уменьшаем пропорционально, чтобы оно всегда помещалось в одну строку. */
private fun fitSize(text: String, base: TextUnit, maxChars: Int): TextUnit =
    if (text.length <= maxChars) base else (base.value * maxChars / text.length).sp

@Composable
fun ReadingsScreen(showObis: Boolean, viewModel: ReadingsViewModel = hiltViewModel()) {
    val readings by viewModel.readings.collectAsState()
    val meterInfo by viewModel.meterInfo.collectAsState()
    val p = RimTheme.palette
    var refreshing by remember { mutableStateOf(false) }
    val expanded = remember { mutableStateMapOf("255:${EnergyCategory.ACTIVE_IMPORT}" to true) }

    val current = readings.filter { !it.isRdch }
    val rdch = readings.filter { it.isRdch }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(p.background)
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 24.dp),
    ) {
        LaunchedEffect(refreshing) { if (refreshing) { kotlinx.coroutines.delay(900); refreshing = false } }
        ScreenHeader("Показания", onRefresh = { refreshing = true; viewModel.refresh() }, refreshing = refreshing)

        // Прибор и дата
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(Modifier.weight(1f)) {
                Text(meterInfo?.model ?: "—", color = p.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text("  ·  № ${meterInfo?.serialNumber ?: "—"}", color = p.textSecondary, fontSize = 16.sp)
            }
            Text(formatDate(meterInfo?.deviceDate), color = p.textSecondary, fontSize = 16.sp)
        }
        Divider()
        Box(Modifier.height(14.dp))

        // Текущий тариф и время прибора — сплошная фирменная плашка
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RimShapes.card)
                .background(p.accent)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("ТЕКУЩИЙ ТАРИФ", color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.sp)
                MonoText(tariffLabel(meterInfo?.currentTariff), color = Color.White, fontSize = 38.sp, weight = FontWeight.Bold)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("Время прибора", color = Color.White.copy(alpha = 0.85f), fontSize = 15.sp)
                MonoText(formatTime(meterInfo?.deviceTime), color = Color.White, fontSize = 26.sp, weight = FontWeight.SemiBold)
            }
        }

        EnergySection("Активная энергия", ACTIVE_GROUPS, current, "255", "", expanded, showObis)
        EnergySection("Реактивная энергия", REACTIVE_GROUPS, current, "255", "", expanded, showObis)

        if (rdch.isNotEmpty()) {
            EnergySection("Расчётный день (РДЧ) · активная", ACTIVE_GROUPS, rdch, "101", "", expanded, showObis)
            EnergySection("Расчётный день (РДЧ) · реактивная", REACTIVE_GROUPS, rdch, "101", "", expanded, showObis)
        }

        if (readings.isEmpty()) {
            Text(
                "Нет данных — нажмите «Обновить»",
                color = p.textSecondary, fontSize = 16.sp, modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}

@Composable
private fun EnergySection(
    title: String,
    groups: List<GroupSpec>,
    readings: List<Reading>,
    period: String,
    titleSuffix: String,
    expanded: MutableMap<String, Boolean>,
    showObis: Boolean,
) {
    val present = groups.filter { spec -> readings.any { it.category == spec.category } }
    if (present.isEmpty()) return
    Box(Modifier.height(18.dp))
    SectionLabel(title)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        present.forEach { spec ->
            val items = readings.filter { it.category == spec.category }
            val key = "$period:${spec.category}"
            EnergyCard(
                spec = spec,
                items = items,
                period = period,
                titleSuffix = titleSuffix,
                primary = period == "255" && spec.category == EnergyCategory.ACTIVE_IMPORT,
                open = expanded[key] == true,
                onToggle = { expanded[key] = !(expanded[key] ?: false) },
                showObis = showObis,
            )
        }
    }
}

/**
 * Карточка группы энергии: цветная полоса слева, итог крупными цифрами, по нажатию — тарифы
 * строками таблицы. Главная группа (A+ текущих показаний) — самые крупные цифры.
 */
@Composable
private fun EnergyCard(
    spec: GroupSpec,
    items: List<Reading>,
    period: String,
    titleSuffix: String,
    primary: Boolean,
    open: Boolean,
    onToggle: () -> Unit,
    showObis: Boolean,
) {
    val p = RimTheme.palette
    // Только реально пришедшие тарифы (отсутствующие в буфере не показываем).
    val presentTariffs = items.mapNotNull { it.tariff }.distinct().sorted()
    val hasTariffs = presentTariffs.isNotEmpty()
    val totalText = formatNumber(total(items), 3)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(RimShapes.card)
            .background(p.cardSurface)
            .border(1.dp, p.border, RimShapes.card),
    ) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(spec.color))
        Column(Modifier.weight(1f)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = hasTariffs) { onToggle() }
                    .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(spec.title + titleSuffix, color = p.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text(unitOf(items, spec.unit), color = p.textSecondary, fontSize = 16.sp)
                    }
                    if (showObis) {
                        MonoText("${spec.obisBase}.0.$period", color = p.textFaint, fontSize = 13.sp, weight = FontWeight.Normal)
                    }
                    val base = if (primary) 42.sp else 30.sp
                    MonoText(
                        totalText,
                        color = p.textPrimary,
                        fontSize = fitSize(totalText, base, if (primary) 11 else 14),
                        weight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                if (hasTariffs) {
                    Text(
                        "▾", color = p.textSecondary, fontSize = 20.sp,
                        modifier = Modifier.padding(start = 10.dp).rotate(if (open) 180f else 0f),
                    )
                }
            }
            if (open && hasTariffs) {
                presentTariffs.forEach { t ->
                    val value = items.firstOrNull { it.tariff == t }?.valueKwh ?: 0.0
                    Divider()
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text(tariffLabel(t), color = p.textSecondary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                            if (showObis) {
                                MonoText("${spec.obisBase}.$t.$period", color = p.textFaint, fontSize = 12.sp, weight = FontWeight.Normal)
                            }
                        }
                        MonoText(formatNumber(value, 3), color = p.textPrimary, fontSize = 23.sp, weight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}
