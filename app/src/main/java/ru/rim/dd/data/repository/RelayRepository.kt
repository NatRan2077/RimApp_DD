package ru.rim.dd.data.repository

import kotlinx.coroutines.flow.Flow
import ru.rim.dd.core.model.RelayState

/** Состояние и управление реле — размыкателем нагрузки (UC-07 / UC-08). */
interface RelayRepository {
    fun relayState(): Flow<RelayState>
    suspend fun turnRelayOn()
    suspend fun turnRelayOff()
}
