package ru.rim.dd.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import ru.rim.dd.core.model.MeterInfo
import ru.rim.dd.core.model.NetworkParams
import ru.rim.dd.core.model.PhaseValues
import ru.rim.dd.core.model.Reading
import ru.rim.dd.core.model.TamperState
import ru.rim.dd.data.mapper.MeterBufferMapper
import ru.rim.dd.data.session.MeterSessionManager
import ru.rim.dd.data.state.MeterState
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MeterDataRepositoryImpl @Inject constructor(
    private val state: MeterState,
    private val session: MeterSessionManager,
) : MeterDataRepository {

    override fun readings(): Flow<List<Reading>> = state.readings

    override suspend fun refreshReadings() = session.refresh()

    override fun networkParams(): Flow<NetworkParams> = state.network.map {
        it ?: NetworkParams(
            voltage = PhaseValues(0.0),
            current = PhaseValues(0.0),
            power = PhaseValues(0.0),
        )
    }

    override fun meterInfo(): Flow<MeterInfo> =
        state.info.map { it ?: MeterBufferMapper.blankMeterInfo(session.activeSerialNumber) }

    override fun tamperState(): Flow<TamperState> = state.tamper
}
