package org.techwithkaushik.formSnap.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CorrectionLearningTest {

    @Test
    fun rejectsNonFiniteAndInvalidLearningProfiles() {
        val valid = LearnedCorrection(kind = DetectionKind.PHOTO)

        assertTrue(CorrectionLearning.isSafe(valid))
        assertEquals(false, CorrectionLearning.isSafe(valid.copy(boundsDeltaLeft = Float.NaN)))
        assertEquals(false, CorrectionLearning.isSafe(valid.copy(appearance = valid.appearance.copy(contrast = Float.POSITIVE_INFINITY))))
        assertEquals(false, CorrectionLearning.isSafe(valid.copy(conditionAspectRatio = 0f)))
        assertEquals(false, CorrectionLearning.isSafe(valid.copy(confidence = 1.1f)))
        assertEquals(false, CorrectionLearning.isSafe(valid.copy(sampleCount = 0)))
        assertEquals(false, CorrectionLearning.isSafe(valid.copy(sampleCount = 101)))
        assertEquals(false, CorrectionLearning.isSafe(valid.copy(appearance = valid.appearance.copy(contrast = 2f))))
        assertEquals(false, CorrectionLearning.isSafe(valid.copy(appearance = valid.appearance.copy(inkThreshold = 300))))
        assertEquals(false, CorrectionLearning.isSafe(valid.copy(conditionAspectRatio = 100f)))
    }

    @Test
    fun learningProfilesOnlyMergeWhenCaptureConditionsAreSimilar() {
        val baseline = LearnedCorrection(
            kind = DetectionKind.PHOTO,
            conditionBrightness = 0.1f,
            conditionContrast = 1.1f,
            conditionSaturation = 0.9f,
            conditionEdgeDensity = 0.2f,
            conditionAspectRatio = 0.75f,
        )

        assertTrue(CorrectionLearning.sameConditionProfile(baseline, baseline.copy(confidence = 0.9f)))
        assertEquals(
            false,
            CorrectionLearning.sameConditionProfile(baseline, baseline.copy(conditionContrast = 1.5f)),
        )
        assertEquals(
            false,
            CorrectionLearning.sameConditionProfile(baseline, baseline.copy(conditionSaturation = 1.3f)),
        )
        assertEquals(
            false,
            CorrectionLearning.sameConditionProfile(baseline, baseline.copy(kind = DetectionKind.SIGNATURE)),
        )
    }

    @Test
    fun blendUsesPreviousSampleCountAsWeight() {
        val previous = LearnedCorrection(
            kind = DetectionKind.PHOTO,
            boundsDeltaLeft = 2f,
            boundsDeltaTop = 4f,
            appearance = AppearanceAdjustments(brightness = 0.2f, contrast = 1.2f),
            sampleCount = 3,
            confidence = 0.6f,
        )
        val incoming = LearnedCorrection(
            kind = DetectionKind.PHOTO,
            boundsDeltaLeft = 6f,
            boundsDeltaTop = 8f,
            appearance = AppearanceAdjustments(brightness = 0.6f, contrast = 0.8f),
            sampleCount = 1,
            confidence = 1f,
        )

        val blended = CorrectionLearning.blend(previous, incoming)

        assertEquals(3f, blended.boundsDeltaLeft, 0.0001f)
        assertEquals(5f, blended.boundsDeltaTop, 0.0001f)
        assertEquals(0.3f, blended.appearance.brightness, 0.0001f)
        assertEquals(1.1f, blended.appearance.contrast, 0.0001f)
        assertEquals(0.7f, blended.confidence, 0.0001f)
        assertEquals(4, blended.sampleCount)
        assertEquals(2, blended.version)
    }

    @Test
    fun blendKeepsConfidenceWithinValidRange() {
        val previous = LearnedCorrection(
            kind = DetectionKind.SIGNATURE,
            confidence = 0.95f,
            sampleCount = 2,
        )
        val incoming = previous.copy(confidence = 1.5f)

        val blended = CorrectionLearning.blend(previous, incoming)

        assertTrue(blended.confidence in 0f..1f)
        assertEquals(DetectionKind.SIGNATURE, blended.kind)
    }

    @Test
    fun blendCapsStoredSampleCountAtOneHundred() {
        val previous = LearnedCorrection(
            kind = DetectionKind.PHOTO,
            boundsDeltaLeft = 1f,
            sampleCount = 100,
        )
        val incoming = previous.copy(boundsDeltaLeft = 11f, sampleCount = 1)

        val blended = CorrectionLearning.blend(previous, incoming)

        assertEquals(100, blended.sampleCount)
        assertEquals((1f * 100f + 11f) / 101f, blended.boundsDeltaLeft, 0.0001f)
    }
}
