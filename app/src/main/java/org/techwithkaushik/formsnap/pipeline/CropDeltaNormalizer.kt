package org.techwithkaushik.formSnap.pipeline

/**
 * Resolution-independent crop edge offsets. Horizontal deltas are fractions of
 * crop width; vertical deltas are fractions of crop height.
 */
data class NormalizedCropDeltas(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

object CropDeltaNormalizer {
    fun normalize(
        leftPixels: Float,
        topPixels: Float,
        rightPixels: Float,
        bottomPixels: Float,
        width: Float,
        height: Float,
    ): NormalizedCropDeltas {
        require(width.isFinite() && width > 0f)
        require(height.isFinite() && height > 0f)
        return NormalizedCropDeltas(
            left = leftPixels / width,
            top = topPixels / height,
            right = rightPixels / width,
            bottom = bottomPixels / height,
        )
    }

    fun toPixels(deltas: NormalizedCropDeltas, width: Float, height: Float): NormalizedCropDeltas {
        require(width.isFinite() && width > 0f)
        require(height.isFinite() && height > 0f)
        return NormalizedCropDeltas(
            left = deltas.left * width,
            top = deltas.top * height,
            right = deltas.right * width,
            bottom = deltas.bottom * height,
        )
    }
}
