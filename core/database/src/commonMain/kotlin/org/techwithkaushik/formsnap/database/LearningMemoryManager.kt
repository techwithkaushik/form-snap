package org.techwithkaushik.formsnap.database

import kotlinx.serialization.Serializable

@Serializable
data class UserCorrectionLogDto(
    val id: Long,
    val sampleKey: String,
    val kind: String,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val estimatedX: Double,
    val estimatedY: Double,
    val estimatedWidth: Double,
    val estimatedHeight: Double,
    val correctedX: Double,
    val correctedY: Double,
    val correctedWidth: Double,
    val correctedHeight: Double,
    val thresholdBias: Double,
    val blockSize: Int,
    val accepted: Boolean,
    val brightnessBucket: Int,
    val edgeDensityBucket: Int,
    val aspectBucket: Int,
    val createdAt: Long,
)

@Serializable
data class TunedParameterDto(
    val kind: String,
    val contextKey: String,
    val actionIndex: Int,
    val visits: Long,
    val totalReward: Double,
    val lastReward: Double,
    val updatedAt: Long,
)

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
