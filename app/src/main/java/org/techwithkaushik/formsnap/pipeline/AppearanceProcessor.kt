package org.techwithkaushik.formSnap.pipeline

import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max

object AppearanceProcessor {
    fun apply(input: Mat, adjustments: AppearanceAdjustments, kind: DetectionKind): Mat {
        require(!input.empty()) { "Image is empty" }
        var current = input.clone()

        val tuning = AppearanceTuning.clamp(adjustments)

        val brightness = tuning.brightness
        val contrast = tuning.contrast
        if (brightness != 0f || contrast != 1f) {
            val adjusted = Mat()
            current.convertTo(
                adjusted,
                -1,
                contrast.toDouble(),
                (brightness * 255f).toDouble(),
            )
            current.release()
            current = adjusted
        }

        if (kind == DetectionKind.PHOTO && tuning.saturation != 1f) {
            val hsv = Mat()
            val adjusted = Mat()
            Imgproc.cvtColor(current, hsv, Imgproc.COLOR_BGR2HSV)
            val channels = ArrayList<Mat>(3)
            Core.split(hsv, channels)
            val saturation = Mat()
            channels[1].convertTo(saturation, channels[1].type(), tuning.saturation.toDouble())
            channels[1].release()
            channels[1] = saturation
            Core.merge(channels, hsv)
            Imgproc.cvtColor(hsv, adjusted, Imgproc.COLOR_HSV2BGR)
            channels.forEach { it.release() }
            hsv.release()
            current.release()
            current = adjusted
        }

        if (tuning.denoise > 0f) {
            val adjusted = Mat()
            val kernel = 3 + 2 * (tuning.denoise * 3f).toInt()
            Imgproc.GaussianBlur(
                current,
                adjusted,
                Size(kernel.toDouble(), kernel.toDouble()),
                0.0,
            )
            current.release()
            current = adjusted
        }

        if (kind == DetectionKind.SIGNATURE && tuning.inkThreshold in 80..220) {
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
                max(2.0, 31.0 - tuning.inkThreshold.toDouble() / 10.0),
            )
            val colored = Mat()
            Imgproc.cvtColor(cleaned, colored, Imgproc.COLOR_GRAY2BGR)
            gray.release()
            cleaned.release()
            current.release()
            current = colored
        }

        if (tuning.sharpness > 0f) {
            val blurred = Mat()
            val sharpened = Mat()
            Imgproc.GaussianBlur(current, blurred, Size(0.0, 0.0), 1.0)
            Core.addWeighted(
                current,
                1.0 + tuning.sharpness.toDouble(),
                blurred,
                -tuning.sharpness.toDouble(),
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
