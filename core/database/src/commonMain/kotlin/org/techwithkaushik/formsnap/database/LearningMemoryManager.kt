package org.techwithkaushik.formsnap.database

import kotlinx.serialization.Serializable

@Serializable
data class LearningMemoryArchiveDto(
    val schemaVersion: Int,
    val appVersion: String,
    val userCorrectionLogs: List<UserCorrectionLogDto>,
    val tunedParameters: List<TunedParameterDto>,
)

expect class LearningMemoryManager(
    database: LearningDatabase,
    appVersion: String,
) {
    suspend fun exportToFsl(
        sink: suspend (ByteArray) -> Unit,
    )

    suspend fun importFromFsl(
        source: suspend () -> ByteArray,
    )

    suspend fun exportToFslBytes(): ByteArray

    suspend fun importFromFslBytes(
        bytes: ByteArray,
    )
}
