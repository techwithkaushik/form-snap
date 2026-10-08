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
            .filter { it.classId in 0..config.maxClassId }
            .take(config.maxDetections)
    }

    private fun looksLikeNmsOutput(shape: IntArray): Boolean =
        shape.last() in 6..7

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
                        label = classLabel(classId, config.maxClassId),
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

        if (a in 7..256 && b > a) {
            channels = a
            candidates = b
            channelsFirst = true
        } else {
            candidates = a
            channels = b
            channelsFirst = false
        }

        if (channels < 7 || candidates <= 0) return emptyList()

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

            if (bestClass !in 0..config.maxClassId || bestScore < config.confidenceThreshold) continue

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
                label = classLabel(bestClass, config.maxClassId),
                confidence = bestScore,
                boundingBox = box,
            )
        }
        return result
    }

    private fun classLabel(classId: Int, maxClassId: Int): String =
        if (maxClassId <= 2) {
            DetectedClass.fromId(classId)?.label ?: "Object $classId"
        } else {
            COCO_LABELS.getOrElse(classId) { "Object $classId" }
        }

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


private val COCO_LABELS = listOf(
    "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train",
    "truck", "boat", "traffic light", "fire hydrant", "stop sign", "parking meter",
    "bench", "bird", "cat", "dog", "horse", "sheep", "cow", "elephant", "bear",
    "zebra", "giraffe", "backpack", "umbrella", "handbag", "tie", "suitcase",
    "frisbee", "skis", "snowboard", "sports ball", "kite", "baseball bat",
    "baseball glove", "skateboard", "surfboard", "tennis racket", "bottle",
    "wine glass", "cup", "fork", "knife", "spoon", "bowl", "banana", "apple",
    "sandwich", "orange", "broccoli", "carrot", "hot dog", "pizza", "donut",
    "cake", "chair", "couch", "potted plant", "bed", "dining table", "toilet",
    "tv", "laptop", "mouse", "remote", "keyboard", "cell phone", "microwave",
    "oven", "toaster", "sink", "refrigerator", "book", "clock", "vase",
    "scissors", "teddy bear", "hair drier", "toothbrush"
)
