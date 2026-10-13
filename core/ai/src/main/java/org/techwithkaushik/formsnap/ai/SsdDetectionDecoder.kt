package org.techwithkaushik.formsnap.ai

import android.graphics.RectF
import kotlin.math.roundToInt

/**
 * Decoder for the standard TFLite Detection_PostProcess four-output contract.
 * Boxes are normalized [top,left,bottom,right], classes/scores are flat
 * vectors, and count is a scalar/vector. Output arrays must already be
 * dequantized to real values by the interpreter adapter.
 */
internal object SsdDetectionDecoder {
    fun decode(
        outputShapes: List<IntArray>,
        outputs: List<FloatArray>,
        transform: LetterboxTransform,
        labels: List<String>,
        confidenceThreshold: Float,
        maxDetections: Int,
    ): List<DetectedObject> {
        require(SsdOutputContract.isLikelyDetectionPostProcess(outputShapes)) {
            "Unsupported SSD output signature: ${outputShapes.joinToString { it.contentToString() }}"
        }
        require(outputShapes.size == outputs.size) { "SSD output count does not match output shapes." }
        // TensorFlow Lite Detection_PostProcess uses the stable order:
        // boxes, classes, scores, count. Do not infer classes/scores from values:
        // a valid model can have only 0.0/1.0 scores, making that heuristic ambiguous.
        val boxIndex = 0
        val classesIndex = 1
        val scoresIndex = 2
        val countIndex = 3
        require(outputShapes[boxIndex].lastOrNull() == 4) { "SSD output 0 must contain boxes [top,left,bottom,right]." }
        val boxCount = outputShapes[boxIndex][outputShapes[boxIndex].lastIndex - 1]
        require(outputShapes[classesIndex].lastOrNull() == boxCount &&
            outputShapes[scoresIndex].lastOrNull() == boxCount
        ) { "SSD classes/scores count does not match box count." }
        val boxes = outputs[boxIndex]
        val classes = outputs[classesIndex]
        val scores = outputs[scoresIndex]
        val declaredCount = outputs[countIndex].firstOrNull()?.toInt() ?: 0
        val count = minOf(declaredCount.coerceAtLeast(0), boxCount, classes.size, scores.size, boxes.size / 4)
        val threshold = confidenceThreshold.coerceIn(0f, 1f)
        val result = ArrayList<DetectedObject>(minOf(count, maxDetections.coerceAtLeast(1)))
        for (i in 0 until count) {
            val score = scores[i]
            val classValue = classes[i]
            if (!score.isFinite() || score !in threshold..1f) continue
            if (!classValue.isFinite() || classValue < 0f || classValue > 999f) continue
            val classId = classValue.roundToInt()
            if (kotlin.math.abs(classValue - classId) > 0.01f) continue
            val offset = i * 4
            val top = boxes[offset]
            val left = boxes[offset + 1]
            val bottom = boxes[offset + 2]
            val right = boxes[offset + 3]
            if (!top.isFinite() || !left.isFinite() || !bottom.isFinite() || !right.isFinite()) continue
            val mapped = mapNormalizedBox(left, top, right, bottom, transform) ?: continue
            result += DetectedObject(
                id = i.toLong(),
                classId = classId,
                label = labels.getOrNull(classId)?.trim()?.takeIf { it.isNotEmpty() } ?: "Class $classId",
                confidence = score,
                boundingBox = mapped,
            )
        }
        return result.sortedByDescending { it.confidence }.take(maxDetections.coerceAtLeast(1))
    }

    private fun mapNormalizedBox(
        leftRatio: Float,
        topRatio: Float,
        rightRatio: Float,
        bottomRatio: Float,
        transform: LetterboxTransform,
    ): RectF? {
        if (leftRatio !in 0f..1f || topRatio !in 0f..1f ||
            rightRatio !in 0f..1f || bottomRatio !in 0f..1f ||
            rightRatio <= leftRatio || bottomRatio <= topRatio
        ) return null
        val inputW = transform.inputWidth.toFloat()
        val inputH = transform.inputHeight.toFloat()
        val left = (leftRatio * inputW - transform.padX) / transform.scale
        val top = (topRatio * inputH - transform.padY) / transform.scale
        val right = (rightRatio * inputW - transform.padX) / transform.scale
        val bottom = (bottomRatio * inputH - transform.padY) / transform.scale
        val mapped = RectF(
            left.coerceIn(0f, transform.sourceWidth.toFloat()),
            top.coerceIn(0f, transform.sourceHeight.toFloat()),
            right.coerceIn(0f, transform.sourceWidth.toFloat()),
            bottom.coerceIn(0f, transform.sourceHeight.toFloat()),
        )
        return mapped.takeIf { it.width() > 0f && it.height() > 0f }
    }
}
