package ru.rim.dd.core.model

/** Разобранное BLE-имя прибора: "RIM1894005000112" → модель "189.40", серийный "05000112". */
data class MeterNameInfo(
    val model: String,
    val serialNumber: String,
)

/**
 * [Android-патч] Разбор BLE-имени прибора и сопоставление его с номером ПУ, который ввёл
 * пользователь.
 *
 * ЗАЧЕМ ОТДЕЛЬНЫМ ОБЪЕКТОМ: раньше разбор имени жил приватным методом внутри
 * MeterRepositoryImpl, а поиск устройства по номеру ПУ вообще НЕ пользовался номером (см.
 * MeterBleClient.connectBySerialNumber) — подключались к первому попавшемуся прибору с именем
 * на "RIM". Теперь одна и та же логика нужна в двух разных местах — при фильтрации эфира и при
 * проверке уже подключённого прибора — и если бы они разошлись хоть на символ, вернулась бы
 * ровно та же беда: «ввёл один номер, подключился к другому счётчику».
 *
 * ФОРМАТ ИМЕНИ подтверждён на реальных приборах ("RIM1894604314652", "RIM1894005000112"):
 * 13 цифр после буквенного префикса, из них первые 5 — модель ("18940" → "189.40", точка после
 * третьей цифры), оставшиеся 8 — серийный номер. Ровно 8 цифр серийного использует и сама
 * прошивка пульта РиМ 040.40: она фильтрует эфир по строке номера счётчика длиной 8 символов
 * (SystemInfo.numMeterStr, дополняется ведущими нулями) — см. app_ble.c/thread_BLE.
 */
object MeterDeviceName {

    /** Длина серийного номера в имени — 8 цифр, как и в прошивке пульта (numMeterStr). */
    const val SERIAL_DIGITS = 8

    /** Первые 5 цифр имени — модель (например, "18940" → "189.40"). */
    private const val MODEL_DIGITS = 5

    private const val TOTAL_DIGITS = MODEL_DIGITS + SERIAL_DIGITS

    /**
     * Разбирает рекламируемое имя. Терпим к пробелам/дефисам (просто выбираются все цифры), но
     * при ЛЮБОМ отклонении от [TOTAL_DIGITS] цифр возвращает null: лучше честно не знать модель
     * и серийный, чем догадаться их из имени нестандартного формата — а в контексте поиска по
     * номеру ПУ «не разобралось» обязано означать «это не наш прибор», а не «сойдёт».
     */
    fun parse(name: String?): MeterNameInfo? {
        val digits = name?.filter { it.isDigit() } ?: return null
        if (digits.length != TOTAL_DIGITS) return null
        val modelDigits = digits.substring(0, MODEL_DIGITS)
        val serial = digits.substring(MODEL_DIGITS)
        val model = "${modelDigits.substring(0, 3)}.${modelDigits.substring(3)}"
        return MeterNameInfo(model = model, serialNumber = serial)
    }

    /**
     * Приводит введённый пользователем номер ПУ к каноническому виду (8 цифр с ведущими нулями)
     * или возвращает null, если ввод не похож на номер.
     *
     * Допускается: сам серийный номер (в том числе без ведущих нулей — "5000112" → "05000112",
     * потому что на корпусе и в документах номер обычно пишут без них) и полное 13-значное
     * число из имени устройства (модель + серийный) — его удобно скопировать целиком.
     *
     * Сознательно НЕ допускается частичное совпадение/поиск по подстроке: сравнение всегда
     * идёт по полному нормализованному номеру, иначе "652" подошло бы сразу нескольким
     * приборам — а это ровно та ошибка, от которой здесь и защищаемся.
     */
    fun normalizeSerial(input: String?): String? {
        val digits = input?.filter { it.isDigit() } ?: return null
        return when {
            digits.isEmpty() -> null
            digits.length == TOTAL_DIGITS -> digits.substring(MODEL_DIGITS)
            digits.length <= SERIAL_DIGITS -> digits.padStart(SERIAL_DIGITS, '0')
            else -> null
        }
    }

    /**
     * true, только если прибор с рекламируемым именем [name] — это ИМЕННО тот ПУ, номер которого
     * запросил пользователь ([requestedSerial]). Любая неопределённость (имя не разобралось,
     * номер не распознан) — это false: подключаться «на всякий случай» нельзя.
     */
    fun matches(name: String?, requestedSerial: String?): Boolean {
        val wanted = normalizeSerial(requestedSerial) ?: return false
        val actual = parse(name)?.serialNumber ?: return false
        return actual == wanted
    }

    /**
     * Сравнение номера, который сообщил САМ прибор (объект 0.0.96.1.0.255 «Серийный номер» из
     * буфера индикации), с запрошенным. Отдельный метод, потому что источник другой: прибор
     * отдаёт номер строкой как есть, без модели — нормализуем обе стороны и сравниваем.
     */
    fun serialsEqual(first: String?, second: String?): Boolean {
        val a = normalizeSerial(first) ?: return false
        val b = normalizeSerial(second) ?: return false
        return a == b
    }
}
