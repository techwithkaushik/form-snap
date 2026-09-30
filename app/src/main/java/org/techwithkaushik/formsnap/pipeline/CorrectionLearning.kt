package org.techwithkaushik.formSnap.pipeline

import kotlin.math.abs
import kotlin.math.ln

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
    val conditionBrightness: Float = 0f,
    val conditionContrast: Float = 1f,
    val conditionSaturation: Float = 1f,
    val conditionEdgeDensity: Float = 0f,
    val conditionAspectRatio: Float = 1f,
    val sampleCount: Int = 1,
    val confidence: Float = 0.5f,
    val version: Int = 2,
)

object CorrectionLearning {
    /**
     * Returns true only when two corrections describe sufficiently similar
     * capture conditions to be safely averaged into one learning profile.
     */
    fun sameConditionProfile(first: LearnedCorrection, second: LearnedCorrection): Boolean =
        first.kind == second.kind &&
            first.version == second.version &&
            close(first.conditionAspectRatio, second.conditionAspectRatio, 0.15f) &&
            close(first.conditionBrightness, second.conditionBrightness, 0.15f) &&
            close(first.conditionContrast, second.conditionContrast, 0.20f) &&
            close(first.conditionSaturation, second.conditionSaturation, 0.20f) &&
            close(first.conditionEdgeDensity, second.conditionEdgeDensity, 0.15f)

    private fun close(first: Float, second: Float, tolerance: Float): Boolean =
        abs(first - second) <= tolerance

    /**
     * Returns a normalized similarity in [0, 1] for the conditions known by the
     * caller. Aspect ratio uses a logarithmic ratio so a 2x and 1/2x mismatch
     * are treated symmetrically. Missing features are excluded, not guessed.
     */
    fun conditionSimilarity(
        profile: LearnedCorrection,
        conditionBrightness: Float? = null,
        conditionContrast: Float? = null,
        conditionSaturation: Float? = null,
        conditionEdgeDensity: Float? = null,
        aspectRatio: Float? = null,
    ): Float {
        val distances = buildList {
            conditionBrightness?.let {
                add(normalizedDistance(profile.conditionBrightness, it, 2f))
            }
            conditionContrast?.let {
                add(normalizedDistance(profile.conditionContrast, it, 4f))
            }
            conditionSaturation?.let {
                add(normalizedDistance(profile.conditionSaturation, it, 4f))
            }
            conditionEdgeDensity?.let {
                add(normalizedDistance(profile.conditionEdgeDensity, it, 1f))
            }
            aspectRatio?.takeIf { it.isFinite() && it > 0f }?.let {
                val ratioDistance = (
                    abs(ln(profile.conditionAspectRatio.toDouble() / it.toDouble())) / ln(2.0)
                    ).toFloat()
                add(ratioDistance.coerceIn(0f, 1f))
            }
        }
        if (distances.isEmpty()) return 1f
        return (1f - distances.average().toFloat()).coerceIn(0f, 1f)
    }

    /**
     * Prevents applying a high-confidence correction learned from a materially
     * different capture shape or environment.
     */
    fun isCompatibleForApplication(
        profile: LearnedCorrection,
        conditionBrightness: Float? = null,
        conditionContrast: Float? = null,
        conditionSaturation: Float? = null,
        conditionEdgeDensity: Float? = null,
        aspectRatio: Float? = null,
        minimumSimilarity: Float = 0.35f,
    ): Boolean {
        if (!minimumSimilarity.isFinite() || minimumSimilarity !in 0f..1f) return false
        return conditionSimilarity(
            profile = profile,
            conditionBrightness = conditionBrightness,
            conditionContrast = conditionContrast,
            conditionSaturation = conditionSaturation,
            conditionEdgeDensity = conditionEdgeDensity,
            aspectRatio = aspectRatio,
        ) >= minimumSimilarity
    }

    private fun normalizedDistance(first: Float, second: Float, scale: Float): Float =
        (abs(first - second) / scale).coerceIn(0f, 1f)

    /**
     * Validates data at the learning-store boundary, not just at the UI feedback
     * boundary. This prevents NaN/Infinity values from poisoning future blends.
     */
    fun isSafe(correction: LearnedCorrection): Boolean {
        val numericValues = listOf(
            correction.boundsDeltaLeft,
            correction.boundsDeltaTop,
            correction.boundsDeltaRight,
            correction.boundsDeltaBottom,
            correction.appearance.brightness,
            correction.appearance.contrast,
            correction.appearance.saturation,
            correction.appearance.sharpness,
            correction.appearance.denoise,
            correction.appearance.backgroundCleanup,
            correction.conditionBrightness,
            correction.conditionContrast,
            correction.conditionSaturation,
            correction.conditionEdgeDensity,
            correction.conditionAspectRatio,
            correction.confidence,
        )
        if (numericValues.any { !it.isFinite() }) return false
        if (correction.conditionAspectRatio <= 0f) return false
        if (correction.sampleCount !in 1..100) return false
        if (correction.version !in 1..3) return false
        if (correction.confidence !in 0f..1f) return false
        if (correction.conditionAspectRatio !in 0.05f..20f) return false
        val deltaLimit = if (correction.version >= 3) 0.35f else 1_000_000f
        if (listOf(
                correction.boundsDeltaLeft,
                correction.boundsDeltaTop,
                correction.boundsDeltaRight,
                correction.boundsDeltaBottom,
            ).any { abs(it) > deltaLimit }
        ) return false
        val appearance = correction.appearance
        if (appearance.brightness !in -0.5f..0.5f) return false
        if (appearance.contrast !in 0.7f..1.5f) return false
        if (appearance.saturation !in 0.5f..1.5f) return false
        if (appearance.sharpness !in 0f..1f) return false
        if (appearance.denoise !in 0f..1f) return false
        if (appearance.backgroundCleanup !in 0f..1f) return false
        if (appearance.inkThreshold !in 80..220) return false
        if (correction.conditionBrightness !in -1f..1f) return false
        if (correction.conditionContrast !in 0f..4f) return false
        if (correction.conditionSaturation !in 0f..4f) return false
        if (correction.conditionEdgeDensity !in 0f..1f) return false
        return true
    }

