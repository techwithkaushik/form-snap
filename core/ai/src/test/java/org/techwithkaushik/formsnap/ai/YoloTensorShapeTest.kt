package org.techwithkaushik.formsnap.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YoloTensorShapeTest {
    @Test
    fun calculatesElementCountForRawYoloOutput() {
        assertEquals(525 * 6, YoloTensorShape.expectedValueCount(intArrayOf(1, 525, 6)))
    }

    @Test
    fun calculatesElementCountForNmsOutput() {
        assertEquals(100 * 7, YoloTensorShape.expectedValueCount(intArrayOf(1, 100, 7)))
    }

    @Test
    fun rejectsUnsupportedRank() {
        assertNull(YoloTensorShape.expectedValueCount(intArrayOf(1, 100, 6, 1)))
    }

    @Test
    fun rejectsZeroOrNegativeDimensions() {
        assertNull(YoloTensorShape.expectedValueCount(intArrayOf(1, 0, 6)))
        assertNull(YoloTensorShape.expectedValueCount(intArrayOf(1, -5, 6)))
    }

    @Test
    fun rejectsElementCountThatWouldOverflowInt() {
        assertNull(YoloTensorShape.expectedValueCount(intArrayOf(1, Int.MAX_VALUE, 6)))
    }
}
