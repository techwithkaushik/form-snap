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
    val tunedParameters: List<TunedParameterDto>,
    val userCorrectionLogs: List<UserCorrectionLogDto>,
)

data class RectDelta(
    val left: Double,
    val top: Double,
    val width: Double,
    val height: Double,
)

data class DetectionSample(
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
    val brightnessBucket: Int = 0,
    val edgeDensityBucket: Int = 0,
    val aspectBucket: Int = 0,
    val createdAt: Long,
)

data class ThresholdProfile(
    val kind: String,
    val bias: Double,
    val sampleCount: Long,
    val meanDx: Double,
    val meanDy: Double,
    val meanDw: Double,
    val meanDh: Double,
)

data class LearningContext(
    val brightnessBucket: Int,
    val edgeDensityBucket: Int,
    val aspectBucket: Int,
) {
    fun key(): String = "$brightnessBucket:$edgeDensityBucket:$aspectBucket"
}
