package org.techwithkaushik.formsnap.core.database

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
    fun key(): String =
        "$brightnessBucket:$edgeDensityBucket:$aspectBucket"
}