    fun fromCorrection(
        automatic: DetectionCandidate,
        correctedBounds: android.graphics.RectF?,
        appearance: AppearanceAdjustments,
        sourceBrightness: Float = 0f,
        sourceContrast: Float = 1f,
        sourceSaturation: Float = 1f,
        sourceEdgeDensity: Float = 0f,
    ): LearnedCorrection {
        val corrected = correctedBounds ?: automatic.bounds
        val width = automatic.bounds.width().coerceAtLeast(1f)
        val height = automatic.bounds.height().coerceAtLeast(1f)
        val deltas = CropDeltaNormalizer.normalize(
            leftPixels = corrected.left - automatic.bounds.left,
            topPixels = corrected.top - automatic.bounds.top,
            rightPixels = corrected.right - automatic.bounds.right,
            bottomPixels = corrected.bottom - automatic.bounds.bottom,
            width = width,
            height = height,
        )
        return LearnedCorrection(
            kind = automatic.kind,
            // Version 3 stores edge offsets as fractions of the detected crop,
            // not absolute pixels, so feedback remains valid across resolutions.
            boundsDeltaLeft = deltas.left,
            boundsDeltaTop = deltas.top,
            boundsDeltaRight = deltas.right,
            boundsDeltaBottom = deltas.bottom,
            appearance = appearance,
            conditionBrightness = sourceBrightness,
            conditionContrast = sourceContrast,
            conditionSaturation = sourceSaturation,
            conditionEdgeDensity = sourceEdgeDensity,
            conditionAspectRatio = automatic.bounds.height() / width,
            sampleCount = 1,
            confidence = automatic.confidence,
            version = 3,
        )
    }

    fun blend(previous: LearnedCorrection, incoming: LearnedCorrection): LearnedCorrection {
        require(previous.kind == incoming.kind && previous.version == incoming.version) {
            "Only profiles with the same detection kind and coordinate version can be blended"
        }
        require(incoming.sampleCount == 1) {
            "blend() accepts one new sample; use mergeWeighted() for aggregate profiles"
        }
        val oldWeight = previous.sampleCount.toFloat().coerceAtLeast(1f)
        val total = oldWeight + 1f

        fun avg(a: Float, b: Float): Float = (a * oldWeight + b) / total
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
            conditionBrightness = avg(previous.conditionBrightness, incoming.conditionBrightness),
            conditionContrast = avg(previous.conditionContrast, incoming.conditionContrast),
            conditionSaturation = avg(previous.conditionSaturation, incoming.conditionSaturation),
            conditionEdgeDensity = avg(previous.conditionEdgeDensity, incoming.conditionEdgeDensity),
            conditionAspectRatio = avg(previous.conditionAspectRatio, incoming.conditionAspectRatio),
            sampleCount = (previous.sampleCount + 1).coerceAtMost(100),
            confidence = avg(previous.confidence, incoming.confidence).coerceIn(0f, 1f),
            // sameConditionProfile requires equal versions. Preserve that version:
            // v3 deltas are normalized fractions, while v1/v2 deltas are pixels.
            // Downgrading v3 here would reinterpret normalized fractions as pixels.
            version = previous.version,
        )
    }
    /**
     * Merges aggregate profiles using both source sample counts, as needed when
     * learning bundles are imported from another installation.
     */
    fun mergeWeighted(previous: LearnedCorrection, incoming: LearnedCorrection): LearnedCorrection {
        require(previous.kind == incoming.kind && previous.version == incoming.version) {
            "Only profiles with the same detection kind and coordinate version can be merged"
        }
        val oldWeight = previous.sampleCount.coerceAtLeast(1).toDouble()
        val newWeight = incoming.sampleCount.coerceAtLeast(1).toDouble()
        val total = oldWeight + newWeight
        fun avg(a: Float, b: Float): Float =
            ((a.toDouble() * oldWeight + b.toDouble() * newWeight) / total).toFloat()
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
            conditionBrightness = avg(previous.conditionBrightness, incoming.conditionBrightness),
            conditionContrast = avg(previous.conditionContrast, incoming.conditionContrast),
            conditionSaturation = avg(previous.conditionSaturation, incoming.conditionSaturation),
            conditionEdgeDensity = avg(previous.conditionEdgeDensity, incoming.conditionEdgeDensity),
            conditionAspectRatio = avg(previous.conditionAspectRatio, incoming.conditionAspectRatio),
            sampleCount = (previous.sampleCount.toLong() + incoming.sampleCount)
                .coerceAtMost(100L).toInt(),
            confidence = avg(previous.confidence, incoming.confidence).coerceIn(0f, 1f),
            version = maxOf(previous.version, incoming.version, 2),
        )
    }
}
