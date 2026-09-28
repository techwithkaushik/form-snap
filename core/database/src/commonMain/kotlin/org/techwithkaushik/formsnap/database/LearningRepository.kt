package org.techwithkaushik.formsnap.database

class LearningRepository(
    private val database: LearningDatabase,
) {
    private val queries = database.learningDatabaseQueries

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

    fun allLogs(): List<UserCorrectionLog> =
        queries.selectAllLogs().executeAsList()

    fun getParameter(parameterKey: String): Double? =
        queries.getParameter(parameterKey).executeAsOneOrNull()

    fun allParameters(): List<TunedParameters> =
        queries.selectAllParameters().executeAsList()
}
