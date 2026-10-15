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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.rim.dd.ui.theme.RimPrimary
import ru.rim.dd.ui.theme.RimFonts
import ru.rim.dd.ui.theme.RimTheme

@Composable
fun GlassCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val p = RimTheme.palette
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RimShapes.card)
            .background(p.glassSurface)
            .border(1.dp, p.border, RimShapes.card)
            .padding(16.dp),
    ) { content() }
}

@Composable
fun DataCard(modifier: Modifier = Modifier, noPad: Boolean = false, content: @Composable () -> Unit) {
    val p = RimTheme.palette
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RimShapes.card)
            .background(p.cardSurface)
            .border(1.dp, p.border, RimShapes.card)
            .padding(if (noPad) 0.dp else 16.dp),
    ) { content() }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        color = RimTheme.palette.accentText,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = RimFonts.Raleway,
        letterSpacing = 1.2.sp,
        modifier = modifier.padding(start = 2.dp, bottom = 8.dp, top = 4.dp),
    )
}

@Composable
fun Divider(modifier: Modifier = Modifier) {
    val p = RimTheme.palette
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
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
            Text(label, color = p.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(desc, color = p.textSecondary, fontSize = 14.sp)
        }
        Switchy(value, onChange)
    }
}

/** Тумблер: акцентный цвет трека во включённом состоянии. */
@Composable
fun Switchy(value: Boolean, onChange: (Boolean) -> Unit) {
    val p = RimTheme.palette
    val track = if (value) RimPrimary else if (p.dark) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.12f)
    Box(
        modifier = Modifier
            .size(width = 50.dp, height = 28.dp)
            .clip(RimShapes.control)
            .background(track)
            .clickable { onChange(!value) },
        contentAlignment = if (value) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .padding(3.dp)
                .size(22.dp)
                .clip(RimShapes.chip)
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
        Text(title, color = p.textPrimary, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, fontFamily = RimFonts.Raleway)
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RimShapes.control)
                .background(p.cardSurface)
                .border(1.dp, p.border, RimShapes.control)
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

/** Число или код: IBM Plex Sans с цифрами одинаковой ширины (напряжение, OBIS, время). */
@Composable
fun MonoText(
    text: String,
    color: Color,
    fontSize: TextUnit,
    modifier: Modifier = Modifier,
    weight: FontWeight = FontWeight.Bold,
) {
    Text(
        text = text, color = color, fontSize = fontSize, fontWeight = weight,
        fontFamily = RimFonts.Plex, style = LocalTextStyle.current.copy(fontFeatureSettings = RimFonts.DIGITS),
        modifier = modifier,
    )
}

/** Плашка статуса (реле, соединение, пломбы): сплошная заливка цветом статуса, белый текст. */
@Composable
fun StatusChip(text: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RimShapes.chip)
            .background(color)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(text, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * Формы по брендбуку: строгий стиль, минимум скруглений. card — карточки и кнопки,
 * control — небольшие элементы управления, chip — плашки статусов и чипы.
 */
object RimShapes {
    val card = RoundedCornerShape(4.dp)
    val control = RoundedCornerShape(4.dp)
    val chip = RoundedCornerShape(2.dp)
}
