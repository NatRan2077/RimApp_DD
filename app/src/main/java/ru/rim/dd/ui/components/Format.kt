package ru.rim.dd.ui.components

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Русский формат числа: запятая — десятичный разделитель, пробел — разделитель тысяч. */
fun formatNumber(value: Double, decimals: Int): String {
    // Форматируем в Locale.US и подменяем разделители вручную (обычный пробел, а не неразрывный).
    val s = String.format(Locale.US, "%,.${decimals}f", value)
    return s.replace(",", "\u0000").replace(".", ",").replace("\u0000", " ")
}

private val CLOCK_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy")
private val CLOCK_TIME = DateTimeFormatter.ofPattern("HH:mm:ss")

fun formatDate(dt: LocalDateTime?): String = dt?.format(CLOCK_DATE) ?: "—"
fun formatTime(dt: LocalDateTime?): String = dt?.format(CLOCK_TIME) ?: "—"
fun formatDateTime(dt: LocalDateTime?): String = dt?.let { "${it.format(CLOCK_DATE)} ${it.format(CLOCK_TIME)}" } ?: "—"

fun tariffLabel(tariff: Int?): String = tariff?.let { "Т$it" } ?: "—"
