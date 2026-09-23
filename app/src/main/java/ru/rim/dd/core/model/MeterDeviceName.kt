package ru.rim.dd.core.model

/** Разобранное BLE-имя прибора: "RIM1894005000112" → модель "189.40", серийный "05000112". */
data class MeterNameInfo(
    val model: String,
    val serialNumber: String,
)
object MeterDeviceName {

    /** Длина серийного номера в имени — 8 цифр, как и в прошивке пульта (numMeterStr). */
    const val SERIAL_DIGITS = 8

    /** Первые 5 цифр имени — модель (например, "18940" → "189.40"). */
    private const val MODEL_DIGITS = 5

    private const val TOTAL_DIGITS = MODEL_DIGITS + SERIAL_DIGITS

    fun parse(name: String?): MeterNameInfo? {
        val raw = name?.trim() ?: return null
        val digits = raw.filter { it.isDigit() }
        return when (digits.length) {
            TOTAL_DIGITS -> {
                val modelDigits = digits.substring(0, MODEL_DIGITS)
                val serial = digits.substring(MODEL_DIGITS)
                MeterNameInfo(
                    model = "${modelDigits.substring(0, 3)}.${modelDigits.substring(3)}",
                    serialNumber = serial,
                )
            }
            SERIAL_DIGITS -> {
                // Модель — буквенная часть имени до первой цифры ("AKROS-07200090" → "AKROS").
                // Если букв нет вообще (имя из одних цифр), модели мы не знаем — ставим прочерк,
                // настоящее название всё равно приходит потом из буфера (объект «Тип прибора»).
                val letters = raw.takeWhile { !it.isDigit() }.trim { it == '-' || it == '_' || it.isWhitespace() }
                MeterNameInfo(
                    model = letters.ifEmpty { "—" },
                    serialNumber = digits,
                )
            }
            else -> null
        }
    }
    fun normalizeSerial(input: String?): String? {
        val digits = input?.filter { it.isDigit() } ?: return null
        return when {
            digits.isEmpty() -> null
            digits.length == TOTAL_DIGITS -> digits.substring(MODEL_DIGITS)
            digits.length <= SERIAL_DIGITS -> digits.padStart(SERIAL_DIGITS, '0')
            else -> null
        }
    }
    fun matches(name: String?, requestedSerial: String?): Boolean {
        val wanted = normalizeSerial(requestedSerial) ?: return false
        val actual = parse(name)?.serialNumber ?: return false
        return actual == wanted
    }

    fun serialsEqual(first: String?, second: String?): Boolean {
        val a = normalizeSerial(first) ?: return false
        val b = normalizeSerial(second) ?: return false
        return a == b
    }
    val KNOWN_NAME_PREFIXES: List<String> = listOf("RIM", "AKROS")
    fun looksLikeMeter(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        if (parse(name) != null) return true
        return KNOWN_NAME_PREFIXES.any { name.startsWith(it, ignoreCase = true) }
    }
}
