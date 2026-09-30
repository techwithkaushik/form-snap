package org.techwithkaushik.formSnap.pipeline

import android.graphics.RectF
import android.content.Context
import kotlin.math.abs

data class CorrectionFeedback(
    val kind: DetectionKind,
    val automatic: DetectionCandidate,
    val correctedBounds: RectF,
    val appearance: AppearanceAdjustments,
    val accepted: Boolean = true,
    val conditionFeatures: ImageConditionFeatures? = null,
)

object FeedbackRecorder {
    fun record(context: Context, feedback: CorrectionFeedback) {
        if (!feedback.accepted || !isSafeFeedback(feedback)) return

        val learned = CorrectionLearning.fromCorrection(
            automatic = feedback.automatic,
            correctedBounds = feedback.correctedBounds,
            appearance = AppearanceTuning.clamp(feedback.appearance),
            sourceBrightness = feedback.conditionFeatures?.brightness ?: 0f,
            sourceContrast = feedback.conditionFeatures?.contrast ?: 1f,
            sourceSaturation = feedback.conditionFeatures?.saturation ?: 1f,
            sourceEdgeDensity = feedback.conditionFeatures?.edgeDensity ?: 0f,
        ).copy(
            // This sample is an explicitly accepted human correction. Detection
            // confidence describes the machine's original guess, not the quality
            // of the user's corrected target; carrying it forward suppressed learning.
            confidence = 1f,
        )
        LearningStore.record(context, learned)
    }

    /**
     * Do not let accidental handles, corrupt coordinates, or a mismatched
     * preview state poison future automatic crops.
     */
    internal fun isSafeFeedback(feedback: CorrectionFeedback): Boolean {
        if (feedback.kind != feedback.automatic.kind) return false
        val original = feedback.automatic.bounds
        val corrected = feedback.correctedBounds
        if (!valid(original) || !valid(corrected)) return false
        if (!feedback.automatic.confidence.isFinite() ||
            feedback.automatic.confidence !in 0f..1f
        ) return false

        val horizontalLimit = original.width() * MAX_EDGE_DELTA_RATIO
        val verticalLimit = original.height() * MAX_EDGE_DELTA_RATIO
        if (abs(corrected.left - original.left) > horizontalLimit ||
            abs(corrected.right - original.right) > horizontalLimit ||
            abs(corrected.top - original.top) > verticalLimit ||
            abs(corrected.bottom - original.bottom) > verticalLimit
        ) return false

        val widthRatio = corrected.width() / original.width()
        val heightRatio = corrected.height() / original.height()
        if (widthRatio !in MIN_SIZE_RATIO..MAX_SIZE_RATIO ||
            heightRatio !in MIN_SIZE_RATIO..MAX_SIZE_RATIO
        ) return false

        val appearance = feedback.appearance
        return listOf(
            appearance.brightness,
            appearance.contrast,
            appearance.saturation,
            appearance.sharpness,
            appearance.denoise,
            appearance.backgroundCleanup,
        ).all { it.isFinite() }
    }

    private fun valid(bounds: RectF): Boolean =
        bounds.left.isFinite() && bounds.top.isFinite() &&
            bounds.right.isFinite() && bounds.bottom.isFinite() &&
            bounds.width() >= 1f && bounds.height() >= 1f

    private const val MAX_EDGE_DELTA_RATIO = 0.35f
    private const val MIN_SIZE_RATIO = 0.50f
    private const val MAX_SIZE_RATIO = 1.50f
}
