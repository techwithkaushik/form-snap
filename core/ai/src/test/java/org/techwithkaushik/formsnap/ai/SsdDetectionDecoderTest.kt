package org.techwithkaushik.formsnap.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SsdDetectionDecoderTest {
    private val transform = LetterboxTransform(
        scale = 1f,
        padX = 0f,
        padY = 0f,
        sourceWidth = 100,
        sourceHeight = 100,
        inputSize = 100,
        inputWidth = 100,
        inputHeight = 100,
    )
    private val shapes = listOf(
        intArrayOf(1, 2, 4),
        intArrayOf(1, 2),
        intArrayOf(1, 2),
        intArrayOf(1),
    )

    @Test
    fun decodesBoxesClassesScoresAndCountInCanonicalOrder() {
        val result = SsdDetectionDecoder.decode(
            shapes,
            listOf(
                floatArrayOf(0.1f, 0.2f, 0.7f, 0.8f, 0f, 0f, 0f, 0f),
                floatArrayOf(1f, 0f),
                floatArrayOf(0.91f, 0.2f),
                floatArrayOf(1f),
            ),
            transform,
            listOf("background", "PHOTO"),
            confidenceThreshold = 0.3f,
            maxDetections = 10,
        )
        assertEquals(1, result.size)
        assertEquals(1, result.single().classId)
        assertEquals("PHOTO", result.single().label)
        assertEquals(0.91f, result.single().confidence, 0.0001f)
        assertEquals(20f, result.single().boundingBox.left, 0.001f)
        assertEquals(10f, result.single().boundingBox.top, 0.001f)
        assertEquals(80f, result.single().boundingBox.right, 0.001f)
        assertEquals(70f, result.single().boundingBox.bottom, 0.001f)
    }

    @Test
    fun skipsInvalidClassesScoresAndDegenerateBoxes() {
        val result = SsdDetectionDecoder.decode(
            shapes,
            listOf(
                floatArrayOf(0.1f, 0.2f, 0.7f, 0.8f, 0.4f, 0.4f, 0.4f, 0.4f),
                floatArrayOf(0f, 0f),
                floatArrayOf(Float.NaN, 0.99f),
                floatArrayOf(2f),
            ),
            transform,
            emptyList(),
            confidenceThreshold = 0f,
            maxDetections = 10,
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun limitsResultsAndUsesGenericLabelsWhenNoLabelsProvided() {
        val result = SsdDetectionDecoder.decode(
            shapes,
            listOf(
                floatArrayOf(0.1f, 0.1f, 0.4f, 0.4f, 0.5f, 0.5f, 0.9f, 0.9f),
                floatArrayOf(2f, 3f),
                floatArrayOf(0.8f, 0.9f),
                floatArrayOf(2f),
            ),
            transform,
            emptyList(),
            confidenceThreshold = 0.1f,
            maxDetections = 1,
        )
        assertEquals(1, result.size)
        assertEquals("Class 3", result.single().label)
    }
}
