package org.techwithkaushik.formSnap.pipeline

import org.junit.Assert.assertEquals
import org.junit.Test

class OutputNormalizerTest {
    @Test
    fun fitDimensionsPreservesLandscapeAspectRatioInsidePortraitOutput() {
        val fit = fitDimensions(
            sourceWidth = 400,
            sourceHeight = 300,
            targetWidth = 400,
            targetHeight = 500,
        )

        assertEquals(400, fit.width)
        assertEquals(300, fit.height)
    }

    @Test
    fun fitDimensionsPreservesPortraitAspectRatioInsideLandscapeOutput() {
        val fit = fitDimensions(
            sourceWidth = 300,
            sourceHeight = 400,
            targetWidth = 500,
            targetHeight = 200,
        )

        assertEquals(150, fit.width)
        assertEquals(200, fit.height)
    }

    @Test(expected = IllegalArgumentException::class)
    fun fitDimensionsRejectsEmptySource() {
        fitDimensions(0, 100, 200, 200)
    }

    @Test(expected = IllegalArgumentException::class)
    fun fitDimensionsRejectsEmptyTarget() {
        fitDimensions(100, 100, 0, 200)
    }
}
