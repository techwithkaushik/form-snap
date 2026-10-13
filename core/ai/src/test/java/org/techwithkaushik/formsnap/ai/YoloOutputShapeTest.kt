package org.techwithkaushik.formsnap.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YoloOutputShapeTest {
    @Test
    fun recognizesSmallSixChannelRawYoloOutput() {
        val input = intArrayOf(1, 160, 160, 3)
        val output = intArrayOf(1, 525, 6)

        assertFalse(YoloOutputShape.isLikelyNmsOutput(output, input))
        assertEquals(2, YoloOutputShape.rawClassCount(output, input))
    }

    @Test
    fun recognizesChannelFirstSmallRawYoloOutput() {
        val input = intArrayOf(1, 3, 160, 160)
        val output = intArrayOf(1, 6, 525)

        assertFalse(YoloOutputShape.isLikelyNmsOutput(output, input))
        assertEquals(2, YoloOutputShape.rawClassCount(output, input))
    }

    @Test
    fun recognizesSmallPostNmsOutput() {
        val input = intArrayOf(1, 640, 640, 3)
        val output = intArrayOf(1, 100, 6)

        assertTrue(YoloOutputShape.isLikelyNmsOutput(output, input))
        assertNull(YoloOutputShape.rawClassCount(output, input))
    }

    @Test
    fun infersClassCountForStandardLargeRawYoloOutput() {
        assertEquals(
            80,
            YoloOutputShape.rawClassCount(
                intArrayOf(1, 84, 8400),
                intArrayOf(1, 640, 640, 3),
            ),
        )
    }
    @Test
    fun recognizesRectangularRawYoloOutput() {
        // 240x160 input: 30*20 + 15*10 + 8*5 = 790 candidates.
        val input = intArrayOf(1, 160, 240, 3)
        val output = intArrayOf(1, 790, 6)

        assertFalse(YoloOutputShape.isLikelyNmsOutput(output, input))
        assertEquals(2, YoloOutputShape.rawClassCount(output, input))
    }

    @Test
    fun recognizesChannelFirstRectangularRawYoloOutput() {
        val input = intArrayOf(1, 3, 160, 240)
        val output = intArrayOf(1, 6, 790)

        assertFalse(YoloOutputShape.isLikelyNmsOutput(output, input))
        assertEquals(2, YoloOutputShape.rawClassCount(output, input))
    }


    @Test
    fun recognizesPostNmsOutputForRectangularInput() {
        val input = intArrayOf(1, 160, 240, 3)
        val output = intArrayOf(1, 100, 6)

        assertTrue(YoloOutputShape.isLikelyNmsOutput(output, input))
        assertNull(YoloOutputShape.rawClassCount(output, input))
    }

    @Test
    fun recognizesSingleClassRawYoloOutput() {
        val input = intArrayOf(1, 320, 320, 3)
        val output = intArrayOf(1, 2100, 5)

        assertFalse(YoloOutputShape.isLikelyNmsOutput(output, input))
        assertEquals(1, YoloOutputShape.rawClassCount(output, input))
    }

    @Test
    fun rejectsUnsupportedOutputRankForRawClassInference() {
        assertNull(
            YoloOutputShape.rawClassCount(
                intArrayOf(1, 100, 6, 1),
                intArrayOf(1, 160, 160, 3),
            ),
        )
    }

}
