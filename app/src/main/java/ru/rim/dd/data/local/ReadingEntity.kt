package ru.rim.dd.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "readings")
data class ReadingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val obisId: String,
    val tariff: Int?,
    val valueKwh: Double,
    val timestampEpochSeconds: Long,
    val serialNumber: String, // к какому ПУ относится запись (при нескольких сопряжённых устройствах)
)
