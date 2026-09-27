package org.techwithkaushik.formSnap.pipeline

import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import kotlin.math.max

object AppearanceProcessor {
    fun apply(input: Mat, adjustments: AppearanceAdjustments, kind: DetectionKind): Mat {
        require(!input.empty()) { "Image is empty" }
        var current = input.clone()

        val brightness = adjustments.brightness.coerceIn(-0.5f, 0.5f)
        val contrast = adjustments.contrast.coerceIn(0.7f, 1.5f)
        if (brightness != 0f || contrast != 1f) {
            val adjusted = Mat()
            current.convertTo(adjusted, -1, contrast.toDouble(), (brightness * 255f).toDouble())
            current.release()
            current = adjusted
        }

        val saturation = adjustments.saturation.coerceIn(0.5f, 1.5f)
        if (kind == DetectionKind.PHOTO && saturation != 1f) {
            val hsv = Mat()
            val adjusted = Mat()
            Imgproc.cvtColor(current, hsv, Imgproc.COLOR_BGR2HSV)
            val channels = ArrayList<Mat>(3)
            Core.split(hsv, channels)
            channels[1].convertTo(channels[1], channels[1].type(), saturation.toDouble())
            Core.merge(channels, hsv)
            Imgproc.cvtColor(hsv, adjusted, Imgproc.COLOR_HSV2BGR)
            channels.forEach { it.release() }
            hsv.release()
            current.release()
            current = adjusted
        }

        val denoise = adjustments.denoise.coerceIn(0f, 1f)
        if (denoise > 0f) {
            val adjusted = Mat()
            val strength = max(1, (3 + denoise * 7).toInt())
            Imgproc.GaussianBlur(current, adjusted, org.opencv.core.Size(strength.toDouble(), strength.toDouble()), 0.0)
            current.release()
            current = adjusted
        }

        if (kind == DetectionKind.SIGNATURE && adjustments.inkThreshold in 80..220) {
            val gray = Mat()
            val cleaned = Mat()
            Imgproc.cvtColor(current, gray, Imgproc.COLOR_BGR2GRAY)
            Imgproc.adaptiveThreshold(
                gray,
                cleaned,
                255.0,
                Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                Imgproc.THRESH_BINARY,
                31,
                max(2, 31 - adjustments.inkThreshold / 10),
            )
            Imgproc.cvtColor(cleaned, current, Imgproc.COLOR_GRAY2BGR)
            gray.release()
            cleaned.release()
        }

        val sharpness = adjustments.sharpness.coerceIn(0f, 1f)
        if (sharpness > 0f) {
            val blurred = Mat()
            val sharpened = Mat()
            Imgproc.GaussianBlur(current, blurred, org.opencv.core.Size(0.0, 0.0), 1.0)
            Core.addWeighted(
                current,
                1.0 + sharpness.toDouble(),
                blurred,
                -sharpness.toDouble(),
                0.0,
                sharpened,
            )
            current.release()
            blurred.release()
            current = sharpened
        }

        return current
    }
}
