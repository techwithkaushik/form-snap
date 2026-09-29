package org.techwithkaushik.formSnap.pipeline

import kotlin.math.max
import kotlin.math.min

/** A platform-independent box used by regression evaluation and test fixtures. */
data class EvaluationBox(
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
) {
    init {
        require(left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite()) {
            "Evaluation box coordinates must be finite"
        }
        require(right > left && bottom > top) {
            "Evaluation box must have positive width and height"
        }
    }

    val area: Double
        get() = (right - left) * (bottom - top)
}

data class DetectionObservation(
    val kind: DetectionKind,
    val box: EvaluationBox,
)

data class DetectionMetrics(
    val truePositives: Int,
    val falsePositives: Int,
    val falseNegatives: Int,
) {
    val precision: Double
        get() = ratio(truePositives, truePositives + falsePositives)

    val recall: Double
        get() = ratio(truePositives, truePositives + falseNegatives)

    val f1: Double
        get() = if (precision + recall == 0.0) 0.0
        else 2.0 * precision * recall / (precision + recall)

    private fun ratio(numerator: Int, denominator: Int): Double =
        if (denominator == 0) 1.0 else numerator.toDouble() / denominator
}

/**
 * One-to-one IoU-based matching for a labelled image set.
 *
 * This class evaluates detector output; it does not itself detect photos or
 * signatures. Keep ground-truth labels independent from predicted detections.
 */
object DetectionEvaluation {
    fun evaluate(
        expected: List<DetectionObservation>,
        actual: List<DetectionObservation>,
        iouThreshold: Double = 0.5,
    ): Map<DetectionKind, DetectionMetrics> {
        require(iouThreshold.isFinite() && iouThreshold in 0.0..1.0) {
            "IoU threshold must be between 0 and 1"
        }

        return DetectionKind.values().associateWith { kind ->
            val truths = expected.filter { it.kind == kind }
            val predictions = actual.filter { it.kind == kind }
            val overlaps = Array(truths.size) { truthIndex ->
                DoubleArray(predictions.size) { predictionIndex ->
                    intersectionOverUnion(
                        truths[truthIndex].box,
                        predictions[predictionIndex].box,
                    )
                }
            }

            // Maximum-cardinality bipartite matching. A simple greedy pass can
            // consume the only valid prediction for a later truth and undercount
            // true positives, even when a complete valid matching exists.
            val truthByPrediction = IntArray(predictions.size) { -1 }

            fun assign(truthIndex: Int, visitedPredictions: BooleanArray): Boolean {
                val candidates = predictions.indices
                    .filter { overlaps[truthIndex][it] >= iouThreshold }
                    .sortedByDescending { overlaps[truthIndex][it] }

                for (predictionIndex in candidates) {
                    if (visitedPredictions[predictionIndex]) continue
                    visitedPredictions[predictionIndex] = true

                    val previousTruth = truthByPrediction[predictionIndex]
                    if (previousTruth == -1 ||
                        assign(previousTruth, visitedPredictions)
                    ) {
                        truthByPrediction[predictionIndex] = truthIndex
                        return true
                    }
                }
                return false
            }

            var truePositives = 0
            for (truthIndex in truths.indices) {
                if (assign(truthIndex, BooleanArray(predictions.size))) {
                    truePositives++
                }
            }

            DetectionMetrics(
                truePositives = truePositives,
                falsePositives = predictions.size - truePositives,
                falseNegatives = truths.size - truePositives,
            )
        }
    }

    /**
     * Fraction of labelled content retained inside a crop. 1.0 means the
     * crop fully contains the content; values below 1.0 quantify clipping.
     */
    fun contentRetention(content: EvaluationBox, crop: EvaluationBox): Double {
        val left = max(content.left, crop.left)
        val top = max(content.top, crop.top)
        val right = min(content.right, crop.right)
        val bottom = min(content.bottom, crop.bottom)
        if (right <= left || bottom <= top) return 0.0
        val retainedArea = (right - left) * (bottom - top)
        return (retainedArea / content.area).coerceIn(0.0, 1.0)
    }

    fun intersectionOverUnion(first: EvaluationBox, second: EvaluationBox): Double {
        val intersectionWidth = max(0.0, min(first.right, second.right) - max(first.left, second.left))
        val intersectionHeight = max(0.0, min(first.bottom, second.bottom) - max(first.top, second.top))
        val intersection = intersectionWidth * intersectionHeight
        val union = first.area + second.area - intersection
        return if (union <= 0.0) 0.0 else (intersection / union).coerceIn(0.0, 1.0)
    }
}
