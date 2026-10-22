package ru.rim.dd.data.repository

import kotlinx.coroutines.flow.Flow
import ru.rim.dd.core.model.MeterInfo
import ru.rim.dd.core.model.NetworkParams
import ru.rim.dd.core.model.Reading
import ru.rim.dd.core.model.TamperState

/** Данные подключённого прибора: показания, сеть, сведения, пломбы, история (UC-03 … UC-06, UC-10). */
interface MeterDataRepository {
    fun readings(): Flow<List<Reading>>

    /** Кнопка «Обновить»: один GET буфера обновляет показания, сеть и инфо. */
    suspend fun refreshReadings()

    fun networkParams(): Flow<NetworkParams>

    fun meterInfo(): Flow<MeterInfo>

    /** Пломбы, датчики, батарея и лимит мощности — из буфера индикации, см. [TamperState]. */
    fun tamperState(): Flow<TamperState>

    fun history(serialNumber: String): Flow<List<Reading>>
}
