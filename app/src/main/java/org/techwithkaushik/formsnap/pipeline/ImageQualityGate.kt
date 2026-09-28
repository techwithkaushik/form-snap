package org.techwithkaushik.formSnap.pipeline

import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import kotlin.math.max

data class QualityResult(
    val accepted: Boolean,
    val sharpness: Double,
    val brightness: Double,
    val inkDensity: Double,
)

object ImageQualityGate {
    fun evaluate(image: Mat, kind: DetectionKind): QualityResult {
        require(!image.empty()) { "Image is empty" }
        val gray = Mat()
        val laplacian = Mat()
        val dark = Mat()
        return try {
            Imgproc.cvtColor(image, gray, Imgproc.COLOR_BGR2GRAY)
            Imgproc.Laplacian(gray, laplacian, -1)
            val mean = Core.mean(laplacian).`val`[0]
            val meanSquare = Core.norm(laplacian, Core.NORM_L2SQR) /
                max(1.0, image.cols().toDouble() * image.rows().toDouble())
            val variance = meanSquare - mean * mean
            val brightness = Core.mean(gray).`val`[0] / 255.0
            Imgproc.threshold(gray, dark, 150.0, 255.0, Imgproc.THRESH_BINARY_INV)
            val inkDensity = Core.countNonZero(dark).toDouble() /
                max(1.0, dark.cols().toDouble() * dark.rows().toDouble())
            val accepted = when (kind) {
                DetectionKind.PHOTO -> variance >= 35.0 && brightness in 0.12..0.96
                DetectionKind.SIGNATURE -> variance >= 10.0 && brightness in 0.10..0.99 && inkDensity >= 0.002
            }
            QualityResult(accepted, variance, brightness, inkDensity)
        } finally {
            dark.release()
            laplacian.release()
            gray.release()
        }
    }
}
