package org.techwithkaushik.formsnap.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SsdOutputContractTest {
    @Test
    fun recognizesCommonDetectionPostProcessOutputs() {
        assertTrue(
            SsdOutputContract.isLikelyDetectionPostProcess(
                listOf(
                    intArrayOf(1, 10, 4),
                    intArrayOf(1, 10),
                    intArrayOf(1, 10),
                    intArrayOf(1),
                ),
            ),
        )
    }

    @Test
    fun recognizesScalarCountOutput() {
        assertTrue(
            SsdOutputContract.isLikelyDetectionPostProcess(
                listOf(
                    intArrayOf(1, 25),
                    intArrayOf(1, 25, 4),
                    intArrayOf(1, 25),
                    intArrayOf(1),
                ),
            ),
        )
    }

    @Test
    fun rejectsYoloSingleOutputAndUnrelatedMultiOutputShapes() {
        assertFalse(SsdOutputContract.isLikelyDetectionPostProcess(listOf(intArrayOf(1, 6, 8400))))
        assertFalse(
            SsdOutputContract.isLikelyDetectionPostProcess(
                listOf(intArrayOf(1, 10, 4), intArrayOf(1, 10), intArrayOf(1, 10), intArrayOf(1, 10)),
            ),
        )
    }

    @Test
    fun rejectsWrongBoxCoordinatesDimension() {
        assertFalse(
            SsdOutputContract.isLikelyDetectionPostProcess(
                listOf(intArrayOf(1, 10, 5), intArrayOf(1, 10), intArrayOf(1, 10), intArrayOf(1)),
            ),
        )
    }
}
