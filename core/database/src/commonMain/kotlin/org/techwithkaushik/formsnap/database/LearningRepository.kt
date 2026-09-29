package org.techwithkaushik.formsnap.database

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow

class LearningRepository(
    private val database: LearningDatabaseWrapper,
) {
    private val queries = database.instance.learningDatabaseQueries

    fun record(
        timestamp: Long,
        contentType: String,
        detectedX: Long,
        detectedY: Long,
        correctedX: Long,
        correctedY: Long,
        isRejected: Long,
    ) {
        queries.insertCorrection(
            timestamp = timestamp,
            contentType = contentType,
            detectedX = detectedX,
            detectedY = detectedY,
            correctedX = correctedX,
            correctedY = correctedY,
            isRejected = isRejected,
        )
    }

    fun allLogs(): Flow<List<UserCorrectionLog>> =
        queries
            .selectAllLogs()
            .asFlow()
            .mapToList(Dispatchers.Default)

    fun getParameter(parameterKey: String): Double? =
        queries.getParameter(parameterKey).executeAsOneOrNull()

    fun allParameters(): List<TunedParameters> =
        queries.selectAllParameters().executeAsList()

    fun updateParameter(
        parameterKey: String,
        parameterValue: Double,
    ) {
        queries.updateParameter(
            parameterKey = parameterKey,
            parameterValue = parameterValue,
        )
    }
}
