package org.techwithkaushik.formSnap.pipeline

import android.graphics.RectF
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Scalar
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
        var fittedContent: Mat? = null
        var output: Mat? = null
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
                val rectifiedCrop = PerspectiveNormalizer.rectifyCrop(bgrCrop, kind)
                rectified = rectifiedCrop
            } finally {
                bgrCrop.release()
            }
            val content = Mat()
            fittedContent = content
            val normalized = rectified ?: throw IllegalStateException("Crop normalization failed")
            val scale = minOf(
                widthPx.toDouble() / normalized.cols(),
                heightPx.toDouble() / normalized.rows(),
            )
            val contentWidth = (normalized.cols() * scale).roundToInt().coerceIn(1, widthPx)
            val contentHeight = (normalized.rows() * scale).roundToInt().coerceIn(1, heightPx)
            Imgproc.resize(
                normalized,
                content,
                Size(contentWidth.toDouble(), contentHeight.toDouble()),
                0.0,
                0.0,
                if (kind == DetectionKind.PHOTO) Imgproc.INTER_AREA else Imgproc.INTER_CUBIC,
            )

            val left = (widthPx - contentWidth) / 2
            val right = widthPx - contentWidth - left
            val top = (heightPx - contentHeight) / 2
            val bottom = heightPx - contentHeight - top
            val fitted = Mat()
            output = fitted
            if (kind == DetectionKind.PHOTO) {
                // Replicate edge pixels for portrait crops instead of distorting
                // faces or clipping content to force the requested aspect ratio.
                Core.copyMakeBorder(content, fitted, top, bottom, left, right, Core.BORDER_REPLICATE)
            } else {
                // A clean white canvas prevents stretched ink and keeps signature
                // strokes intact when the detected region is not exactly 5:2.
                Core.copyMakeBorder(
                    content, fitted, top, bottom, left, right,
                    Core.BORDER_CONSTANT, Scalar(255.0, 255.0, 255.0),
                )
            }

            val outputBounds = RectF(
                crop.bounds.left.toFloat(),
                crop.bounds.top.toFloat(),
                crop.bounds.right.toFloat(),
                crop.bounds.bottom.toFloat(),
            )
            output = null // Ownership transfers to the caller on success.
            return NormalizedOutput(image = fitted, bounds = outputBounds)
        } finally {
            output?.release()
            fittedContent?.release()
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
