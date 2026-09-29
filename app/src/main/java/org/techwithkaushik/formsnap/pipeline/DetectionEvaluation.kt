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
            val matchedPredictions = HashSet<Int>()
            var truePositives = 0
            var falseNegatives = 0

            for (truth in truths) {
                var bestIndex = -1
                var bestIou = -1.0
                for (index in predictions.indices) {
                    if (index in matchedPredictions) continue
                    val iou = intersectionOverUnion(truth.box, predictions[index].box)
                    if (iou > bestIou) {
                        bestIou = iou
                        bestIndex = index
                    }
                }

                if (bestIndex >= 0 && bestIou >= iouThreshold) {
                    matchedPredictions += bestIndex
                    truePositives++
                } else {
                    falseNegatives++
                }
            }

            DetectionMetrics(
                truePositives = truePositives,
                falsePositives = predictions.size - matchedPredictions.size,
                falseNegatives = falseNegatives,
            )
        }
    }

    fun intersectionOverUnion(first: EvaluationBox, second: EvaluationBox): Double {
        val intersectionWidth = max(0.0, min(first.right, second.right) - max(first.left, second.left))
        val intersectionHeight = max(0.0, min(first.bottom, second.bottom) - max(first.top, second.top))
        val intersection = intersectionWidth * intersectionHeight
        val union = first.area + second.area - intersection
        return if (union <= 0.0) 0.0 else (intersection / union).coerceIn(0.0, 1.0)
    }
}
