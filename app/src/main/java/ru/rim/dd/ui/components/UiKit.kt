package ru.rim.dd.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.rim.dd.ui.theme.RimPrimary
import ru.rim.dd.ui.theme.RimPrimaryLight
import ru.rim.dd.ui.theme.RimTheme

/**
 * [Android-патч] Переиспользуемые элементы дизайна из макета Figma: «стеклянные» и «плотные»
 * карточки, заголовки секций, подписи OBIS, тумблер, заголовок экрана с кнопкой обновления.
 * Все берут цвета из RimTheme.palette, поэтому одинаково работают в тёмной и светлой теме.
 */

@Composable
fun GlassCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val p = RimTheme.palette
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(p.glassSurface)
            .border(1.dp, p.border, RoundedCornerShape(18.dp))
            .padding(18.dp),
    ) { content() }
}

@Composable
fun DataCard(modifier: Modifier = Modifier, noPad: Boolean = false, content: @Composable () -> Unit) {
    val p = RimTheme.palette
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(p.cardSurface)
            .border(1.dp, p.border, RoundedCornerShape(16.dp))
            .padding(if (noPad) 0.dp else 16.dp),
    ) { content() }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        color = RimPrimaryLight,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        modifier = modifier.padding(start = 2.dp, bottom = 8.dp, top = 4.dp),
    )
}

/** Подпись OBIS-кода мелким моно-шрифтом — показывается только когда включён тумблер OBIS. */
@Composable
fun ObisLabel(code: String, modifier: Modifier = Modifier) {
    Text(
        text = code,
        color = RimPrimaryLight.copy(alpha = 0.6f),
        fontSize = 10.sp,
        fontFamily = FontFamily.Monospace,
        modifier = modifier.padding(start = 2.dp, bottom = 6.dp),
    )
}

@Composable
fun Divider(modifier: Modifier = Modifier) {
    val p = RimTheme.palette
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .padding(vertical = 0.dp)
            .background(p.border),
    )
}

@Composable
fun ToggleRow(
    label: String,
    desc: String,
    value: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = RimTheme.palette
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = p.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(desc, color = p.textSecondary, fontSize = 12.sp)
        }
        Switchy(value, onChange)
    }
}

/** Тумблер в стиле макета: фиолетовый градиент во включённом состоянии. */
@Composable
fun Switchy(value: Boolean, onChange: (Boolean) -> Unit) {
    val p = RimTheme.palette
    val track = if (value) RimPrimary else if (p.dark) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.12f)
    Box(
        modifier = Modifier
            .size(width = 50.dp, height = 28.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(track)
            .clickable { onChange(!value) },
        contentAlignment = if (value) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .padding(3.dp)
                .size(22.dp)
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}

@Composable
fun ScreenHeader(title: String, onRefresh: () -> Unit, refreshing: Boolean) {
    val p = RimTheme.palette
    val angle = if (refreshing) {
        val t = rememberInfiniteTransition(label = "spin")
        t.animateFloat(
            initialValue = 0f, targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Restart),
            label = "angle",
        ).value
    } else 0f

    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, color = p.textPrimary, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(p.cardSurface)
                .border(1.dp, p.border, RoundedCornerShape(12.dp))
                .clickable { onRefresh() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Refresh,
                contentDescription = "Обновить",
                tint = p.textSecondary,
                modifier = Modifier.size(20.dp).rotate(angle),
            )
        }
    }
}

/** Число моноширинным шрифтом (напряжение, коды, время) — как var(--font-mono) в макете. */
@Composable
fun MonoText(
    text: String,
    color: Color,
    fontSize: androidx.compose.ui.unit.TextUnit,
    modifier: Modifier = Modifier,
    weight: FontWeight = FontWeight.Bold,
) {
    Text(text = text, color = color, fontSize = fontSize, fontWeight = weight, fontFamily = FontFamily.Monospace, modifier = modifier)
}

/** Чип-плашка статуса (реле, соединение, пломбы): цветная точка + текст. */
@Composable
fun StatusChip(text: String, color: Color, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

/** Внутренние отступы контента экрана под макет (сверху 20, по бокам 16, снизу — под навбар). */
val ScreenContentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 24.dp)
