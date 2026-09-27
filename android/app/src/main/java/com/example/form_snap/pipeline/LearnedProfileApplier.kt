package org.techwithkaushik.formSnap.pipeline

import android.graphics.RectF

data class LearnedApplication(
    val bounds: RectF,
    val appearance: AppearanceAdjustments,
    val blend: Float,
)

object LearnedProfileApplier {
    fun apply(
        candidate: DetectionCandidate,
        learned: LearnedCorrection?,
    ): LearnedApplication {
        if (learned == null) {
            return LearnedApplication(candidate.bounds, AppearanceAdjustments(), 0f)
        }

        val strength = (learned.confidence *
            (learned.sampleCount.coerceIn(1, 20) / 20f)).coerceIn(0f, 0.60f)

        val b = candidate.bounds
        val learnedBounds = RectF(
            b.left + learned.boundsDeltaLeft,
            b.top + learned.boundsDeltaTop,
            b.right + learned.boundsDeltaRight,
            b.bottom + learned.boundsDeltaBottom,
        )

        return LearnedApplication(
            bounds = blendBounds(b, learnedBounds, strength),
            appearance = blendAppearance(AppearanceAdjustments(), learned.appearance, strength),
            blend = strength,
        )
    }

    private fun blendBounds(base: RectF, learned: RectF, strength: Float): RectF = RectF(
        lerp(base.left, learned.left, strength),
        lerp(base.top, learned.top, strength),
        lerp(base.right, learned.right, strength),
        lerp(base.bottom, learned.bottom, strength),
    )

    private fun blendAppearance(
        base: AppearanceAdjustments,
        learned: AppearanceAdjustments,
        strength: Float,
    ): AppearanceAdjustments = AppearanceAdjustments(
        brightness = lerp(base.brightness, learned.brightness, strength),
        contrast = lerp(base.contrast, learned.contrast, strength),
        saturation = lerp(base.saturation, learned.saturation, strength),
        sharpness = lerp(base.sharpness, learned.sharpness, strength),
        denoise = lerp(base.denoise, learned.denoise, strength),
        inkThreshold = lerp(base.inkThreshold.toFloat(), learned.inkThreshold.toFloat(), strength).toInt(),
        backgroundCleanup = lerp(base.backgroundCleanup, learned.backgroundCleanup, strength),
    )

    private fun lerp(a: Float, b: Float, t: Float): Float =
        a + (b - a) * t
}
