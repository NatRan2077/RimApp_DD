package ru.rim.dd.feature.network

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.rim.dd.core.model.MeterInfo
import ru.rim.dd.core.model.NetworkParams
import ru.rim.dd.core.model.Reading
import ru.rim.dd.core.model.RelaySource
import ru.rim.dd.core.model.RelayState
import ru.rim.dd.core.model.TamperState
import ru.rim.dd.data.repository.MeterDataRepository
import ru.rim.dd.data.repository.RelayRepository

class NetworkViewModelTest {

    private val relayStateFlow = MutableStateFlow(
        RelayState(isOn = false, powerLimitKw = 0.0, source = RelaySource.METER_READ, remoteTurnOnAllowed = true)
    )

    private val fakeRelayRepository = object : RelayRepository {
        override fun relayState(): Flow<RelayState> = relayStateFlow
        override suspend fun turnRelayOn() {}
        override suspend fun turnRelayOff() {}
    }

    private val fakeMeterDataRepository = object : MeterDataRepository {
        override fun readings(): Flow<List<Reading>> = emptyFlow()
        override fun networkParams(): Flow<NetworkParams> = emptyFlow()
        override fun meterInfo(): Flow<MeterInfo> = emptyFlow()
        override fun tamperState(): Flow<TamperState> = emptyFlow()
        override suspend fun refreshReadings() {}
    }

    @Test
    fun turnOnCountdown_initialValueIsNull() {
        val viewModel = NetworkViewModel(fakeMeterDataRepository, fakeRelayRepository)
        assertNull(viewModel.turnOnCountdown.value)
    }

    @Test
    fun relayState_initialStateIsUnknown() {
        val viewModel = NetworkViewModel(fakeMeterDataRepository, fakeRelayRepository)
        assertEquals(RelaySource.UNKNOWN, viewModel.relayState.value.source)
    }
}
