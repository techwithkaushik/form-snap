package org.techwithkaushik.formSnap.pipeline

import android.graphics.RectF
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.roundToInt

data class NormalizedOutput(
    val image: Mat,
    val bounds: RectF,
)

object OutputNormalizer {
    private const val MAX_OUTPUT_PIXELS = 24_000_000L

    fun normalize(
        source: Mat,
        candidate: DetectionCandidate,
        kind: DetectionKind,
        dpi: Int = 300,
        widthMm: Double? = null,
        heightMm: Double? = null,
    ): NormalizedOutput {
        require(!source.empty()) { "Source image is empty" }
        require(dpi in 72..1200) { "DPI must be between 72 and 1200" }

        val requestedWidthMm = widthMm ?: defaultWidthMm(kind)
        val requestedHeightMm = heightMm ?: defaultHeightMm(kind)
        require(requestedWidthMm.isFinite() && requestedWidthMm > 0.0) {
            "Output width must be a finite positive value"
        }
        require(requestedHeightMm.isFinite() && requestedHeightMm > 0.0) {
            "Output height must be a finite positive value"
        }

        val widthPx = max(1, mmToPx(requestedWidthMm, dpi))
        val heightPx = max(1, mmToPx(requestedHeightMm, dpi))
        require(widthPx.toLong() * heightPx.toLong() <= MAX_OUTPUT_PIXELS) {
            "Requested output is too large to process safely"
        }

        val crop = CropEngine.crop(source, candidate)
        var rectified: Mat? = null
        var cleaned: Mat? = null
        var resized: Mat? = null
        try {
            val bgrCrop = Mat()
            try {
                when (crop.image.channels()) {
                    1 -> Imgproc.cvtColor(crop.image, bgrCrop, Imgproc.COLOR_GRAY2BGR)
                    3 -> crop.image.copyTo(bgrCrop)
                    4 -> Imgproc.cvtColor(crop.image, bgrCrop, Imgproc.COLOR_BGRA2BGR)
                    else -> throw IllegalArgumentException(
                        "Unsupported image channel count: ${crop.image.channels()}",
                    )
                }
                rectified = PerspectiveNormalizer.rectifyCrop(bgrCrop, kind)
            } finally {
                bgrCrop.release()
            }
            cleaned = BorderCleaner.clean(rectified, kind)
            val output = Mat()
            resized = output
            Imgproc.resize(
                cleaned,
                output,
                Size(widthPx.toDouble(), heightPx.toDouble()),
                0.0,
                0.0,
                if (kind == DetectionKind.PHOTO) Imgproc.INTER_AREA else Imgproc.INTER_CUBIC,
            )

            val outputBounds = RectF(
                crop.bounds.left.toFloat(),
                crop.bounds.top.toFloat(),
                crop.bounds.right.toFloat(),
                crop.bounds.bottom.toFloat(),
            )
            resized = null // Ownership transfers to the caller on success.
            return NormalizedOutput(image = output, bounds = outputBounds)
        } finally {
            resized?.release()
            cleaned?.release()
            rectified?.release()
            crop.image.release()
        }
    }

    private fun mmToPx(mm: Double, dpi: Int): Int =
        (mm * dpi / 25.4).roundToInt()

    private fun defaultWidthMm(kind: DetectionKind): Double =
        if (kind == DetectionKind.PHOTO) 40.0 else 50.0

    private fun defaultHeightMm(kind: DetectionKind): Double =
        if (kind == DetectionKind.PHOTO) 50.0 else 20.0
}
