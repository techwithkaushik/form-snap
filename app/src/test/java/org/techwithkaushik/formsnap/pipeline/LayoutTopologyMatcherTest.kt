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
    fun normalizedCorrectionScalesWithCandidateResolution() {
        val signature = layout(
            photo = NormalizedLayoutBox(0.10f, 0.10f, 0.30f, 0.30f),
            signature = NormalizedLayoutBox(0.10f, 0.70f, 0.80f, 0.85f),
        )
        val profile = LayoutCorrectionProfile(
            kind = DetectionKind.PHOTO,
            signature = signature,
            deltas = NormalizedCropDeltas(-0.1f, -0.1f, 0.1f, 0.1f),
            sampleCount = 2,
            confidence = 1f,
        )

        val corrected = LayoutTopologyMatcher.apply(
            bounds = android.graphics.RectF(100f, 100f, 300f, 300f),
            profile = profile,
            actualSignature = signature,
            sourceWidth = 1000,
            sourceHeight = 1000,
        )

        requireNotNull(corrected)
        assertEquals(80f, corrected.left, 0.001f)
        assertEquals(80f, corrected.top, 0.001f)
        assertEquals(320f, corrected.right, 0.001f)
        assertEquals(320f, corrected.bottom, 0.001f)
    }

    @Test
    fun correctionIsNotAppliedWhenLayoutCounterpartIsMissing() {
        val expected = layout(
            photo = NormalizedLayoutBox(0.10f, 0.10f, 0.30f, 0.30f),
            signature = NormalizedLayoutBox(0.10f, 0.70f, 0.80f, 0.85f),
        )
        val actual = layout(
            photo = NormalizedLayoutBox(0.10f, 0.10f, 0.30f, 0.30f),
            signature = null,
        )
        val profile = LayoutCorrectionProfile(
            kind = DetectionKind.PHOTO,
            signature = expected,
            deltas = NormalizedCropDeltas(-0.1f, -0.1f, 0.1f, 0.1f),
        )

        val corrected = LayoutTopologyMatcher.apply(
            bounds = android.graphics.RectF(100f, 100f, 300f, 300f),
            profile = profile,
            actualSignature = actual,
            sourceWidth = 1000,
            sourceHeight = 1000,
        )

        assertEquals(null, corrected)
    }
}
