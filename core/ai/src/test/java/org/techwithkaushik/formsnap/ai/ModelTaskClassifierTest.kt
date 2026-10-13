package org.techwithkaushik.formsnap.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelTaskClassifierTest {
    @Test
    fun recognizesCommonClassificationOutput() {
        assertEquals(
            ModelTask.IMAGE_CLASSIFICATION,
            ModelTaskClassifier.classify(
                intArrayOf(1, 224, 224, 3),
                intArrayOf(1, 631),
            ),
        )
    }

    @Test
    fun recognizesClassificationWithSingletonSpatialDimensions() {
        assertEquals(
            ModelTask.IMAGE_CLASSIFICATION,
            ModelTaskClassifier.classify(
                intArrayOf(1, 224, 224, 3),
                intArrayOf(1, 1, 1, 631),
            ),
        )
    }

    @Test
    fun recognizesYoloObjectDetectionOutput() {
        assertEquals(
            ModelTask.OBJECT_DETECTION,
            ModelTaskClassifier.classify(
                intArrayOf(1, 640, 640, 3),
                intArrayOf(1, 6, 8400),
            ),
        )
    }

    @Test
    fun rejectsUnknownOutputInsteadOfGuessing() {
        assertEquals(
            ModelTask.UNSUPPORTED,
            ModelTaskClassifier.classify(
                intArrayOf(1, 224, 224, 3),
                intArrayOf(1, 20, 20, 64),
            ),
        )
    }

    @Test
    fun rejectsNonImageInput() {
        assertEquals(
            ModelTask.UNSUPPORTED,
            ModelTaskClassifier.classify(
                intArrayOf(1, 128),
                intArrayOf(1, 631),
            ),
        )
    }
}
