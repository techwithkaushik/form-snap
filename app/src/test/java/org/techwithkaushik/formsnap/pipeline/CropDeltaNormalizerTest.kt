package org.techwithkaushik.formSnap.pipeline

import org.junit.Assert.assertEquals
import org.junit.Test

class CropDeltaNormalizerTest {
    @Test
    fun normalizesEdgeOffsetsRelativeToCropDimensions() {
        val normalized = CropDeltaNormalizer.normalize(
            leftPixels = 10f,
            topPixels = 20f,
            rightPixels = -5f,
            bottomPixels = 40f,
            width = 100f,
            height = 200f,
        )

        assertEquals(0.10f, normalized.left, 0.0001f)
        assertEquals(0.10f, normalized.top, 0.0001f)
        assertEquals(-0.05f, normalized.right, 0.0001f)
        assertEquals(0.20f, normalized.bottom, 0.0001f)
    }

    @Test
    fun reusesLearnedOffsetsAtDifferentResolution() {
        val normalized = CropDeltaNormalizer.normalize(
            leftPixels = 10f,
            topPixels = 20f,
            rightPixels = -5f,
            bottomPixels = 40f,
            width = 100f,
            height = 200f,
        )

        val scaled = CropDeltaNormalizer.toPixels(normalized, width = 200f, height = 400f)

        assertEquals(20f, scaled.left, 0.0001f)
        assertEquals(40f, scaled.top, 0.0001f)
        assertEquals(-10f, scaled.right, 0.0001f)
        assertEquals(80f, scaled.bottom, 0.0001f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsZeroCropWidth() {
        CropDeltaNormalizer.normalize(1f, 1f, 1f, 1f, width = 0f, height = 10f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonFiniteCropHeight() {
        CropDeltaNormalizer.toPixels(
            NormalizedCropDeltas(0f, 0f, 0f, 0f),
            width = 10f,
            height = Float.NaN,
        )
    }
}
