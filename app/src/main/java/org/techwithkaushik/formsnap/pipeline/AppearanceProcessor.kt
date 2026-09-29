package org.techwithkaushik.formSnap.pipeline

import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max

/**
 * Applies bounded appearance adjustments without taking ownership of [input].
 * Every intermediate Mat is released on both success and failure paths.
 */
object AppearanceProcessor {
    fun apply(input: Mat, adjustments: AppearanceAdjustments, kind: DetectionKind): Mat {
        require(!input.empty()) { "Image is empty" }
        var current = input.clone()
        try {
            val tuning = AppearanceTuning.clamp(adjustments)

            if (tuning.brightness != 0f || tuning.contrast != 1f) {
                val adjusted = adjustBrightnessContrast(
                    current,
                    tuning.brightness,
                    tuning.contrast,
                )
                current.release()
                current = adjusted
            }

            if (kind == DetectionKind.PHOTO && tuning.saturation != 1f) {
                val adjusted = adjustSaturation(current, tuning.saturation)
                current.release()
                current = adjusted
            }

            if (tuning.denoise > 0f) {
                val kernel = 3 + 2 * (tuning.denoise * 3f).toInt()
                val adjusted = gaussianBlur(current, kernel)
                current.release()
                current = adjusted
            }

            if (kind == DetectionKind.SIGNATURE && tuning.inkThreshold in 80..220) {
                val adjusted = thresholdSignature(current, tuning.inkThreshold)
                current.release()
                current = adjusted
            }

            if (tuning.sharpness > 0f) {
                val adjusted = sharpen(current, tuning.sharpness)
                current.release()
                current = adjusted
            }

            return current
        } catch (t: Throwable) {
            current.release()
            throw t
        }
    }

    private fun adjustBrightnessContrast(
        input: Mat,
        brightness: Float,
        contrast: Float,
    ): Mat {
        val output = Mat()
        return try {
            input.convertTo(
                output,
                -1,
                contrast.toDouble(),
                (brightness * 255f).toDouble(),
            )
            output
        } catch (t: Throwable) {
            output.release()
            throw t
        }
    }

    private fun adjustSaturation(input: Mat, saturation: Float): Mat {
        val hsv = Mat()
        val output = Mat()
        val channels = ArrayList<Mat>(3)
        var success = false
        try {
            Imgproc.cvtColor(input, hsv, Imgproc.COLOR_BGR2HSV)
            Core.split(hsv, channels)
            val adjustedSaturation = Mat()
            try {
                channels[1].convertTo(
                    adjustedSaturation,
                    channels[1].type(),
                    saturation.toDouble(),
                )
            } catch (t: Throwable) {
                adjustedSaturation.release()
                throw t
            }
            channels[1].release()
            channels[1] = adjustedSaturation
            Core.merge(channels, hsv)
            Imgproc.cvtColor(hsv, output, Imgproc.COLOR_HSV2BGR)
            success = true
            return output
        } finally {
            channels.forEach { it.release() }
            hsv.release()
            if (!success) output.release()
        }
    }

    private fun gaussianBlur(input: Mat, kernel: Int): Mat {
        val output = Mat()
        return try {
            Imgproc.GaussianBlur(
                input,
                output,
                Size(kernel.toDouble(), kernel.toDouble()),
                0.0,
            )
            output
        } catch (t: Throwable) {
            output.release()
            throw t
        }
    }

    private fun thresholdSignature(input: Mat, threshold: Int): Mat {
        val gray = Mat()
        val binary = Mat()
        val output = Mat()
        var success = false
        try {
            when (input.channels()) {
                1 -> input.copyTo(gray)
                3 -> Imgproc.cvtColor(input, gray, Imgproc.COLOR_BGR2GRAY)
                4 -> Imgproc.cvtColor(input, gray, Imgproc.COLOR_BGRA2GRAY)
                else -> throw IllegalArgumentException(
                    "Unsupported image channel count: ${input.channels()}",
                )
            }
            Imgproc.adaptiveThreshold(
                gray,
                binary,
                255.0,
                Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                Imgproc.THRESH_BINARY,
                31,
                max(2.0, 31.0 - threshold.toDouble() / 10.0),
            )
            if (input.channels() == 1) {
                binary.copyTo(output)
            } else {
                Imgproc.cvtColor(binary, output, Imgproc.COLOR_GRAY2BGR)
            }
            success = true
            return output
        } finally {
            gray.release()
            binary.release()
            if (!success) output.release()
        }
    }

    private fun sharpen(input: Mat, sharpness: Float): Mat {
        val blurred = Mat()
        val output = Mat()
        var success = false
        try {
            Imgproc.GaussianBlur(input, blurred, Size(0.0, 0.0), 1.0)
            Core.addWeighted(
                input,
                1.0 + sharpness.toDouble(),
                blurred,
                -sharpness.toDouble(),
                0.0,
                output,
            )
            success = true
            return output
        } finally {
            blurred.release()
            if (!success) output.release()
        }
    }
}
