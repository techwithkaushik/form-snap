package org.techwithkaushik.formSnap.pipeline

import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/** A rectangle expressed in source-image normalized coordinates [0,1]. */
data class NormalizedLayoutBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val aspectRatio: Float get() = height / width.coerceAtLeast(0.0001f)

    fun isValid(): Boolean =
        listOf(left, top, right, bottom).all(Float::isFinite) &&
            left in 0f..1f && top in 0f..1f &&
            right in 0f..1f && bottom in 0f..1f &&
            right > left && bottom > top
}

/**
 * Compact first-release topology signature. It captures image aspect and the
 * normalized locations/sizes of independently detected photo/signature regions.
 * It deliberately avoids retaining source pixels or personal form text.
 */
data class LayoutTopologySignature(
    val imageAspectRatio: Float,
    val photo: NormalizedLayoutBox?,
    val signature: NormalizedLayoutBox?,
) {
    fun isValid(): Boolean =
        imageAspectRatio.isFinite() && imageAspectRatio in 0.05f..20f &&
            (photo == null || photo.isValid()) &&
            (signature == null || signature.isValid())
}

data class LayoutCorrectionProfile(
    val kind: DetectionKind,
    val signature: LayoutTopologySignature,
    val deltas: NormalizedCropDeltas,
    val sampleCount: Int = 1,
    val confidence: Float = 1f,
)

object LayoutTopologyMatcher {
    const val MIN_MATCH_CONFIDENCE = 0.75f

    fun signature(
        sourceWidth: Int,
        sourceHeight: Int,
        photoBounds: RectF?,
        signatureBounds: RectF?,
    ): LayoutTopologySignature? {
        if (sourceWidth <= 0 || sourceHeight <= 0) return null
        fun normalize(bounds: RectF?): NormalizedLayoutBox? {
            bounds ?: return null
            val box = NormalizedLayoutBox(
                (bounds.left / sourceWidth).coerceIn(0f, 1f),
                (bounds.top / sourceHeight).coerceIn(0f, 1f),
                (bounds.right / sourceWidth).coerceIn(0f, 1f),
                (bounds.bottom / sourceHeight).coerceIn(0f, 1f),
            )
            return box.takeIf(NormalizedLayoutBox::isValid)
        }
        return LayoutTopologySignature(
            imageAspectRatio = sourceWidth.toFloat() / sourceHeight.toFloat(),
            photo = normalize(photoBounds),
            signature = normalize(signatureBounds),
        ).takeIf(LayoutTopologySignature::isValid)
    }

    fun similarity(
        expected: LayoutTopologySignature,
        actual: LayoutTopologySignature,
        kind: DetectionKind,
    ): Float {
        if (!expected.isValid() || !actual.isValid()) return 0f
        val expectedTarget = boxFor(expected, kind) ?: return 0f
        val actualTarget = boxFor(actual, kind) ?: return 0f
        val targetScore = boxSimilarity(expectedTarget, actualTarget)
        val otherKind = if (kind == DetectionKind.PHOTO) DetectionKind.SIGNATURE else DetectionKind.PHOTO
        val expectedOther = boxFor(expected, otherKind)
        val actualOther = boxFor(actual, otherKind)
        val contextScore = when {
            expectedOther == null && actualOther == null -> 1f
            expectedOther == null || actualOther == null -> 0.25f
            else -> boxSimilarity(expectedOther, actualOther)
        }
        val aspectScore = exp(
            -abs(ln(expected.imageAspectRatio.toDouble() / actual.imageAspectRatio.toDouble())) / ln(2.0),
        ).toFloat().coerceIn(0f, 1f)
        return (targetScore * 0.65f + contextScore * 0.25f + aspectScore * 0.10f)
            .coerceIn(0f, 1f)
    }

    fun apply(
        bounds: RectF,
        profile: LayoutCorrectionProfile,
        actualSignature: LayoutTopologySignature,
        sourceWidth: Int,
        sourceHeight: Int,
    ): RectF? {
        val score = similarity(profile.signature, actualSignature, profile.kind)
        if (score < MIN_MATCH_CONFIDENCE || !profile.confidence.isFinite() ||
            profile.confidence !in 0f..1f || profile.sampleCount < 1
        ) return null
        if (!listOf(
                profile.deltas.left, profile.deltas.top,
                profile.deltas.right, profile.deltas.bottom,
            ).all(Float::isFinite)
        ) return null
        if (listOf(
                profile.deltas.left, profile.deltas.top,
                profile.deltas.right, profile.deltas.bottom,
            ).any { abs(it) > 0.35f }
        ) return null

        val pixels = CropDeltaNormalizer.toPixels(
            profile.deltas,
            bounds.width().coerceAtLeast(1f),
            bounds.height().coerceAtLeast(1f),
        )
        val adjusted = RectF(
            bounds.left + pixels.left,
            bounds.top + pixels.top,
            bounds.right + pixels.right,
            bounds.bottom + pixels.bottom,
        )
        adjusted.left = adjusted.left.coerceIn(0f, sourceWidth - 1f)
        adjusted.top = adjusted.top.coerceIn(0f, sourceHeight - 1f)
        adjusted.right = adjusted.right.coerceIn(adjusted.left + 1f, sourceWidth.toFloat())
        adjusted.bottom = adjusted.bottom.coerceIn(adjusted.top + 1f, sourceHeight.toFloat())
        return adjusted.takeIf { it.width() >= bounds.width() * 0.5f &&
            it.height() >= bounds.height() * 0.5f &&
            it.width() <= bounds.width() * 1.5f &&
            it.height() <= bounds.height() * 1.5f
        }
    }

    private fun boxFor(signature: LayoutTopologySignature, kind: DetectionKind): NormalizedLayoutBox? =
        if (kind == DetectionKind.PHOTO) signature.photo else signature.signature

    private fun boxSimilarity(a: NormalizedLayoutBox, b: NormalizedLayoutBox): Float {
        val distance = (
            abs(a.left - b.left) + abs(a.top - b.top) +
                abs(a.right - b.right) + abs(a.bottom - b.bottom)
            ) / 4f
        return (1f - distance * 3.0f).coerceIn(0f, 1f)
    }
}
