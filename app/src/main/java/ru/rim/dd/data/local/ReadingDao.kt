package ru.rim.dd.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ReadingDao {
    @Insert
    suspend fun insert(entity: ReadingEntity)

    @Query("SELECT * FROM readings WHERE serialNumber = :serialNumber ORDER BY timestampEpochSeconds DESC")
    fun observeHistory(serialNumber: String): Flow<List<ReadingEntity>>

    @Query(
        "SELECT * FROM readings WHERE serialNumber = :serialNumber " +
            "AND timestampEpochSeconds BETWEEN :fromEpoch AND :toEpoch " +
            "ORDER BY timestampEpochSeconds DESC"
    )
    suspend fun history(serialNumber: String, fromEpoch: Long, toEpoch: Long): List<ReadingEntity>
}
