package org.techwithkaushik.formSnap.pipeline

data class AppearanceAdjustments(
    val brightness: Float = 0f,
    val contrast: Float = 1f,
    val saturation: Float = 1f,
    val sharpness: Float = 0f,
    val denoise: Float = 0f,
    val inkThreshold: Int = 150,
    val backgroundCleanup: Float = 0f,
)

data class LearnedCorrection(
    val kind: DetectionKind,
    val boundsDeltaLeft: Float = 0f,
    val boundsDeltaTop: Float = 0f,
    val boundsDeltaRight: Float = 0f,
    val boundsDeltaBottom: Float = 0f,
    val appearance: AppearanceAdjustments = AppearanceAdjustments(),
    val sampleCount: Int = 1,
    val confidence: Float = 0.5f,
    val version: Int = 1,
)

object CorrectionLearning {
    fun fromCorrection(
        automatic: DetectionCandidate,
        correctedBounds: android.graphics.RectF?,
        appearance: AppearanceAdjustments,
    ): LearnedCorrection {
        val corrected = correctedBounds ?: automatic.bounds
        return LearnedCorrection(
            kind = automatic.kind,
            boundsDeltaLeft = corrected.left - automatic.bounds.left,
            boundsDeltaTop = corrected.top - automatic.bounds.top,
            boundsDeltaRight = corrected.right - automatic.bounds.right,
            boundsDeltaBottom = corrected.bottom - automatic.bounds.bottom,
            appearance = appearance,
            sampleCount = 1,
            confidence = automatic.confidence,
        )
    }

    fun blend(previous: LearnedCorrection, incoming: LearnedCorrection): LearnedCorrection {
        val oldWeight = previous.sampleCount.toFloat()
        val newWeight = 1f
        val total = oldWeight + newWeight

        fun avg(a: Float, b: Float): Float = (a * oldWeight + b * newWeight) / total
        fun avgInt(a: Int, b: Int): Int = avg(a.toFloat(), b.toFloat()).toInt()

        return previous.copy(
            boundsDeltaLeft = avg(previous.boundsDeltaLeft, incoming.boundsDeltaLeft),
            boundsDeltaTop = avg(previous.boundsDeltaTop, incoming.boundsDeltaTop),
            boundsDeltaRight = avg(previous.boundsDeltaRight, incoming.boundsDeltaRight),
            boundsDeltaBottom = avg(previous.boundsDeltaBottom, incoming.boundsDeltaBottom),
            appearance = AppearanceAdjustments(
                brightness = avg(previous.appearance.brightness, incoming.appearance.brightness),
                contrast = avg(previous.appearance.contrast, incoming.appearance.contrast),
                saturation = avg(previous.appearance.saturation, incoming.appearance.saturation),
                sharpness = avg(previous.appearance.sharpness, incoming.appearance.sharpness),
                denoise = avg(previous.appearance.denoise, incoming.appearance.denoise),
                inkThreshold = avgInt(previous.appearance.inkThreshold, incoming.appearance.inkThreshold),
                backgroundCleanup = avg(previous.appearance.backgroundCleanup, incoming.appearance.backgroundCleanup),
            ),
            sampleCount = (previous.sampleCount + 1).coerceAtMost(100),
            confidence = avg(previous.confidence, incoming.confidence).coerceIn(0f, 1f),
        )
    }
}
