package ru.rim.dd.ui.components

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * [Android-патч] Форматирование чисел «как в макете»: десятичная запятая и пробел-разделитель
 * тысяч (русский формат), фиксированное число знаков после запятой.
 */
fun formatNumber(value: Double, decimals: Int): String {
    // Локаль с запятой-разделителем дробной части и неразрывным пробелом тысяч — но макет
    // использует обычный пробел, поэтому нормализуем к нему.
    val s = String.format(Locale.US, "%,.${decimals}f", value)
    return s.replace(",", "\u0000").replace(".", ",").replace("\u0000", " ")
}

private val CLOCK_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy")
private val CLOCK_TIME = DateTimeFormatter.ofPattern("HH:mm:ss")

fun formatDate(dt: LocalDateTime?): String = dt?.format(CLOCK_DATE) ?: "—"
fun formatTime(dt: LocalDateTime?): String = dt?.format(CLOCK_TIME) ?: "—"
fun formatDateTime(dt: LocalDateTime?): String = dt?.let { "${it.format(CLOCK_DATE)} ${it.format(CLOCK_TIME)}" } ?: "—"

fun tariffLabel(tariff: Int?): String = tariff?.let { "Т$it" } ?: "—"
