package org.techwithkaushik.formSnap.pipeline

import android.graphics.RectF
import kotlin.math.abs

data class LearnedApplication(
    val bounds: RectF,
    val appearance: AppearanceAdjustments,
    val blend: Float,
)

object LearnedProfileApplier {
    fun apply(
        candidate: DetectionCandidate,
        learned: LearnedCorrection?,
        conditionFeatures: ImageConditionFeatures? = null,
    ): LearnedApplication {
        if (learned == null || !isSafeToApply(candidate, learned, conditionFeatures)) {
            return LearnedApplication(candidate.bounds, AppearanceAdjustments(), 0f)
        }

        val aspect = candidate.bounds.height() / candidate.bounds.width().coerceAtLeast(1f)
        val conditionMatch = CorrectionLearning.conditionSimilarity(
            profile = learned,
            conditionBrightness = conditionFeatures?.brightness,
            conditionContrast = conditionFeatures?.contrast,
            conditionSaturation = conditionFeatures?.saturation,
            conditionEdgeDensity = conditionFeatures?.edgeDensity,
            aspectRatio = conditionFeatures?.aspectRatio ?: aspect,
        )
        // Human-approved corrections are useful after the first sample.
        // Grow influence gradually with repeated samples instead of dividing sample
        // count by 20 (which made early learning effectively invisible).
        val sampleStrength = 0.16f + (learned.sampleCount.coerceIn(1, 10) - 1) * 0.045f
        val maxStrength = if (learned.sampleCount <= 1) 0.22f else 0.60f
        val strength = (learned.confidence * sampleStrength * conditionMatch)
            .coerceIn(0f, maxStrength)

        if (strength < 0.04f) {
            return LearnedApplication(candidate.bounds, AppearanceAdjustments(), 0f)
        }

        val b = candidate.bounds
        // v3 stores normalized edge deltas; v1/v2 profiles remain readable as
        // legacy pixel offsets and are never blended with normalized profiles.
        val scaledDeltas = if (learned.version >= 3) {
            CropDeltaNormalizer.toPixels(
                NormalizedCropDeltas(
                    learned.boundsDeltaLeft,
                    learned.boundsDeltaTop,
                    learned.boundsDeltaRight,
                    learned.boundsDeltaBottom,
                ),
                b.width(),
                b.height(),
            )
        } else {
            NormalizedCropDeltas(
                learned.boundsDeltaLeft,
                learned.boundsDeltaTop,
                learned.boundsDeltaRight,
                learned.boundsDeltaBottom,
            )
        }
        val deltaLeft = scaledDeltas.left
        val deltaRight = scaledDeltas.right
        val deltaTop = scaledDeltas.top
        val deltaBottom = scaledDeltas.bottom
        val learnedBounds = RectF(
            b.left + deltaLeft,
            b.top + deltaTop,
            b.right + deltaRight,
            b.bottom + deltaBottom,
        )
        // Validate the final blend too: a finite correction can still invert or
        // collapse a rectangle after it is applied to a small candidate.
        if (!isValidBounds(blendBounds(b, learnedBounds, strength))) {
            return LearnedApplication(candidate.bounds, AppearanceAdjustments(), 0f)
        }

        val appearance = blendAppearance(
            AppearanceAdjustments(),
            learned.appearance,
            strength,
        )

        return LearnedApplication(
            bounds = blendBounds(b, learnedBounds, strength),
            appearance = AppearanceTuning.clamp(appearance),
            blend = strength,
        )
    }

