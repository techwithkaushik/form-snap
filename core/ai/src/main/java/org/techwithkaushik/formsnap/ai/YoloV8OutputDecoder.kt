package org.techwithkaushik.formsnap.ai

import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min

internal class YoloV8OutputDecoder(
    private val config: DetectionConfig,
) {
    fun decode(
        values: FloatArray,
        shape: IntArray,
        transform: LetterboxTransform,
    ): List<DetectedObject> {
        require(shape.isNotEmpty()) { "YOLO output shape is empty." }
        require(values.isNotEmpty()) { "YOLO output tensor is empty." }

        val detections = if (looksLikeNmsOutput(shape)) {
            decodeNms(values, shape, transform)
        } else {
            decodeRawYolo(values, shape, transform)
        }

        return nonMaximumSuppression(detections)
            .filter { it.classId in 0..1 }
            .take(config.maxDetections)
    }

    /*
     * Raw two-class YOLOv8 at 320px has 2,100 candidates; at 640px it has
     * 8,400. A small final dimension alone is not enough to identify NMS:
     * raw channel-last output is also [1, N, 6].
     */
    private fun looksLikeNmsOutput(shape: IntArray): Boolean =
        shape.size >= 3 && shape.last() in 6..7 && shape[shape.size - 2] <= 1000

    private fun decodeNms(
        values: FloatArray,
        shape: IntArray,
        transform: LetterboxTransform,
    ): List<DetectedObject> {
        val rows = if (shape.size >= 2) shape[shape.size - 2] else 0
        val cols = shape.last()
        if (rows <= 0 || cols < 6) return emptyList()

        return buildList {
            for (row in 0 until rows) {
                val base = row * cols
                val confidence = values[base + 4]
                val classId = values[base + 5].toInt()
                if (confidence < config.confidenceThreshold) continue

                val box = mapBox(
                    RectF(
                        values[base],
                        values[base + 1],
                        values[base + 2],
                        values[base + 3],
                    ),
                    transform,
                ) ?: continue

                add(
                    DetectedObject(
                        id = size.toLong(),
                        classId = classId,
                        label = classLabel(classId),
                        confidence = confidence,
                        boundingBox = box,
                    ),
                )
            }
        }
    }

    private fun decodeRawYolo(
        values: FloatArray,
        shape: IntArray,
        transform: LetterboxTransform,
    ): List<DetectedObject> {
        if (shape.size < 3) return emptyList()

        val a = shape[shape.size - 2]
        val b = shape[shape.size - 1]

        val channels: Int
        val candidates: Int
        val channelsFirst: Boolean

        if (a == 6 && b > a || a in 7..256 && b > a) {
            channels = a
            candidates = b
            channelsFirst = true
        } else {
            candidates = a
            channels = b
            channelsFirst = false
        }

        if (channels < 6 || candidates <= 0) return emptyList()
        val classCount = channels - 4
        if (classCount != 2) return emptyList()

        fun at(candidate: Int, channel: Int): Float =
            if (channelsFirst) {
                values[channel * candidates + candidate]
            } else {
                values[candidate * channels + channel]
            }

        val result = ArrayList<DetectedObject>(candidates)
        for (candidate in 0 until candidates) {
            val cx = at(candidate, 0)
            val cy = at(candidate, 1)
            val width = at(candidate, 2)
            val height = at(candidate, 3)

            var bestClass = -1
            var bestScore = 0f
            for (classIndex in 4 until channels) {
                val score = at(candidate, classIndex)
                if (score > bestScore) {
                    bestScore = score
                    bestClass = classIndex - 4
                }
            }

            if (bestClass !in 0..1 || bestScore < config.confidenceThreshold) continue

            val box = mapBox(
                RectF(
                    cx - width / 2f,
                    cy - height / 2f,
                    cx + width / 2f,
                    cy + height / 2f,
                ),
                transform,
            ) ?: continue

            result += DetectedObject(
                id = candidate.toLong(),
                classId = bestClass,
                label = classLabel(bestClass),
                confidence = bestScore,
                boundingBox = box,
            )
        }
        return result
    }

    private fun classLabel(classId: Int): String =
        DetectedClass.fromId(classId)?.label ?: "Object $classId"

    private fun mapBox(
        raw: RectF,
        transform: LetterboxTransform,
    ): RectF? {
        val maxInput = transform.inputSize.toFloat()
        val maxCoordinate = max(
            max(kotlin.math.abs(raw.left), kotlin.math.abs(raw.right)),
            max(kotlin.math.abs(raw.top), kotlin.math.abs(raw.bottom)),
        )
        val scaleToInput = if (maxCoordinate <= 1.5f) maxInput else 1f

        val left = (raw.left * scaleToInput - transform.padX) / transform.scale
        val top = (raw.top * scaleToInput - transform.padY) / transform.scale
        val right = (raw.right * scaleToInput - transform.padX) / transform.scale
        val bottom = (raw.bottom * scaleToInput - transform.padY) / transform.scale

        val clipped = RectF(
            left.coerceIn(0f, transform.sourceWidth.toFloat()),
            top.coerceIn(0f, transform.sourceHeight.toFloat()),
            right.coerceIn(0f, transform.sourceWidth.toFloat()),
            bottom.coerceIn(0f, transform.sourceHeight.toFloat()),
        )

        return if (clipped.width() >= 2f && clipped.height() >= 2f) clipped else null
    }

    private fun nonMaximumSuppression(
        input: List<DetectedObject>,
    ): List<DetectedObject> {
        val result = ArrayList<DetectedObject>()
        input.groupBy { it.classId }.values.forEach { classDetections ->
            val remaining = classDetections.sortedByDescending { it.confidence }.toMutableList()
            while (remaining.isNotEmpty()) {
                val best = remaining.removeAt(0)
                result += best
                remaining.removeAll {
                    iou(best.boundingBox, it.boundingBox) > config.iouThreshold
                }
            }
        }
        return result.sortedByDescending { it.confidence }
    }

    private fun iou(a: RectF, b: RectF): Float {
        val left = max(a.left, b.left)
        val top = max(a.top, b.top)
        val right = min(a.right, b.right)
        val bottom = min(a.bottom, b.bottom)
        val intersection = max(0f, right - left) * max(0f, bottom - top)
        if (intersection <= 0f) return 0f
        val union = a.width() * a.height() + b.width() * b.height() - intersection
        return if (union > 0f) intersection / union else 0f
    }
}
