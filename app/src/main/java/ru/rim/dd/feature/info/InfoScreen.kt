package ru.rim.dd.feature.info

import android.annotation.SuppressLint
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import ru.rim.dd.core.model.EnergyDirection
import ru.rim.dd.core.model.PhaseCondition
import ru.rim.dd.core.model.PhaseStatus
import ru.rim.dd.core.model.TamperCondition
import ru.rim.dd.ui.components.DataCard
import ru.rim.dd.ui.components.Divider
import ru.rim.dd.ui.components.MonoText
import ru.rim.dd.ui.components.RimShapes
import ru.rim.dd.ui.components.ScreenHeader
import ru.rim.dd.ui.components.SectionLabel
import ru.rim.dd.ui.components.StatusChip
import ru.rim.dd.ui.components.formatDate
import ru.rim.dd.ui.components.formatDateTime
import ru.rim.dd.ui.components.formatNumber
import ru.rim.dd.ui.components.formatTime
import ru.rim.dd.ui.components.tariffLabel
import ru.rim.dd.ui.theme.RimError
import ru.rim.dd.ui.theme.RimFonts
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
                Column(Modifier.weight(1f)) {
                    Text(info?.model ?: "—", color = p.textSecondary, fontSize = 18.sp, fontWeight = FontWeight.Bold, fontFamily = RimFonts.Raleway)
                    MonoText("№ ${info?.serialNumber ?: "—"}", color = p.textPrimary, fontSize = 28.sp, weight = FontWeight.SemiBold, modifier = Modifier.padding(top = 2.dp))
                    Box(Modifier.padding(top = 8.dp)) {
                        val sig = signal?.let { " · $it дБм" } ?: ""
                        StatusChip("Подключён$sig", RimSuccess)
                    }
                }
            }
        }

        // Параметры прибора показываются только когда прибор их прислал: нет значения — нет строки,
        // пришло в очередной цикл — строка появляется сама.
        val paramRows = buildList<@Composable () -> Unit> {
            if (inf == null) return@buildList
            inf.temperatureC?.let { add { InfoRow("Температура", "${formatNumber(it, 1)} °C", "0.0.96.9.0.255", showObis) } }
            inf.backupVoltageV?.let { add { InfoRow("Напряжение батарейки", "${formatNumber(it, 3)} В", "0.0.96.6.3.255", showObis) } }
            inf.deviceDate?.let { add { InfoRow("Дата прибора", formatDate(it), inf.deviceDateObis, showObis, mono = true) } }
            inf.deviceTime?.let { add { InfoRow("Время прибора", formatTime(it), inf.deviceTimeObis, showObis, mono = true) } }
            inf.rdchDateTime?.let { add { InfoRow("Расчётный день (РДЧ)", formatDateTime(it), "0.0.0.9.2.101", showObis, mono = true) } }
            inf.firmwareVersion.takeIf { it.isNotBlank() && it != "—" }?.let { add { InfoRow("Версия ПО", it, inf.firmwareVersionObis, showObis) } }
            inf.currentTariff?.let { add { InfoRow("Текущий тариф", tariffLabel(it), "0.0.96.14.0.255", showObis) } }
            inf.operatingTime?.let { t ->
                add {
                    val unit = inf.operatingTimeUnit?.let { u -> " $u" } ?: ""
                    InfoRow("Время работы", "${formatNumber(t, 0)}$unit", "0.0.96.8.0.255", showObis, mono = true)
                }
            }
            inf.hdlcP1?.let { add { InfoRow("HDLC P1", it, "0.0.22.0.0.255", showObis, mono = true) } }
            inf.hdlcP2?.let { add { InfoRow("HDLC P2", it, "0.1.22.0.0.255", showObis, mono = true) } }
            inf.hdlcP3?.let { add { InfoRow("HDLC P3", it, "0.2.22.0.0.255", showObis, mono = true) } }
            inf.hdlcP4?.let { add { InfoRow("HDLC P4", it, "0.3.22.0.0.255", showObis, mono = true) } }
        }
        Box(Modifier.padding(top = 4.dp).animateContentSize()) {
            Column {
                if (paramRows.isNotEmpty()) {
                    SectionLabel("Параметры прибора")
                    DataCard {
                        paramRows.forEachIndexed { index, row ->
                            if (index > 0) RowDivider()
                            row()
                        }
                    }
                }
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
                            Text(it, color = RimError, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
                        }
                        RowDivider()
                        ChipRow("Чередование фаз", "1.0.96.50.0.255", showObis) {
                            if (grid.sequenceError) StatusChip("Нарушено", RimError) else StatusChip("Норма", RimSuccess)
                        }
                    }
                }
                if (dir != null) {
                    if (grid != null) RowDivider()
                    Row(Modifier.fillMaxWidth()) {
                        LabelWithObis("Квадранты энергии", "0.0.96.50.170.255", showObis)
                    }
                    Box(Modifier.padding(top = 8.dp))
                    EnergyQuadrants(dir)
                }
            }
        }

        // Разделы появляются, когда прибор прислал статус пломб и датчиков («Значки ДД»).
        // Датчики разнесены по смыслу: пломбы (вскрытие), датчики воздействия, состояние прибора,
        // качество сети. Набор зависит от версии ЖКИ: у версии 0 только магнит и ПКЭ, у версии ≥ 1 — полный.
        if (!tamper.unknown) {
            val full = tamper.lcdVersion != 0
            if (full) {
                SensorGroup("Пломбы") {
                    SensorRow("Пломба корпуса", tamper.cover, false)
                    SensorRow("Пломба клеммника", tamper.meter, false)
                }
            }
            SensorGroup("Датчики воздействия") {
                SensorRow("Магнитное поле", tamper.magnet, false)
                if (full) SensorRow("ВЧ поле", tamper.rf, false)
            }
            if (full) {
                SensorGroup("Состояние прибора") {
                    SensorRow("Батарея", tamper.battery, false)
                    SensorRow("Самодиагностика", tamper.selfDiagnosticsFault, false)
                    if (tamper.powerLimitExceeded) SensorRow("Превышен лимит мощности", TamperCondition.ACTIVE, false)
                }
            }
            SensorGroup("Качество сети") {
                SensorRow("ПКЭ", tamper.eqi, false)
                if (full) SensorRow("Небаланс", tamper.unbalance, false)
            }
        }
    }
}

