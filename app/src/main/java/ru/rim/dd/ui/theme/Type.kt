package ru.rim.dd.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import ru.rim.dd.R

/**
 * Фирменные шрифты по брендбуку: Raleway — заголовки, IBM Plex Sans — текст и цифры
 * (для цифровых платформ). Файлы лежат в res/font.
 */
object RimFonts {
    val Raleway = FontFamily(
        Font(R.font.raleway_semibold, FontWeight.SemiBold),
        Font(R.font.raleway_bold, FontWeight.Bold),
        Font(R.font.raleway_extrabold, FontWeight.ExtraBold),
    )
    val Plex = FontFamily(
        Font(R.font.ibm_plex_sans_regular, FontWeight.Normal),
        Font(R.font.ibm_plex_sans_medium, FontWeight.Medium),
        Font(R.font.ibm_plex_sans_semibold, FontWeight.SemiBold),
        Font(R.font.ibm_plex_sans_bold, FontWeight.Bold),
        // ExtraBold у Plex нет — берётся Bold, чтобы не было синтетического утолщения.
        Font(R.font.ibm_plex_sans_bold, FontWeight.ExtraBold),
    )

    /** Цифры одинаковой ширины (tnum) и «прописные» (lnum): столбцы показаний ровно выравниваются. */
    const val DIGITS = "tnum, lnum"
}

internal fun rimTypography(): Typography {
    val head = RimFonts.Raleway
    val body = RimFonts.Plex
    val d = RimFonts.DIGITS
    return Typography(
        headlineLarge = TextStyle(fontFamily = head, fontWeight = FontWeight.ExtraBold, fontSize = 30.sp),
        headlineMedium = TextStyle(fontFamily = head, fontWeight = FontWeight.ExtraBold, fontSize = 28.sp),
        headlineSmall = TextStyle(fontFamily = head, fontWeight = FontWeight.Bold, fontSize = 24.sp),
        titleLarge = TextStyle(fontFamily = head, fontWeight = FontWeight.Bold, fontSize = 21.sp),
        titleMedium = TextStyle(fontFamily = body, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, fontFeatureSettings = d),
        // bodyLarge — стиль по умолчанию для всех Text без явного шрифта: IBM Plex Sans с ровными цифрами.
        bodyLarge = TextStyle(fontFamily = body, fontWeight = FontWeight.Normal, fontSize = 17.sp, fontFeatureSettings = d),
        bodyMedium = TextStyle(fontFamily = body, fontWeight = FontWeight.Normal, fontSize = 15.sp, fontFeatureSettings = d),
        bodySmall = TextStyle(fontFamily = body, fontWeight = FontWeight.Normal, fontSize = 13.sp, fontFeatureSettings = d),
        labelLarge = TextStyle(fontFamily = body, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, fontFeatureSettings = d),
        labelSmall = TextStyle(fontFamily = body, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, fontFeatureSettings = d),
    )
}
