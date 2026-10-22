package ru.rim.dd.data.journal

import ru.rim.dd.core.model.EnergyCategory
import ru.rim.dd.core.model.MeterInfo
import ru.rim.dd.core.model.NetworkParams
import ru.rim.dd.core.model.PhaseValues
import ru.rim.dd.core.model.Reading
import ru.rim.dd.data.local.JournalLine
import ru.rim.dd.data.local.JournalStore
import ru.rim.dd.ui.components.formatNumber
import javax.inject.Inject
import javax.inject.Singleton

/** Снимок данных прибора в том виде, в каком он попадает в карточку журнала. */
data class JournalSnapshot(
    val meterType: String?,
    val serialNumber: String?,
    val energy: List<JournalLine>,
    val voltage: String?,
    val current: String?,
)

/**
 * Связка «данные прибора → журнал». Превращает текущие данные в текстовый снимок
 * ([snapshotOf] — чистая функция) и передаёт его в [JournalStore]; остальные методы —
 * жизненный цикл карточки (начало подключения, сохранение, выход).
 */
@Singleton
class JournalRecorder @Inject constructor(
    private val journal: JournalStore,
) {
    /** Начало подключения: карточка и лог байтов заводятся до первого обмена. */
    fun begin() = journal.begin()

    /** Подключение не удалось — всё, что успело записаться, удаляется. */
    fun discard() = journal.discard()

    /** Выход: сохраняет последний целый снимок, если «+» не нажимали. */
    fun end() = journal.end()

    /** Кнопка «+»: сохраняет текущий снимок. false — данных для журнала ещё нет. */
    fun commit(): Boolean = journal.commit()

    /** Входящие байты транспорта для сырого лога (работает только при включённом флаге). */
    fun onIncomingBytes(chunk: ByteArray) = journal.appendBytes(chunk)

    /** Переносит последние разобранные данные в карточку. Вызывается после каждого разобранного буфера. */
    fun record(
        info: MeterInfo?,
        network: NetworkParams?,
        readings: List<Reading>,
        reportedSerial: String?,
    ) {
        val s = snapshotOf(info, network, readings, reportedSerial)
        journal.update(
            meterType = s.meterType,
            serialNumber = s.serialNumber,
            energy = s.energy,
            voltage = s.voltage,
            current = s.current,
        )
    }

    companion object {
        private val MODEL_PREFIX = Regex("^(РиМ|RIM|Rim)\\s*", RegexOption.IGNORE_CASE)

        /**
         * Тип и номер ПУ, энергия по тарифам, напряжение и ток. Номер, который прибор сообщил
         * сам ([reportedSerial]), приоритетнее разобранного из BLE-имени.
         */
        fun snapshotOf(
            info: MeterInfo?,
            network: NetworkParams?,
            readings: List<Reading>,
            reportedSerial: String?,
        ): JournalSnapshot = JournalSnapshot(
            meterType = info?.model?.replace(MODEL_PREFIX, "")?.trim(),
            serialNumber = reportedSerial ?: info?.serialNumber,
            energy = energyLines(readings),
            voltage = network?.let { phases(it.voltage, 1, "В") },
            current = network?.let { phases(it.current, 3, "А") },
        )

        internal fun energyLines(readings: List<Reading>): List<JournalLine> {
            fun head(c: EnergyCategory) = when (c) {
                EnergyCategory.ACTIVE_IMPORT -> "A+"
                EnergyCategory.ACTIVE_EXPORT -> "A−"
                EnergyCategory.REACTIVE_Q1 -> "R+"
                EnergyCategory.REACTIVE_Q4 -> "R−"
                EnergyCategory.OTHER -> "Прочее"
            }
            return readings
                .filter { !it.isRdch && it.category != EnergyCategory.OTHER }
                .sortedWith(compareBy({ it.category.ordinal }, { it.tariff ?: 0 }))
                .map {
                    val tariff = if (it.tariff == null) "сумма" else "Т${it.tariff}"
                    val unit = it.unitLabel?.let { u -> " $u" } ?: ""
                    JournalLine("${head(it.category)} · $tariff", formatNumber(it.valueKwh, 3) + unit)
                }
        }

        internal fun phases(v: PhaseValues, decimals: Int, unit: String): String =
            if (v.isThreePhase) {
                "L1 ${formatNumber(v.l1 ?: 0.0, decimals)} · L2 ${formatNumber(v.l2 ?: 0.0, decimals)} · " +
                        "L3 ${formatNumber(v.l3 ?: 0.0, decimals)} $unit"
            } else {
                "${formatNumber(v.total, decimals)} $unit"
            }
    }
}