/** Смысловая группа датчиков: заголовок и строки карточками. */
@Composable
private fun SensorGroup(title: String, content: @Composable () -> Unit) {
    Box(Modifier.padding(top = 4.dp))
    SectionLabel(title)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

@Composable
private fun ChipRow(label: String, obis: String?, showObis: Boolean, chips: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        LabelWithObis(label, obis, showObis)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) { chips() }
    }
}

/**
 * Квадранты энергии — символ с ЖКИ прибора (ДД): четыре стрелки, P — активная, Q — реактивная
 * энергия. Каждый сегмент включает букву, знак и стрелку целиком:
 * Q+ — вверх, P+ — вправо, P− — влево, Q− — вниз. Включённый сегмент залит
 * фирменным синим, выключенный — тонкий контур. Биты берутся из «Значков ДД» (байт 3, EnergyDirection).
 * Чертёж рисуется в условных единицах 720×480 и масштабируется по ширине экрана.
 */
@SuppressLint("UnusedBoxWithConstraintsScope")
@Composable
private fun EnergyQuadrants(dir: EnergyDirection) {
    val p = RimTheme.palette
    val density = LocalDensity.current
    val on = p.accent
    val off = p.textPrimary.copy(alpha = 0.30f)
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val s = maxWidth.value / 720f // dp на единицу чертежа
            fun fs(units: Float) = with(density) { (units * s).dp.toSp() }
            Box(Modifier.fillMaxWidth().height((480f * s).dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    val k = size.width / 720f
                    val cx = 360f * k
                    val cy = 240f * k
                    fun arrow(direction: Int, active: Boolean) {
                        val path = arrowPath(cx, cy, k, direction)
                        val color = if (active) on else off
                        if (active) drawPath(path, color, style = Fill)
                        drawPath(path, color, style = Stroke(width = 7f * k, join = StrokeJoin.Miter))
                    }
                    fun sign(x: Float, y: Float, plus: Boolean, active: Boolean) {
                        val path = signPath(x * k, y * k, k, plus)
                        val color = if (active) on else off
                        if (active) drawPath(path, color, style = Fill)
                        drawPath(path, color, style = Stroke(width = 4f * k, join = StrokeJoin.Miter))
                    }
                    arrow(0, dir.reactivePlus)   // Q+
                    arrow(1, dir.activePlus)     // P+
                    arrow(3, dir.activeMinus)    // P−
                    arrow(2, dir.reactiveMinus)  // Q−
                    sign(275f, 108f, plus = true, active = dir.reactivePlus)
                    sign(615f, 105f, plus = true, active = dir.activePlus)
                    sign(235f, 428f, plus = false, active = dir.activeMinus)
                    sign(620f, 430f, plus = false, active = dir.reactiveMinus)
                }
                QuadrantGlyph("Q", 135f, 112f, s, ::fs, if (dir.reactivePlus) on else off)
                QuadrantGlyph("P", 500f, 105f, s, ::fs, if (dir.activePlus) on else off)
                QuadrantGlyph("P", 125f, 410f, s, ::fs, if (dir.activeMinus) on else off)
                QuadrantGlyph("Q", 510f, 410f, s, ::fs, if (dir.reactiveMinus) on else off)
            }
        }
        Text(
            energyQuadrantCaption(dir),
            color = p.textPrimary,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}

