package ru.rim.dd.core.model

/**
 * [Android-патч] Состояние отдельного датчика вмешательства (пломба/магнит/СВЧ и т.п.) —
 * см. TamperState.decode() и TAMPER_STATUS_OBIS в GetResponseParser.kt. Три состояния, а не
 * два (просто да/нет) — потому что так их закодировала сама прошивка пульта РиМ 040.40
 * (thread_dataRequest.c::read_status(), версия ЖКИ 1): есть отдельно бит "СЕЙЧАС сработано"
 * (ACTIVE) и отдельно "было зафиксировано, но сейчас не активно" (FLAG) — это соответствует
 * официальной семантике электронных пломб по СТО 34.01-5.1-006 (0=не определено, 1=обжата,
 * 2=взломана, 3=последующие вскрытия): ACTIVE ~ "взломана/вскрытие сейчас", FLAG ~ "было
 * вскрытие, статус зафиксирован", OK ~ "обжата, нарушений нет".
 */
enum class TamperCondition {
    OK,
    FLAG,
    ACTIVE,
}

/**
 * [Android-патч] Состояние пломб и датчиков внешних воздействий прибора учёта — ПОЛНОСТЬЮ
 * расшифровано из исходников прошивки самого пульта РиМ 040.40 (файл
 * thread_dataRequest.c, функция read_status(), ветка "версия ЖКИ 1" — она же реально
 * наблюдается на приборах из логов: см. TAMPER_STATUS_OBIS). Как и control_state размыкателя
 * (см. RELAY_CONTROL_STATE_OBIS), это НЕ отдельный GET на объекты СТО 34.01-5.1-006 (Е.12:
 * 0.0.96.51.0/1/3/4/5.255 — на этом приборе такой прямой запрос почти наверняка получил бы
 * отказ доступа, как и все остальные объекты без DLMS-ассоциации) — источник ОДИН: то же
 * позиционное (без обёртки в OBIS-код) 32-битное поле "Значки ДД" (0.0.96.50.170.255) внутри
 * ТОГО ЖЕ буфера индикации (0.0.21.0.2.255), который мы и так опрашиваем каждый цикл.
 *
 * Раскладка байтов 32-битного поля (см. комментарий в GetResponseParser.kt и оригинал в
 * thread_dataRequest.c):
 *  байт 0 (биты 31..24): статус ПКЭ (не используется здесь)
 *  байт 1 (биты 23..16): "сейчас активно" — бит 0 = крышка/корпус, бит 1 = замок счётчика/
 *                        клеммника, бит 2 = магнитное поле, бит 3 = батарея, бит 4 = СВЧ/RF,
 *                        бит 5 = ошибка самодиагностики, биты 6-7 = версия ЖКИ (0..3)
 *  байт 2 (биты 15..8):  те же биты 0-5, но "было зафиксировано, сейчас не активно" (FLAG);
 *                        бит 7 = превышен лимит мощности (превышение УПМк)
 *  байт 3 (биты 7..0):   квадрант (направление энергии), здесь не используется
 */
data class TamperState(
    val cover: TamperCondition,
    val meter: TamperCondition,
    val magnet: TamperCondition,
    val battery: TamperCondition,
    val rf: TamperCondition,
    val selfDiagnosticsFault: TamperCondition,
    val powerLimitExceeded: Boolean,
    /** true, пока не пришло ни одного успешного цикла — честное "не знаем", как и RelaySource.UNKNOWN. */
    val unknown: Boolean = true,
) {
    /** Есть ли ХОТЬ ОДНО активное (не FLAG, а именно ACTIVE) нарушение прямо сейчас. */
    val anyActive: Boolean
        get() = !unknown && listOf(cover, meter, magnet, battery, rf).any { it == TamperCondition.ACTIVE }

    companion object {
        val UNKNOWN = TamperState(
            cover = TamperCondition.OK,
            meter = TamperCondition.OK,
            magnet = TamperCondition.OK,
            battery = TamperCondition.OK,
            rf = TamperCondition.OK,
            selfDiagnosticsFault = TamperCondition.OK,
            powerLimitExceeded = false,
            unknown = true,
        )

        /**
         * [Android-патч] Раскладка бит — 1:1 копия read_status() из прошивки ДД (ветка
         * "версия ЖКИ 1"). rawStatus — значение элемента буфера 0.0.96.50.170.255 ("Значки ДД"),
         * как из ObisReading.rawValue (см. TAMPER_STATUS_OBIS в GetResponseParser.kt).
         */
        fun decode(rawStatus: Long): TamperState {
            val activeBits = (rawStatus ushr 16) and 0xFF // байт 1
            val flagBits = (rawStatus ushr 8) and 0xFF // байт 2

            fun bit(mask: Long): TamperCondition = when {
                (activeBits and mask) == mask -> TamperCondition.ACTIVE
                (flagBits and mask) == mask -> TamperCondition.FLAG
                else -> TamperCondition.OK
            }

            return TamperState(
                cover = bit(0x01),
                meter = bit(0x02),
                magnet = bit(0x04),
                battery = bit(0x08),
                rf = bit(0x10),
                selfDiagnosticsFault = bit(0x20),
                powerLimitExceeded = (flagBits and 0x80) == 0x80L,
                unknown = false,
            )
        }
    }
}
