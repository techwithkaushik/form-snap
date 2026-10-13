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
        val boxIndex = outputShapes.indices.first { outputShapes[it].lastOrNull() == 4 && outputShapes[it].size >= 2 }
        val boxCount = outputShapes[boxIndex][outputShapes[boxIndex].lastIndex - 1]
        val vectorIndices = outputShapes.indices.filter {
            it != boxIndex && outputShapes[it].lastOrNull() == boxCount && outputShapes[it].size in 2..3
        }
        require(vectorIndices.size >= 2) { "SSD classes/scores vectors could not be identified." }
        val countIndex = outputShapes.indices.firstOrNull {
            it != boxIndex && (outputShapes[it].contentEquals(intArrayOf(1)) ||
                outputShapes[it].contentEquals(intArrayOf(1, 1)))
        } ?: throw IllegalArgumentException("SSD detection count tensor is missing.")
        val classScoreIndices = vectorIndices.filter { it != countIndex }
        require(classScoreIndices.size >= 2) { "SSD classes and scores tensors are missing." }
        // DetectionPostProcess class and score tensors have identical shapes.
        // Infer score tensor conservatively from finite values; class values are integral IDs.
        val first = classScoreIndices[0]
        val second = classScoreIndices[1]
        val firstValues = outputs[first]
        val secondValues = outputs[second]
        val firstLooksLikeIds = firstValues.all { it.isFinite() && kotlin.math.abs(it - it.roundToInt()) < 0.001f }
        val secondLooksLikeIds = secondValues.all { it.isFinite() && kotlin.math.abs(it - it.roundToInt()) < 0.001f }
        val classesIndex = when {
            firstLooksLikeIds && !secondLooksLikeIds -> first
            secondLooksLikeIds && !firstLooksLikeIds -> second
            else -> throw IllegalArgumentException("Unable to distinguish SSD class IDs from scores safely.")
        }
        val scoresIndex = if (classesIndex == first) second else first
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