/** Крупная буква P или Q (центр в точке чертежа cx, cy). */
@Composable
private fun QuadrantGlyph(text: String, cx: Float, cy: Float, s: Float, fs: (Float) -> TextUnit, color: Color) {
    Box(
        Modifier.offset(x = ((cx - 90f) * s).dp, y = ((cy - 90f) * s).dp).size((180f * s).dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = color, fontSize = fs(150f), fontWeight = FontWeight.Medium, fontFamily = RimFonts.Plex, softWrap = false)
    }
}

/**
 * Контур стрелки: 0 — вверх, 1 — вправо, 2 — вниз, 3 — влево. Размеры в единицах чертежа
 * относительно центра: остриё в 222, голова 100×50, стержень шириной 44, зазор у центра 33.
 */
private fun arrowPath(cx: Float, cy: Float, k: Float, direction: Int): Path {
    val up = listOf(
        0f to -222f, -50f to -172f, -22f to -172f, -22f to -33f,
        22f to -33f, 22f to -172f, 50f to -172f,
    )
    val path = Path()
    up.forEachIndexed { i, (x, y) ->
        val (rx, ry) = when (direction) {
            1 -> -y to x
            2 -> -x to -y
            3 -> y to -x
            else -> x to y
        }
        val px = cx + rx * k
        val py = cy + ry * k
        if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
    }
    path.close()
    return path
}

/** Контур знака «+» или «−» с центром в (cx, cy) пикселей. */
private fun signPath(cx: Float, cy: Float, k: Float, plus: Boolean): Path {
    val h = 40f * k   // половина длины
    val a = 10f * k   // половина толщины
    val pts = if (plus) {
        listOf(-a to -h, a to -h, a to -a, h to -a, h to a, a to a, a to h, -a to h, -a to a, -h to a, -h to -a, -a to -a)
    } else {
        listOf(-h to -a, h to -a, h to a, -h to a)
    }
    val path = Path()
    pts.forEachIndexed { i, (x, y) -> if (i == 0) path.moveTo(cx + x, cy + y) else path.lineTo(cx + x, cy + y) }
    path.close()
    return path
}

/** Подпись под символом: знаки включённых сегментов и, если однозначно, номер квадранта. */
private fun energyQuadrantCaption(dir: EnergyDirection): String {
    if (dir.none) return "Нет"
    val signs = listOfNotNull(
        "P+".takeIf { dir.activePlus }, "P−".takeIf { dir.activeMinus },
        "Q+".takeIf { dir.reactivePlus }, "Q−".takeIf { dir.reactiveMinus },
    ).joinToString(" ")
    val quadrant = when {
        dir.activePlus && dir.reactivePlus && !dir.activeMinus && !dir.reactiveMinus -> "I"
        dir.activeMinus && dir.reactivePlus && !dir.activePlus && !dir.reactiveMinus -> "II"
        dir.activeMinus && dir.reactiveMinus && !dir.activePlus && !dir.reactivePlus -> "III"
        dir.activePlus && dir.reactiveMinus && !dir.activeMinus && !dir.reactivePlus -> "IV"
        else -> null
    }
    return if (quadrant != null) "Квадрант $quadrant · $signs" else signs
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
        Text(label, color = RimTheme.palette.textSecondary, fontSize = 16.sp)
        if (showObis && obis != null) MonoText(obis, color = RimTheme.palette.textFaint, fontSize = 12.sp, weight = FontWeight.Normal, modifier = Modifier.padding(top = 1.dp))
    }
}

@Composable
private fun InfoRow(label: String, value: String, obis: String?, showObis: Boolean, mono: Boolean = false) {
    val p = RimTheme.palette
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
        LabelWithObis(label, obis, showObis)
        if (mono) {
            MonoText(value, color = p.textPrimary, fontSize = 19.sp, weight = FontWeight.SemiBold)
        } else {
            Text(value, color = p.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
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
            .clip(RimShapes.card)
            .background(p.cardSurface)
            .border(1.dp, p.border, RimShapes.card)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (condition == TamperCondition.ACTIVE && !unknown) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
            }
            Text(label, color = p.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
        StatusChip(text, color)
    }
}
