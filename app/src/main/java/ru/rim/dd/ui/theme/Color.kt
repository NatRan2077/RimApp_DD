package ru.rim.dd.ui.theme

import androidx.compose.ui.graphics.Color

// ---- Фирменные цвета АО «Радио и микроэлектроника» (брендбук) ----
val BrandBlue = Color(0xFF1282C5)       // синий: основной акцент
val BrandSky = Color(0xFF77B8DD)        // голубой
val BrandLightBlue = Color(0xFFB2E1F6)  // светло-голубой
val BrandPale = Color(0xFFD4E5F0)       // бледно-голубой
val BrandGray = Color(0xFFE1E1E1)       // серый: рамки, разделители
val BrandInk = Color(0xFF2B2B2A)        // тёмный: текст, фон тёмной темы

// ---- Акценты (старые имена оставлены, чтобы не трогать каждый экран) ----
val RimPrimary = BrandBlue
val RimPrimaryDeep = BrandBlue
val RimPrimaryLight = BrandSky
val RimViolet = BrandBlue
val RimLilac = BrandBlue

// «Градиенты» по брендбуку — сплошной цвет: строгий плоский стиль без переливов.
val RimHeroGradient = listOf(BrandBlue, BrandBlue)
val RimButtonGradient = listOf(BrandBlue, BrandBlue)
val RimIconGradient = listOf(BrandBlue, BrandBlue)

// Статусы — приглушённые, без «неона»
val RimSuccess = Color(0xFF2E9E5B)
val RimWarn = Color(0xFFD99A00)
val RimError = Color(0xFFD64545)

// Группы энергии на экране «Показания»: оттенки фирменного синего
val RimGroupActiveImport = BrandBlue
val RimGroupActiveExport = Color(0xFF0B5F92)
val RimGroupReactiveQ1 = BrandSky
val RimGroupReactiveQ4 = Color(0xFF7F97A8)

// ---- Тёмная тема ----
val DarkBg = Color(0xFF1C1C1B)
val DarkNavBg = Color(0xFF232322)
val DarkTextPrimary = Color(0xFFFFFFFF)

// ---- Светлая тема ----
val LightBg = Color(0xFFFFFFFF)
val LightTextPrimary = BrandInk
