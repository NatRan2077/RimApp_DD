package ru.rim.dd.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * [Android-патч] Палитра из макета Figma (перенос дизайна в приложение). Акцентные цвета общие
 * для тёмной и светлой тем, фон/поверхности/текст — свои у каждой (см. RimPalette / RimTheme).
 */

// ---- Акценты (одинаковые в обеих темах) ----
val RimPrimary = Color(0xFF7C4FF0)
val RimPrimaryDeep = Color(0xFF6D3EE8)
val RimPrimaryLight = Color(0xFF9D6FFF)
val RimViolet = Color(0xFF8B5CF6)
val RimLilac = Color(0xFFA78BFA)

// Градиенты
val RimHeroGradient = listOf(Color(0xFF5B2FCC), Color(0xFF8B5CF6), Color(0xFFC084FC))
val RimButtonGradient = listOf(Color(0xFF6D3EE8), Color(0xFF9D6FFF))
val RimIconGradient = listOf(Color(0xFF5B2FCC), Color(0xFF9D6FFF))

// Статусы
val RimSuccess = Color(0xFF4ADE80)
val RimWarn = Color(0xFFFBBF24)
val RimError = Color(0xFFF87171)

// Цвета групп энергии на экране «Показания» (из макета Figma)
val RimGroupActiveImport = Color(0xFF7C4FF0)
val RimGroupActiveExport = Color(0xFF06B6D4)
val RimGroupReactiveQ1 = Color(0xFFF59E0B)
val RimGroupReactiveQ4 = Color(0xFF10B981)

// ---- Тёмная тема ----
val DarkBg = Color(0xFF0A0914)
val DarkNavBg = Color(0xFF100D1C)
val DarkTextPrimary = Color(0xFFF0EEF8)

// ---- Светлая тема ----
val LightBg = Color(0xFFF0EEF9)
val LightTextPrimary = Color(0xFF1A1730)
