package ru.rim.dd.ui.components

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Русский формат числа: запятая — десятичный разделитель, пробел — разделитель тысяч. */
fun formatNumber(value: Double, decimals: Int): String {
    // Форматируем в Locale.US и подменяем разделители вручную (обычный пробел, а не неразрывный).
    val s = String.format(Locale.US, "%,.${decimals}f", value)
    return s.replace(",", "\u0000").replace(".", ",").replace("\u0000", " ")
}
/** Формат вывода времени работы прибора день часы минуты секунды.  */
fun formatUptime(totalSeconds: Long): String {
    val d = totalSeconds / 86_400
    val h = (totalSeconds % 86_400) / 3_600
    val m = (totalSeconds % 3_600) / 60
    val s = totalSeconds % 60
    return "%02dд : %02dч : %02dм : %02dс".format(d, h, m, s)
}

private val CLOCK_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy")
private val CLOCK_TIME = DateTimeFormatter.ofPattern("HH:mm:ss")

fun formatDate(dt: LocalDateTime?): String = dt?.format(CLOCK_DATE) ?: "—"
fun formatTime(dt: LocalDateTime?): String = dt?.format(CLOCK_TIME) ?: "—"
fun formatDate(d: LocalDate?): String = d?.format(CLOCK_DATE) ?: "—"
fun formatTime(t: LocalTime?): String = t?.format(CLOCK_TIME) ?: "—"
fun formatDateTime(dt: LocalDateTime?): String = dt?.let { "${it.format(CLOCK_DATE)} ${it.format(CLOCK_TIME)}" } ?: "—"

fun tariffLabel(tariff: Int?): String = tariff?.let { "Т$it" } ?: "—"
