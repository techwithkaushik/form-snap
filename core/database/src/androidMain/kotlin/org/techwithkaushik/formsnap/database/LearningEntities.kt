package org.techwithkaushik.formsnap.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "UserCorrectionLog")
data class UserCorrectionLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long,
    val contentType: String,
    val detectedX: Long,
    val detectedY: Long,
    val correctedX: Long,
    val correctedY: Long,
    val isRejected: Long = 0,
)

@Entity(tableName = "TunedParameters")
data class TunedParameterEntity(
    @PrimaryKey
    val parameterKey: String,
    val parameterValue: Double,
)
