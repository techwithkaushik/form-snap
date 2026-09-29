package org.techwithkaushik.formsnap.database

import kotlinx.coroutines.flow.Flow

data class UserCorrectionLogRecord(
    val id: Long,
    val timestamp: Long,
    val contentType: String,
    val detectedX: Long,
    val detectedY: Long,
    val correctedX: Long,
    val correctedY: Long,
    val isRejected: Long,
)

data class TunedParameterRecord(
    val parameterKey: String,
    val parameterValue: Double,
)

class LearningRepository internal constructor(
    private val access: LearningDatabaseAccess,
) {
    fun record(
        timestamp: Long,
        contentType: String,
        detectedX: Long,
        detectedY: Long,
        correctedX: Long,
        correctedY: Long,
        isRejected: Long,
    ) = access.insertCorrection(
        timestamp,
        contentType,
        detectedX,
        detectedY,
        correctedX,
        correctedY,
        isRejected,
    )

    fun allLogs(): Flow<List<UserCorrectionLogRecord>> =
        access.selectAllLogs()

    fun allLogsSnapshot(): List<UserCorrectionLogRecord> =
        access.selectAllLogsSnapshot()

    fun getParameter(parameterKey: String): Double? =
        access.getParameter(parameterKey)

    fun allParameters(): List<TunedParameterRecord> =
        access.selectAllParameters()

    fun updateParameter(
        parameterKey: String,
        parameterValue: Double,
    ) = access.updateParameter(parameterKey, parameterValue)
}

internal interface LearningDatabaseAccess {
    fun insertCorrection(
        timestamp: Long,
        contentType: String,
        detectedX: Long,
        detectedY: Long,
        correctedX: Long,
        correctedY: Long,
        isRejected: Long,
    )

    fun selectAllLogs(): Flow<List<UserCorrectionLogRecord>>

    fun selectAllLogsSnapshot(): List<UserCorrectionLogRecord>

    fun selectAllParameters(): List<TunedParameterRecord>

    fun getParameter(parameterKey: String): Double?

    fun updateParameter(
        parameterKey: String,
        parameterValue: Double,
    )
}
