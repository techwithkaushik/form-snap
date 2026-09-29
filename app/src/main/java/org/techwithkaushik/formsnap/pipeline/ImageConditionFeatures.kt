package org.techwithkaushik.formSnap.pipeline

import android.graphics.RectF
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.Rect
import org.opencv.imgproc.Imgproc
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/**
 * Measured characteristics of the detected region, normalized to the ranges
 * accepted by [CorrectionLearning]. Features are measured from the original
 * image, before enhancement, so learning is not biased by its own adjustments.
 */
data class ImageConditionFeatures(
    val brightness: Float,
    val contrast: Float,
    val saturation: Float,
    val edgeDensity: Float,
    val aspectRatio: Float,
) {
    companion object {
        /**
         * Returns null for invalid/empty regions or unsupported channel counts.
         * All OpenCV native allocations are released even when an operation fails.
         */
        fun measure(source: Mat, bounds: RectF): ImageConditionFeatures? {
            if (source.empty() || !valid(bounds)) return null
            if (source.channels() !in setOf(1, 3, 4)) return null

            val left = floor(bounds.left.toDouble()).toInt().coerceIn(0, source.cols())
            val top = floor(bounds.top.toDouble()).toInt().coerceIn(0, source.rows())
            val right = ceil(bounds.right.toDouble()).toInt().coerceIn(0, source.cols())
            val bottom = ceil(bounds.bottom.toDouble()).toInt().coerceIn(0, source.rows())
            if (right - left < 2 || bottom - top < 2) return null

            val region = source.submat(Rect(left, top, right - left, bottom - top))
            val gray = Mat()
            val hsv = Mat()
            val edges = Mat()
            val mean = MatOfDouble()
            val standardDeviation = MatOfDouble()
            try {
                when (region.channels()) {
                    1 -> region.copyTo(gray)
                    3 -> Imgproc.cvtColor(region, gray, Imgproc.COLOR_BGR2GRAY)
                    4 -> Imgproc.cvtColor(region, gray, Imgproc.COLOR_BGRA2GRAY)
                }

                Core.meanStdDev(gray, mean, standardDeviation)
                val meanBrightness = Core.mean(gray).`val`[0]
                val contrast = (standardDeviation.toArray().firstOrNull() ?: 0.0) / 64.0

                val saturation = when (region.channels()) {
                    3 -> {
                        Imgproc.cvtColor(region, hsv, Imgproc.COLOR_BGR2HSV)
                        Core.mean(hsv).`val`[1] / 64.0
                    }
                    4 -> {
                        Imgproc.cvtColor(region, hsv, Imgproc.COLOR_BGRA2HSV)
                        Core.mean(hsv).`val`[1] / 64.0
                    }
                    else -> 0.0
                }

                Imgproc.Canny(gray, edges, 50.0, 150.0)
                val edgeDensity = Core.countNonZero(edges).toDouble() /
                    max(1.0, edges.rows().toDouble() * edges.cols().toDouble())

                return ImageConditionFeatures(
                    brightness = (meanBrightness / 127.5 - 1.0).toFloat().coerceIn(-1f, 1f),
                    contrast = contrast.toFloat().coerceIn(0f, 4f),
                    saturation = saturation.toFloat().coerceIn(0f, 4f),
                    edgeDensity = edgeDensity.toFloat().coerceIn(0f, 1f),
                    aspectRatio = ((bottom - top).toFloat() / max(1, right - left))
                        .coerceIn(0.05f, 20f),
                )
            } finally {
                standardDeviation.release()
                mean.release()
                edges.release()
                hsv.release()
                gray.release()
                region.release()
            }
        }

        private fun valid(bounds: RectF): Boolean =
            bounds.left.isFinite() && bounds.top.isFinite() &&
                bounds.right.isFinite() && bounds.bottom.isFinite() &&
                bounds.right > bounds.left && bounds.bottom > bounds.top
    }
}
