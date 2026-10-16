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
    /** Поверхность второй карточки (GlassCard) — в строгом стиле та же, что и DataCard. */
    val glassSurface: Color,
    val border: Color,
    /** Фирменный синий для заливок (кнопки, плашки). */
    val accent: Color,
    /** Акцент для текста: в тёмной теме — голубой, иначе синий плохо читается на тёмном фоне. */
    val accentText: Color,
    /** Подложка полей ввода. */
    val fieldSurface: Color,
)

private val DarkPalette = RimPalette(
    dark = true,
    background = DarkBg,
    navBackground = DarkNavBg,
    textPrimary = DarkTextPrimary,
    textSecondary = Color(0xFFA9A9A6),
    textFaint = Color(0xFF7A7A78),
    cardSurface = BrandInk,
    glassSurface = BrandInk,
    border = Color(0xFF40403E),
    accent = BrandBlue,
    accentText = BrandSky,
    fieldSurface = Color(0xFF333332),
)

private val LightPalette = RimPalette(
    dark = false,
    background = LightBg,
    navBackground = LightBg,
    textPrimary = LightTextPrimary,
    textSecondary = Color(0xFF6E6E6C),
    textFaint = Color(0xFF9A9A98),
    cardSurface = LightBg,
    glassSurface = LightBg,
    border = BrandGray,
    accent = BrandBlue,
    accentText = BrandBlue,
    fieldSurface = Color(0xFFF5F8FA),
)

val LocalRimPalette = staticCompositionLocalOf { DarkPalette }

/** Быстрый доступ к палитре: RimTheme.palette внутри @Composable. */
object RimTheme {
    val palette: RimPalette
        @Composable get() = LocalRimPalette.current
}

private val DarkColors = darkColorScheme(
    primary = BrandBlue,
    onPrimary = Color.White,
    background = DarkBg,
    onBackground = DarkTextPrimary,
    surface = DarkBg,
    onSurface = DarkTextPrimary,
    onSurfaceVariant = Color(0xFFA9A9A6),
    error = RimError,
)

private val LightColors = lightColorScheme(
    primary = BrandBlue,
    onPrimary = Color.White,
    background = LightBg,
    onBackground = LightTextPrimary,
    surface = LightBg,
    onSurface = LightTextPrimary,
    onSurfaceVariant = Color(0xFF6E6E6C),
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
