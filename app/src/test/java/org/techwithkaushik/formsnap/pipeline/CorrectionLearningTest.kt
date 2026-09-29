package org.techwithkaushik.formSnap.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CorrectionLearningTest {

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
