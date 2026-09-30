package org.techwithkaushik.formSnap.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutTopologyMatcherTest {
    private fun layout(
        photo: NormalizedLayoutBox?,
        signature: NormalizedLayoutBox?,
        aspect: Float = 0.7f,
    ) = LayoutTopologySignature(aspect, photo, signature)

    @Test
    fun matchingNormalizedPhotoAndSignaturePositionsPassConfidenceGate() {
        val expected = layout(
            photo = NormalizedLayoutBox(0.10f, 0.12f, 0.38f, 0.42f),
            signature = NormalizedLayoutBox(0.15f, 0.72f, 0.80f, 0.84f),
        )
        val actual = layout(
            photo = NormalizedLayoutBox(0.11f, 0.12f, 0.39f, 0.42f),
            signature = NormalizedLayoutBox(0.16f, 0.72f, 0.81f, 0.84f),
            aspect = 0.705f,
        )

        val score = LayoutTopologyMatcher.similarity(expected, actual, DetectionKind.PHOTO)

        assertTrue(score >= LayoutTopologyMatcher.MIN_MATCH_CONFIDENCE)
    }

    @Test
    fun unrelatedCandidatePositionDoesNotPassConfidenceGate() {
        val expected = layout(
            photo = NormalizedLayoutBox(0.05f, 0.10f, 0.30f, 0.40f),
            signature = NormalizedLayoutBox(0.10f, 0.72f, 0.80f, 0.84f),
        )
        val actual = layout(
            photo = NormalizedLayoutBox(0.65f, 0.12f, 0.95f, 0.42f),
            signature = NormalizedLayoutBox(0.10f, 0.72f, 0.80f, 0.84f),
        )

        val score = LayoutTopologyMatcher.similarity(expected, actual, DetectionKind.PHOTO)

        assertTrue(score < LayoutTopologyMatcher.MIN_MATCH_CONFIDENCE)
    }

    @Test
    fun missingCounterpartIsPenalizedRatherThanAssumedEquivalent() {
        val expected = layout(
            photo = NormalizedLayoutBox(0.10f, 0.10f, 0.35f, 0.45f),
            signature = NormalizedLayoutBox(0.10f, 0.75f, 0.75f, 0.86f),
        )
        val actual = layout(
            photo = NormalizedLayoutBox(0.10f, 0.10f, 0.35f, 0.45f),
            signature = null,
        )

        val score = LayoutTopologyMatcher.similarity(expected, actual, DetectionKind.PHOTO)

        assertTrue(score < LayoutTopologyMatcher.MIN_MATCH_CONFIDENCE)
    }

    @Test
    fun exactSignatureMatchesAtOne() {
        val signature = layout(
            photo = NormalizedLayoutBox(0.12f, 0.10f, 0.38f, 0.44f),
            signature = null,
        )

        assertEquals(
            1f,
            LayoutTopologyMatcher.similarity(signature, signature, DetectionKind.PHOTO),
            0.0001f,
        )
    }

    @Test
    fun normalizedDeltasScaleWithCandidateResolution() {
        val deltas = NormalizedCropDeltas(-0.1f, -0.1f, 0.1f, 0.1f)

        val small = CropDeltaNormalizer.toPixels(deltas, width = 100f, height = 100f)
        val large = CropDeltaNormalizer.toPixels(deltas, width = 200f, height = 200f)

        assertEquals(-10f, small.left, 0.001f)
        assertEquals(-20f, large.left, 0.001f)
        assertEquals(10f, small.right, 0.001f)
        assertEquals(20f, large.right, 0.001f)
    }

}
