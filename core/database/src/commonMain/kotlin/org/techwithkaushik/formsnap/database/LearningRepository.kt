package org.techwithkaushik.formsnap.database

class LearningRepository(
    private val database: LearningDatabase,
) {
    private val queries = database.learningDatabaseQueries

    fun record(
        timestamp: Long,
        contentType: String,
        detectedX: Int,
        detectedY: Int,
        correctedX: Int,
        correctedY: Int,
        isRejected: Boolean,
    ) {
        queries.insertLog(
            timestamp = timestamp,
            contentType = contentType,
            detectedX = detectedX.toLong(),
            detectedY = detectedY.toLong(),
            correctedX = correctedX.toLong(),
            correctedY = correctedY.toLong(),
            isRejected = isRejected,
        )
    }

    fun allLogs(): List<UserCorrectionLog> =
        queries.selectAllLogs().executeAsList()

    fun allParameters(): List<TunedParameters> =
        queries.selectAllParameters().executeAsList()
}
