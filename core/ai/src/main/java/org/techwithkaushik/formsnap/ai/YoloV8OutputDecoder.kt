package org.techwithkaushik.formsnap.ai

import android.graphics.RectF
import android.util.Log
import kotlin.math.max
import kotlin.math.min

internal class YoloV8OutputDecoder(
    private val config: DetectionConfig,
) {
    private companion object {
        const val MAX_CANDIDATES_PER_CLASS = 100
    }

    private fun thresholdFor(classId: Int): Float = config.confidenceThreshold.coerceAtLeast(0.05f)
    fun decode(
        values: FloatArray,
        shape: IntArray,
        transform: LetterboxTransform,
    ): List<DetectedObject> {
        if (values.isEmpty()) {
            Log.w("FormSnapAI", "YOLO output tensor is empty; skipping this frame.")
            return emptyList()
        }
        val expectedValues = YoloTensorShape.expectedValueCount(shape)
        if (expectedValues == null || expectedValues != values.size) {
            Log.e(
                "FormSnapAI",
                "Invalid YOLO output tensor: shape=${shape.contentToString()}, " +
                    "values=${values.size}, expected=${expectedValues ?: "unsupported shape"}. " +
                    "Skipping this frame to avoid invalid tensor indexing.",
            )
            return emptyList()
        }

        val detections = if (looksLikeNmsOutput(shape, transform)) {
            decodeNms(values, shape, transform)
        } else {
            decodeRawYolo(values, shape, transform)
        }.filter { detection ->
            detection.confidence >= thresholdFor(detection.classId)
        }
            // Bound NMS work on slower ARM devices. The final output is at most
            // one box per class, so processing thousands of weak candidates is wasteful.
            .groupBy { it.classId }
            .values
            .flatMap { classDetections ->
                classDetections.sortedByDescending { it.confidence }.take(MAX_CANDIDATES_PER_CLASS)
            }
        if (detections.isEmpty()) {
            val scoreStats = rawClassScoreStats(values, shape)
            Log.w(
                "FormSnapAI",
                "YOLO decoded zero boxes: shape=${shape.contentToString()}, " +
                    "bestClassScore=${scoreStats.first}, above005=${scoreStats.second}, " +
                    "above012=${scoreStats.third}, threshold=${config.confidenceThreshold}. " +
                    "Check model class order, preprocessing and trained weights.",
            )
        } else {
            Log.d(
                "FormSnapAI",
                "YOLO decoded ${detections.size} boxes before NMS; best=${detections.maxOf { it.confidence }}",
            )
        }

        // Keep multiple instances of every class. Class-aware NMS only removes overlapping duplicates.
        return nonMaximumSuppression(detections).sortedByDescending { it.confidence }.take(config.maxDetections.coerceAtLeast(1))
    }

    private fun rawClassScoreStats(values: FloatArray, shape: IntArray): Triple<Float, Int, Int> {
        if (shape.size < 3) return Triple(Float.NaN, 0, 0)
        val a = shape[shape.size - 2]
        val b = shape.last()
        val channelsFirst = a in 5..512 && b > a
        val channels = if (channelsFirst) a else b
        val candidates = if (channelsFirst) b else a
        if (channels < 5 || candidates <= 0 || values.size < channels * candidates) {
            return Triple(Float.NaN, 0, 0)
        }
        var best = 0f
        var above005 = 0
        var above012 = 0
        for (candidate in 0 until candidates) {
            var score = 0f
            for (channel in 4 until channels) {
                val value = if (channelsFirst) values[channel * candidates + candidate] else values[candidate * channels + channel]
                if (value.isFinite()) score = max(score, value)
            }
            if (score.isFinite()) {
                best = max(best, score)
                if (score >= 0.05f) above005++
                if (score >= 0.12f) above012++
            }
        }
        return Triple(best, above005, above012)
    }

    /*
     * Raw two-class YOLOv8 at 320px has 2,100 candidates; at 640px it has
     * 8,400. A small final dimension alone is not enough to identify NMS:
     * raw channel-last output is also [1, N, 6].
     */
    private fun looksLikeNmsOutput(shape: IntArray, transform: LetterboxTransform): Boolean =
        YoloOutputShape.isLikelyNmsOutput(
            outputShape = shape,
            inputWidth = transform.inputWidth,
            inputHeight = transform.inputHeight,
        )

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
                val rawClassId = values[base + 5]
                if (!confidence.isFinite() || confidence < config.confidenceThreshold) continue
                if (!rawClassId.isFinite() || rawClassId < 0f || rawClassId > 999f) continue
                val classId = rawClassId.toInt()
                // Post-NMS class IDs are integral; malformed floats should not
                // silently turn into a different class label.
                if (kotlin.math.abs(rawClassId - classId) > 0.01f) continue

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

        if (a in 5..512 && b > a) {
            channels = a
            candidates = b
            channelsFirst = true
        } else {
            candidates = a
            channels = b
            channelsFirst = false
        }

        // A single-class raw YOLO head has 4 box channels + 1 class score.
        if (channels < 5 || candidates <= 0) return emptyList()
        val classCount = channels - 4
        if (classCount < 1) return emptyList()

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

            if (bestClass !in 0 until classCount || !bestScore.isFinite() || bestScore < config.confidenceThreshold) continue

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
        config.classLabels.getOrNull(classId)?.trim()?.takeIf { it.isNotEmpty() }
            ?: "Class $classId"

    private fun mapBox(
        raw: RectF,
        transform: LetterboxTransform,
    ): RectF? {
        if (!raw.left.isFinite() || !raw.top.isFinite() ||
            !raw.right.isFinite() || !raw.bottom.isFinite()
        ) return null

        val maxCoordinate = max(
            max(kotlin.math.abs(raw.left), kotlin.math.abs(raw.right)),
            max(kotlin.math.abs(raw.top), kotlin.math.abs(raw.bottom)),
        )
        // Some custom exports return normalized 0..1 boxes. Scale x and y
        // independently: rectangular model inputs must not use width for y.
        val normalizedCoordinates = maxCoordinate <= 1.5f
        val xScale = if (normalizedCoordinates) transform.inputWidth.toFloat() else 1f
        val yScale = if (normalizedCoordinates) transform.inputHeight.toFloat() else 1f

        val left = (raw.left * xScale - transform.padX) / transform.scale
        val top = (raw.top * yScale - transform.padY) / transform.scale
        val right = (raw.right * xScale - transform.padX) / transform.scale
        val bottom = (raw.bottom * yScale - transform.padY) / transform.scale

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