    private fun isSafeToApply(
        candidate: DetectionCandidate,
        learned: LearnedCorrection,
        conditionFeatures: ImageConditionFeatures?,
    ): Boolean {
        val bounds = candidate.bounds
        if (!isValidBounds(bounds)) return false
        if (learned.kind != candidate.kind) return false
        val candidateAspectRatio = bounds.height() / bounds.width().coerceAtLeast(1f)
        if (!CorrectionLearning.isCompatibleForApplication(
                profile = learned,
                conditionBrightness = conditionFeatures?.brightness,
                conditionContrast = conditionFeatures?.contrast,
                conditionSaturation = conditionFeatures?.saturation,
                conditionEdgeDensity = conditionFeatures?.edgeDensity,
                aspectRatio = conditionFeatures?.aspectRatio ?: candidateAspectRatio,
            )
        ) return false

        // A single correction is not enough to change future results unless
        // the detector itself reported very high confidence in that correction.
        if (learned.sampleCount < 2 && learned.confidence < 0.85f) return false
        if (learned.sampleCount < 1 || !learned.confidence.isFinite()) return false
        if (learned.confidence !in 0f..1f) return false

        val valuesAreFinite = listOf(
            learned.boundsDeltaLeft,
            learned.boundsDeltaTop,
            learned.boundsDeltaRight,
            learned.boundsDeltaBottom,
            learned.conditionAspectRatio,
            learned.conditionEdgeDensity,
            learned.appearance.brightness,
            learned.appearance.contrast,
            learned.appearance.saturation,
            learned.appearance.sharpness,
            learned.appearance.denoise,
            learned.appearance.backgroundCleanup,
        ).all { it.isFinite() }
        if (!valuesAreFinite || learned.conditionAspectRatio <= 0f) return false

        // Reject profiles whose individual edge corrections are implausibly
        // large relative to this crop. This prevents stale/corrupt profiles
        // from moving a crop far away from the detector's actual candidate.
        val maxHorizontalDelta = bounds.width() * MAX_EDGE_DELTA_RATIO
        val maxVerticalDelta = bounds.height() * MAX_EDGE_DELTA_RATIO
        val leftDelta = if (learned.version >= 3) learned.boundsDeltaLeft * bounds.width() else learned.boundsDeltaLeft
        val rightDelta = if (learned.version >= 3) learned.boundsDeltaRight * bounds.width() else learned.boundsDeltaRight
        val topDelta = if (learned.version >= 3) learned.boundsDeltaTop * bounds.height() else learned.boundsDeltaTop
        val bottomDelta = if (learned.version >= 3) learned.boundsDeltaBottom * bounds.height() else learned.boundsDeltaBottom
        if (if (learned.version >= 3) {
                abs(learned.boundsDeltaLeft) > MAX_EDGE_DELTA_RATIO ||
                    abs(learned.boundsDeltaRight) > MAX_EDGE_DELTA_RATIO ||
                    abs(learned.boundsDeltaTop) > MAX_EDGE_DELTA_RATIO ||
                    abs(learned.boundsDeltaBottom) > MAX_EDGE_DELTA_RATIO
            } else {
                abs(leftDelta) > maxHorizontalDelta ||
                    abs(rightDelta) > maxHorizontalDelta ||
                    abs(topDelta) > maxVerticalDelta ||
                    abs(bottomDelta) > maxVerticalDelta
            }
        ) return false

        val adjustedWidth = bounds.width() + rightDelta - leftDelta
        val adjustedHeight = bounds.height() + bottomDelta - topDelta
        return adjustedWidth >= bounds.width() * MIN_SIZE_RATIO &&
            adjustedWidth <= bounds.width() * MAX_SIZE_RATIO &&
            adjustedHeight >= bounds.height() * MIN_SIZE_RATIO &&
            adjustedHeight <= bounds.height() * MAX_SIZE_RATIO
    }

    private fun isValidBounds(bounds: RectF): Boolean =
        bounds.left.isFinite() && bounds.top.isFinite() &&
            bounds.right.isFinite() && bounds.bottom.isFinite() &&
            bounds.width() >= 1f && bounds.height() >= 1f

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
        inkThreshold = lerp(
            base.inkThreshold.toFloat(),
            learned.inkThreshold.toFloat(),
            strength,
        ).toInt(),
        backgroundCleanup = lerp(
            base.backgroundCleanup,
            learned.backgroundCleanup,
            strength,
        ),
    )

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    private const val MAX_EDGE_DELTA_RATIO = 0.35f
    private const val MIN_SIZE_RATIO = 0.50f
    private const val MAX_SIZE_RATIO = 1.50f
}
