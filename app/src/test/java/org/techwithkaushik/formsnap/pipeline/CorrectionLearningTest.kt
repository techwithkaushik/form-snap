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
        assertEquals(false, CorrectionLearning.sameConditionProfile(baseline, baseline.copy(conditionContrast = 1.6f)))
        assertEquals(false, CorrectionLearning.sameConditionProfile(baseline, baseline.copy(conditionSaturation = 1.4f)))
        assertEquals(false, CorrectionLearning.sameConditionProfile(baseline, baseline.copy(kind = DetectionKind.SIGNATURE)))
    }

    @Test
    fun conditionSimilarityIgnoresFeaturesThatWereNotMeasured() {
        val profile = LearnedCorrection(
            kind = DetectionKind.PHOTO,
            conditionBrightness = 0.8f,
            conditionContrast = 2.5f,
            conditionSaturation = 2.2f,
            conditionEdgeDensity = 0.8f,
            conditionAspectRatio = 0.75f,
        )

        assertEquals(
            1f,
            CorrectionLearning.conditionSimilarity(profile, aspectRatio = 0.75f),
            0.0001f,
        )
        assertEquals(
            0f,
            CorrectionLearning.conditionSimilarity(profile, aspectRatio = 1.5f),
            0.0001f,
        )
    }

    @Test
    fun incompatibleCaptureShapeDoesNotReuseLearnedCorrection() {
        val profile = LearnedCorrection(
            kind = DetectionKind.PHOTO,
            conditionAspectRatio = 0.75f,
            sampleCount = 20,
            confidence = 0.98f,
        )

        assertTrue(
            CorrectionLearning.isCompatibleForApplication(profile, aspectRatio = 0.80f),
        )
        assertEquals(
            false,
            CorrectionLearning.isCompatibleForApplication(profile, aspectRatio = 1.5f),
        )
        assertEquals(
            false,
            CorrectionLearning.isCompatibleForApplication(
                profile,
                aspectRatio = 0.80f,
                minimumSimilarity = Float.NaN,
            ),
        )
    }

    @Test
    fun conditionSimilarityCombinesOnlyKnownFeaturesOnNormalizedScales() {
        val profile = LearnedCorrection(
            kind = DetectionKind.PHOTO,
            conditionBrightness = 0f,
            conditionContrast = 1f,
            conditionSaturation = 1f,
            conditionEdgeDensity = 0f,
            conditionAspectRatio = 1f,
        )

        assertEquals(
            0.75f,
            CorrectionLearning.conditionSimilarity(
                profile = profile,
                conditionBrightness = 1f,
                conditionEdgeDensity = 0f,
            ),
            0.0001f,
        )
        assertEquals(
            1f,
            CorrectionLearning.conditionSimilarity(profile),
            0.0001f,
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
    fun conditionSimilarityHandlesExtremeRatiosWithoutNumericTypeErrors() {
        val profile = LearnedCorrection(
            kind = DetectionKind.PHOTO,
            conditionAspectRatio = 1f,
        )
        assertEquals(
            0f,
            CorrectionLearning.conditionSimilarity(profile, aspectRatio = 100f),
            0.0001f,
        )
    }

    @Test
    fun weightedImportMergeUsesBothProfileSampleCounts() {
        val previous = LearnedCorrection(
            kind = DetectionKind.PHOTO,
            boundsDeltaLeft = 2f,
            sampleCount = 3,
            confidence = 0.6f,
        )
        val incoming = LearnedCorrection(
            kind = DetectionKind.PHOTO,
            boundsDeltaLeft = 10f,
            sampleCount = 1,
            confidence = 1f,
        )

        val merged = CorrectionLearning.mergeWeighted(previous, incoming)

        assertEquals(4f, merged.boundsDeltaLeft, 0.0001f)
        assertEquals(4, merged.sampleCount)
        assertEquals(0.7f, merged.confidence, 0.0001f)
    }

    @Test
    fun blendKeepsConfidenceWithinValidRange() {
        val previous = LearnedCorrection(
            kind = DetectionKind.SIGNATURE,
            confidence = 0.95f,
            sampleCount = 2,
        )
        val incoming = previous.copy(confidence = 1.5f, sampleCount = 1)

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

    @Test
    fun conditionSimilarityUsesMeasuredLightingAndTextureAlongsideAspectRatio() {
        val profile = LearnedCorrection(
            kind = DetectionKind.PHOTO,
            conditionBrightness = 0.5f,
            conditionContrast = 1.5f,
            conditionSaturation = 1.0f,
            conditionEdgeDensity = 0.2f,
            conditionAspectRatio = 0.75f,
        )

        val sameConditions = CorrectionLearning.conditionSimilarity(
            profile = profile,
            conditionBrightness = 0.5f,
            conditionContrast = 1.5f,
            conditionSaturation = 1.0f,
            conditionEdgeDensity = 0.2f,
            aspectRatio = 0.75f,
        )
        val differentConditions = CorrectionLearning.conditionSimilarity(
            profile = profile,
            conditionBrightness = -0.5f,
            conditionContrast = 3.5f,
            conditionSaturation = 3.0f,
            conditionEdgeDensity = 0.9f,
            aspectRatio = 0.75f,
        )

        assertEquals(1f, sameConditions, 0.0001f)
        assertTrue(differentConditions < sameConditions)
    }

    @Test
    fun missingMeasuredFeaturesDoNotPenalizeLegacyProfiles() {
        val legacy = LearnedCorrection(
            kind = DetectionKind.SIGNATURE,
            conditionBrightness = 0f,
            conditionContrast = 1f,
            conditionSaturation = 1f,
            conditionEdgeDensity = 0f,
            conditionAspectRatio = 2.5f,
        )

        assertEquals(
            1f,
            CorrectionLearning.conditionSimilarity(legacy, aspectRatio = 2.5f),
            0.0001f,
        )
    }

    @Test
    fun normalizedV3ProfileKeepsItsVersionAndNormalizedOffsetsWhenBlended() {
        val previous = LearnedCorrection(
            kind = DetectionKind.SIGNATURE,
            boundsDeltaLeft = 0.10f,
            boundsDeltaTop = -0.05f,
            boundsDeltaRight = 0.08f,
            boundsDeltaBottom = 0.04f,
            sampleCount = 3,
            confidence = 0.8f,
            version = 3,
        )
        val incoming = previous.copy(
            boundsDeltaLeft = 0.20f,
            boundsDeltaTop = 0.05f,
            boundsDeltaRight = 0.12f,
            boundsDeltaBottom = 0.08f,
            sampleCount = 1,
            confidence = 1f,
        )

        val blended = CorrectionLearning.blend(previous, incoming)

        assertEquals(3, blended.version)
        assertEquals(0.125f, blended.boundsDeltaLeft, 0.0001f)
        assertEquals(-0.025f, blended.boundsDeltaTop, 0.0001f)
        assertEquals(4, blended.sampleCount)
        assertTrue(CorrectionLearning.isSafe(blended))
    }

    @Test
    fun differentCoordinateVersionsCannotShareOneLearningProfile() {
        val legacy = LearnedCorrection(
            kind = DetectionKind.PHOTO,
            boundsDeltaLeft = 12f,
            version = 2,
        )
        val normalized = LearnedCorrection(
            kind = DetectionKind.PHOTO,
            boundsDeltaLeft = 0.12f,
            version = 3,
        )

        assertEquals(false, CorrectionLearning.sameConditionProfile(legacy, normalized))
        val failure = runCatching { CorrectionLearning.blend(legacy, normalized) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

}
