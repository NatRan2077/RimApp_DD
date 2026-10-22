package ru.rim.dd.data.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import ru.rim.dd.core.dlms.ObisReading
import ru.rim.dd.core.model.MeterInfo
import ru.rim.dd.core.model.NetworkParams
import ru.rim.dd.core.model.Reading
import ru.rim.dd.core.model.RelaySource
import ru.rim.dd.core.model.RelayState
import ru.rim.dd.core.model.TamperState
import ru.rim.dd.data.mapper.MeterBufferMapper
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Текущие данные подключённого прибора: то, что наблюдают экраны. Отвечает только за хранение
 * и обновление — разбор буфера делает [MeterBufferMapper], обмен с прибором — репозиторий.
 *
 * Данные принадлежат конкретному прибору, поэтому при новом подключении и при отключении
 * вызывается [reset]: иначе поля, которых новый прибор не присылает (например HDLC P1 у AKROS),
 * показывались бы со значениями прежнего ПУ — разбор буфера сохраняет прежнее значение для
 * не пришедших полей.
 */
@Singleton
class MeterState @Inject constructor() {

    private val _relay = MutableStateFlow(initialRelay())
    private val _tamper = MutableStateFlow(TamperState.UNKNOWN)
    private val _network = MutableStateFlow<NetworkParams?>(null)
    private val _info = MutableStateFlow<MeterInfo?>(null)
    private val _readings = MutableStateFlow<List<Reading>>(emptyList())

    val relay: StateFlow<RelayState> = _relay.asStateFlow()
    val tamper: StateFlow<TamperState> = _tamper.asStateFlow()
    val network: StateFlow<NetworkParams?> = _network.asStateFlow()
    val info: StateFlow<MeterInfo?> = _info.asStateFlow()
    val readings: StateFlow<List<Reading>> = _readings.asStateFlow()

    /** Сбрасывает все данные прибора (новое подключение или отключение). */
    fun reset() {
        _relay.value = initialRelay()
        _tamper.value = TamperState.UNKNOWN
        _network.value = null
        _info.value = null
        _readings.value = emptyList()
    }

    /**
     * Раскладывает разобранный буфер индикации по состояниям. Что в буфере не пришло — не
     * меняется. Возвращает серийный номер, который прибор сообщил сам (null — в этом буфере его нет).
     */
    fun applyBuffer(
        all: List<ObisReading>,
        activeSerialNumber: String?,
        knownReportedSerial: String?,
        now: Instant = Instant.now(),
        log: (String) -> Unit = {},
    ): String? {
        MeterBufferMapper.relayState(all, _relay.value)?.let { _relay.value = it }
        MeterBufferMapper.tamperState(all)?.let { _tamper.value = it }
        MeterBufferMapper.energyReadings(all, now)?.let { _readings.value = it }
        MeterBufferMapper.networkParams(all)?.let { _network.value = it }
        val infoResult = MeterBufferMapper.meterInfo(
            all = all,
            prev = _info.value,
            activeSerialNumber = activeSerialNumber,
            knownReportedSerial = knownReportedSerial,
            now = now,
            log = log,
        )
        infoResult?.let { _info.value = it.info }
        return infoResult?.reportedSerial
    }

    fun updateRelay(transform: (RelayState) -> RelayState) = _relay.update(transform)

    fun updateInfo(transform: (MeterInfo?) -> MeterInfo?) = _info.update(transform)

    private fun initialRelay() = RelayState(isOn = false, powerLimitKw = 0.0, source = RelaySource.UNKNOWN)
}