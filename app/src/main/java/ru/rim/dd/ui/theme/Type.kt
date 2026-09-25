package ru.rim.dd.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * [Android-патч] Типографика под макет: обычный (sans) текст для подписей и МОНОШИРИННЫЙ для
 * чисел/кодов (напряжение, серийники, OBIS, время). Отдельного шрифтового ресурса не добавляем,
 * чтобы не тянуть файлы — используем системные FontFamily.Default / FontFamily.Monospace, что
 * повторяет var(--font-sans)/var(--font-mono) из Figma-экспорта.
 */

/** Моноширинный стиль для чисел и кодов — применяется точечно на экранах. */
val MonoNumber = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)

internal fun rimTypography(): Typography {
    val sans = FontFamily.Default
    return Typography(
        headlineLarge = TextStyle(fontFamily = sans, fontWeight = FontWeight.ExtraBold, fontSize = 30.sp),
        headlineMedium = TextStyle(fontFamily = sans, fontWeight = FontWeight.ExtraBold, fontSize = 26.sp),
        headlineSmall = TextStyle(fontFamily = sans, fontWeight = FontWeight.Bold, fontSize = 22.sp),
        titleMedium = TextStyle(fontFamily = sans, fontWeight = FontWeight.Bold, fontSize = 15.sp),
        bodyLarge = TextStyle(fontFamily = sans, fontWeight = FontWeight.Normal, fontSize = 15.sp),
        bodyMedium = TextStyle(fontFamily = sans, fontWeight = FontWeight.Normal, fontSize = 13.sp),
        bodySmall = TextStyle(fontFamily = sans, fontWeight = FontWeight.Normal, fontSize = 11.sp),
        labelLarge = TextStyle(fontFamily = sans, fontWeight = FontWeight.Bold, fontSize = 14.sp),
        labelSmall = TextStyle(fontFamily = sans, fontWeight = FontWeight.Bold, fontSize = 11.sp),
    )
}
