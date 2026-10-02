package ru.rim.dd.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Цвета, которых нет в ColorScheme Material3; раздаются через LocalRimPalette. */
data class RimPalette(
    val dark: Boolean,
    val background: Color,
    val navBackground: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textFaint: Color,
    /** Поверхность «плотной» карточки (DataCard). */
    val cardSurface: Color,
    /** Поверхность «стеклянной» карточки (GlassCard). */
    val glassSurface: Color,
    val border: Color,
)

private val DarkPalette = RimPalette(
    dark = true,
    background = DarkBg,
    navBackground = DarkNavBg,
    textPrimary = DarkTextPrimary,
    textSecondary = DarkTextPrimary.copy(alpha = 0.45f),
    textFaint = DarkTextPrimary.copy(alpha = 0.30f),
    cardSurface = Color.White.copy(alpha = 0.05f),
    glassSurface = Color.White.copy(alpha = 0.05f),
    border = Color.White.copy(alpha = 0.08f),
)

private val LightPalette = RimPalette(
    dark = false,
    background = LightBg,
    navBackground = Color.White.copy(alpha = 0.95f),
    textPrimary = LightTextPrimary,
    textSecondary = LightTextPrimary.copy(alpha = 0.45f),
    textFaint = LightTextPrimary.copy(alpha = 0.30f),
    cardSurface = Color.White,
    glassSurface = Color.White.copy(alpha = 0.85f),
    border = Color.Black.copy(alpha = 0.06f),
)

val LocalRimPalette = staticCompositionLocalOf { DarkPalette }

/** Быстрый доступ к палитре: RimTheme.palette внутри @Composable. */
object RimTheme {
    val palette: RimPalette
        @Composable get() = LocalRimPalette.current
}

private val DarkColors = darkColorScheme(
    primary = RimPrimary,
    onPrimary = Color.White,
    background = DarkBg,
    onBackground = DarkTextPrimary,
    surface = DarkBg,
    onSurface = DarkTextPrimary,
    onSurfaceVariant = DarkTextPrimary.copy(alpha = 0.45f),
    error = RimError,
)

private val LightColors = lightColorScheme(
    primary = RimPrimary,
    onPrimary = Color.White,
    background = LightBg,
    onBackground = LightTextPrimary,
    surface = LightBg,
    onSurface = LightTextPrimary,
    onSurfaceVariant = LightTextPrimary.copy(alpha = 0.45f),
    error = RimError,
)

@Composable
fun RimTheme(dark: Boolean, content: @Composable () -> Unit) {
    val palette = if (dark) DarkPalette else LightPalette
    CompositionLocalProvider(LocalRimPalette provides palette) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            typography = rimTypography(),
            content = content,
        )
    }
}
