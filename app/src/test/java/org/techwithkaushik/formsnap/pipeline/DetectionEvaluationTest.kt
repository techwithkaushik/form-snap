package org.techwithkaushik.formSnap.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectionEvaluationTest {
    private fun box(left: Double, top: Double, right: Double, bottom: Double) =
        EvaluationBox(left, top, right, bottom)

    @Test
    fun bothPhotoAndSignatureDetectedAreCountedIndependently() {
        val expected = listOf(
            DetectionObservation(DetectionKind.PHOTO, box(10.0, 10.0, 110.0, 130.0)),
            DetectionObservation(DetectionKind.SIGNATURE, box(20.0, 180.0, 220.0, 230.0)),
        )
        val actual = listOf(
            DetectionObservation(DetectionKind.PHOTO, box(12.0, 12.0, 108.0, 128.0)),
            DetectionObservation(DetectionKind.SIGNATURE, box(22.0, 181.0, 218.0, 229.0)),
        )

        val metrics = DetectionEvaluation.evaluate(expected, actual)

        assertEquals(1, metrics.getValue(DetectionKind.PHOTO).truePositives)
        assertEquals(1, metrics.getValue(DetectionKind.SIGNATURE).truePositives)
        assertEquals(0, metrics.getValue(DetectionKind.PHOTO).falseNegatives)
        assertEquals(0, metrics.getValue(DetectionKind.SIGNATURE).falsePositives)
    }

    @Test
    fun photoOnlyScenarioDoesNotCountMissingSignatureAsPhotoFailure() {
        val expected = listOf(
            DetectionObservation(DetectionKind.PHOTO, box(10.0, 10.0, 110.0, 130.0)),
        )
        val actual = listOf(
            DetectionObservation(DetectionKind.PHOTO, box(10.0, 10.0, 110.0, 130.0)),
        )

        val metrics = DetectionEvaluation.evaluate(expected, actual)

        assertEquals(1, metrics.getValue(DetectionKind.PHOTO).truePositives)
        assertEquals(0, metrics.getValue(DetectionKind.SIGNATURE).falsePositives)
        assertEquals(0, metrics.getValue(DetectionKind.SIGNATURE).falseNegatives)
    }

    @Test
    fun signatureOnlyScenarioDoesNotRequirePhoto() {
        val expected = listOf(
            DetectionObservation(DetectionKind.SIGNATURE, box(20.0, 180.0, 220.0, 230.0)),
        )
        val actual = listOf(
            DetectionObservation(DetectionKind.SIGNATURE, box(20.0, 180.0, 220.0, 230.0)),
        )

        val metrics = DetectionEvaluation.evaluate(expected, actual)

        assertEquals(1, metrics.getValue(DetectionKind.SIGNATURE).truePositives)
        assertEquals(0, metrics.getValue(DetectionKind.PHOTO).falsePositives)
        assertEquals(0, metrics.getValue(DetectionKind.PHOTO).falseNegatives)
    }

    @Test
    fun neitherScenarioHasNoFalsePositiveOrFalseNegative() {
        val metrics = DetectionEvaluation.evaluate(emptyList(), emptyList())

        DetectionKind.values().forEach { kind ->
            assertEquals(0, metrics.getValue(kind).truePositives)
            assertEquals(0, metrics.getValue(kind).falsePositives)
            assertEquals(0, metrics.getValue(kind).falseNegatives)
        }
    }

    @Test
    fun duplicatePredictionsBecomeFalsePositives() {
        val truth = DetectionObservation(DetectionKind.PHOTO, box(0.0, 0.0, 100.0, 100.0))
        val predicted = truth.copy()
        val duplicate = truth.copy(box = box(1.0, 1.0, 99.0, 99.0))

        val metrics = DetectionEvaluation.evaluate(listOf(truth), listOf(predicted, duplicate))
            .getValue(DetectionKind.PHOTO)

        assertEquals(1, metrics.truePositives)
        assertEquals(1, metrics.falsePositives)
        assertEquals(0, metrics.falseNegatives)
        assertEquals(0.5, metrics.precision, 0.0001)
    }

    @Test
    fun matchingFindsMaximumNumberOfValidPairsInsteadOfGreedyChoice() {
        val firstTruth = DetectionObservation(
            DetectionKind.PHOTO,
            box(0.0, 0.0, 100.0, 100.0),
        )
        val secondTruth = DetectionObservation(
            DetectionKind.PHOTO,
            box(30.0, 0.0, 130.0, 100.0),
        )
        // The first prediction overlaps both truths, while the second only
        // overlaps the first truth above the 0.5 IoU threshold.
        val flexiblePrediction = DetectionObservation(
            DetectionKind.PHOTO,
            box(20.0, 0.0, 120.0, 100.0),
        )
        val constrainedPrediction = DetectionObservation(
            DetectionKind.PHOTO,
            box(-20.0, 0.0, 80.0, 100.0),
        )

        val metrics = DetectionEvaluation.evaluate(
            expected = listOf(firstTruth, secondTruth),
            actual = listOf(flexiblePrediction, constrainedPrediction),
        ).getValue(DetectionKind.PHOTO)

        assertEquals(2, metrics.truePositives)
        assertEquals(0, metrics.falsePositives)
        assertEquals(0, metrics.falseNegatives)
    }

    @Test
    fun lowOverlapIsCountedAsMissAndFalsePositive() {
        val truth = DetectionObservation(DetectionKind.SIGNATURE, box(0.0, 0.0, 100.0, 30.0))
        val prediction = DetectionObservation(DetectionKind.SIGNATURE, box(200.0, 200.0, 300.0, 230.0))

        val metrics = DetectionEvaluation.evaluate(listOf(truth), listOf(prediction))
            .getValue(DetectionKind.SIGNATURE)

        assertEquals(0, metrics.truePositives)
        assertEquals(1, metrics.falsePositives)
        assertEquals(1, metrics.falseNegatives)
        assertTrue(metrics.f1 == 0.0)
    }

    @Test
    fun cropRetentionReportsClippedContent() {
        val content = box(0.0, 0.0, 100.0, 100.0)
        val crop = box(0.0, 0.0, 75.0, 100.0)

        assertEquals(0.75, DetectionEvaluation.contentRetention(content, crop), 0.000001)
    }

    @Test
    fun cropRetentionIsZeroWhenContentIsOutsideCrop() {
        val content = box(200.0, 200.0, 300.0, 300.0)
        val crop = box(0.0, 0.0, 100.0, 100.0)

        assertEquals(0.0, DetectionEvaluation.contentRetention(content, crop), 0.000001)
    }

    @Test
    fun identicalBoxesHaveFullOverlap() {
        val same = box(5.0, 7.0, 25.0, 37.0)
        assertEquals(1.0, DetectionEvaluation.intersectionOverUnion(same, same), 0.000001)
    }
}
