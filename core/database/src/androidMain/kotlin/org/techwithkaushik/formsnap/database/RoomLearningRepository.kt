package org.techwithkaushik.formsnap.database

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class AndroidLearningRepositoryFactory(
    context: Context,
) {
    private val dao = DatabaseProvider.get(context).learningDao()

    fun create(): LearningRepository = LearningRepository(RoomAccess(dao))
}

private class RoomAccess(
    private val dao: LearningDao,
) : LearningDatabaseAccess {
    override fun insertCorrection(
        timestamp: Long,
        contentType: String,
        detectedX: Long,
        detectedY: Long,
        correctedX: Long,
        correctedY: Long,
        isRejected: Long,
    ) {
        dao.insertCorrection(
            UserCorrectionLogEntity(
                timestamp = timestamp,
                contentType = contentType,
                detectedX = detectedX,
                detectedY = detectedY,
                correctedX = correctedX,
                correctedY = correctedY,
                isRejected = isRejected,
            ),
        )
    }

    override fun selectAllLogs(): Flow<List<UserCorrectionLogRecord>> =
        dao.selectAllLogs().map { rows ->
            rows.map {
                UserCorrectionLogRecord(
                    id = it.id,
                    timestamp = it.timestamp,
                    contentType = it.contentType,
                    detectedX = it.detectedX,
                    detectedY = it.detectedY,
                    correctedX = it.correctedX,
                    correctedY = it.correctedY,
                    isRejected = it.isRejected,
                )
            }
        }

    override fun selectAllLogsSnapshot(): List<UserCorrectionLogRecord> =
        dao.selectAllLogsSnapshot().map {
            UserCorrectionLogRecord(
                id = it.id,
                timestamp = it.timestamp,
                contentType = it.contentType,
                detectedX = it.detectedX,
                detectedY = it.detectedY,
                correctedX = it.correctedX,
                correctedY = it.correctedY,
                isRejected = it.isRejected,
            )
        }

    override fun selectAllParameters(): List<TunedParameterRecord> =
        dao.selectAllParameters().map {
            TunedParameterRecord(
                parameterKey = it.parameterKey,
                parameterValue = it.parameterValue,
            )
        }

    override fun getParameter(parameterKey: String): Double? =
        dao.getParameter(parameterKey)?.parameterValue

    override fun updateParameter(
        parameterKey: String,
        parameterValue: Double,
    ) {
        dao.updateParameter(
            TunedParameterEntity(
                parameterKey = parameterKey,
                parameterValue = parameterValue,
            ),
        )
    }
}
