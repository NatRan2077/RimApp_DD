package ru.rim.dd.core.model

/**
 * Состояние одной фазы — расшифровка байта «Статус фаз» буфера индикации (позиционное поле
 * без OBIS, код в приложении DD_PHASE_DISPLAY_OBIS = 1.0.96.50.0.255).
 * Раскладка бит 1:1 из прошивки пульта РиМ 040.40 (threads_other.c):
 *  бит 7 (0x80) — прибор трёхфазный (у однофазного статус фаз не показывается);
 *  бит 3 (0x08) — нарушено чередование фаз (перефазировка);
 *  биты 0..2 (0x01/0x02/0x04) — фаза A/B/C ОТСУТСТВУЕТ;
 *  биты 4..6 (0x10/0x20/0x40) — переток (обратное направление) по фазе A/B/C.
 * Если переток и отсутствие выставлены вместе, прошивка рисует мигающий значок (приоритет у перетока).
 */
enum class PhaseCondition { PRESENT, ABSENT, REVERSE }

data class PhaseStatus(
    val threePhase: Boolean,
    val sequenceError: Boolean,
    val a: PhaseCondition,
    val b: PhaseCondition,
    val c: PhaseCondition,
) {
    companion object {
        fun decode(raw: Long): PhaseStatus {
            val v = (raw and 0xFF).toInt()
            fun cond(absentMask: Int, reverseMask: Int): PhaseCondition = when {
                (v and reverseMask) != 0 -> PhaseCondition.REVERSE
                (v and absentMask) != 0 -> PhaseCondition.ABSENT
                else -> PhaseCondition.PRESENT
            }
            return PhaseStatus(
                threePhase = (v and 0x80) != 0,
                sequenceError = (v and 0x08) != 0,
                a = cond(0x01, 0x10),
                b = cond(0x02, 0x20),
                c = cond(0x04, 0x40),
            )
        }
    }
}

/**
 * Направление энергии — младший байт 32-битного поля «Значки ДД»
 * (0.0.96.50.170.255), биты 0..3 (energyDir_t в прошивке пульта): A+ (потребление активной),
 * A− (отдача активной), R+ / R− — то же для реактивной. Допустимы комбинации (например A+ и R−).
 */
data class EnergyDirection(
    val activePlus: Boolean,
    val activeMinus: Boolean,
    val reactivePlus: Boolean,
    val reactiveMinus: Boolean,
) {
    val none: Boolean get() = !(activePlus || activeMinus || reactivePlus || reactiveMinus)

    companion object {
        fun decode(statusWord: Long): EnergyDirection {
            val b = (statusWord and 0x0F).toInt()
            return EnergyDirection(
                activePlus = (b and 0x01) != 0,
                activeMinus = (b and 0x02) != 0,
                reactivePlus = (b and 0x04) != 0,
                reactiveMinus = (b and 0x08) != 0,
            )
        }
    }
}
