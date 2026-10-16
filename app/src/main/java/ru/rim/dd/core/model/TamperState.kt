package ru.rim.dd.core.model

/**
 * Состояние датчика вмешательства. Три состояния, как в прошивке пульта РиМ 040.40: ACTIVE —
 * «сработано сейчас», FLAG — «было, сейчас не активно», OK. Соответствует семантике пломб по
 * СТО 34.01-5.1-006 (обжата / взломана / последующие вскрытия).
 */
enum class TamperCondition {
    OK,
    FLAG,
    ACTIVE,
}

/**
 * Состояние пломб и датчиков вмешательства, расшифрованное из прошивки пульта РиМ 040.40
 * (thread_dataRequest.c, read_status(), «версия ЖКИ 1»). Источник один — позиционное 32-битное
 * поле «Значки ДД» (0.0.96.50.170.255) в буфере индикации 0.0.21.0.2.255; отдельных GET на
 * объекты СТО (0.0.96.51.x) нет — на приборе они, скорее всего, недоступны без ассоциации.
 *
 * Раскладка поля:
 *  байт 0 (биты 31..24): статус ПКЭ и небаланс (см. eqi/unbalance)
 *  байт 1 (биты 23..16): «сейчас активно» — бит 0 = крышка/корпус, бит 1 = замок счётчика/
 *                        клеммника, бит 2 = магнитное поле, бит 3 = батарея, бит 4 = СВЧ/RF,
 *                        бит 5 = ошибка самодиагностики, биты 6-7 = версия ЖКИ (0..3)
 *  байт 2 (биты 15..8):  те же биты 0-5, но «было зафиксировано, сейчас не активно» (FLAG);
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
    /** ПКЭ (показатели качества электроэнергии) — байт 0 поля: бит 0 = активно, бит 4 = была сработка. */
    val eqi: TamperCondition = TamperCondition.OK,
    /** Небаланс — байт 0 поля: бит 1 = активно, бит 5 = была сработка (только версия ЖКИ ≥ 1). */
    val unbalance: TamperCondition = TamperCondition.OK,
    /**
     * Версия ЖКИ — биты 6..7 байта 1 (прошивка пульта: 0 — «до 890 постановления», 1 — «890
     * постановление»). От неё зависит раскладка: у версии 0 есть только ПКЭ и магнит.
     */
    val lcdVersion: Int = 1,
    /** true, пока не пришло ни одного успешного цикла. */
    val unknown: Boolean = true,
) {
    /** Есть ли хоть одно ACTIVE (не FLAG) нарушение. */
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

        /** [rawStatus] — значение «Значки ДД» (0.0.96.50.170.255), раскладка см. выше; 1:1 с read_status() прошивки. */
        fun decode(rawStatus: Long): TamperState {
            val eqiBits = (rawStatus ushr 24) and 0xFF // байт 0
            val activeBits = (rawStatus ushr 16) and 0xFF // байт 1
            val flagBits = (rawStatus ushr 8) and 0xFF // байт 2
            val lcdVersion = ((activeBits ushr 6) and 0x03).toInt()

            // Версия ЖКИ 0 («до 890 постановления»): ПКЭ = ненулевой байт 0, магнит = ненулевой
            // байт 1 или 2, других датчиков нет.
            if (lcdVersion == 0) {
                return TamperState(
                    cover = TamperCondition.OK,
                    meter = TamperCondition.OK,
                    magnet = if (activeBits != 0L || flagBits != 0L) TamperCondition.ACTIVE else TamperCondition.OK,
                    battery = TamperCondition.OK,
                    rf = TamperCondition.OK,
                    selfDiagnosticsFault = TamperCondition.OK,
                    powerLimitExceeded = false,
                    eqi = if (eqiBits != 0L) TamperCondition.ACTIVE else TamperCondition.OK,
                    unbalance = TamperCondition.OK,
                    lcdVersion = 0,
                    unknown = false,
                )
            }

            fun bit(mask: Long): TamperCondition = when {
                (activeBits and mask) == mask -> TamperCondition.ACTIVE
                (flagBits and mask) == mask -> TamperCondition.FLAG
                else -> TamperCondition.OK
            }

            // Байт 0: «активно» — младшие биты (0x01 ПКЭ, 0x02 небаланс), «была сработка» — старшие
            // (0x10 ПКЭ, 0x20 небаланс), как в read_status() прошивки пульта.
            fun eqiBit(active: Long, flag: Long): TamperCondition = when {
                (eqiBits and active) == active -> TamperCondition.ACTIVE
                (eqiBits and flag) == flag -> TamperCondition.FLAG
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
                eqi = eqiBit(0x01, 0x10),
                unbalance = eqiBit(0x02, 0x20),
                lcdVersion = lcdVersion,
                unknown = false,
            )
        }
    }
}
