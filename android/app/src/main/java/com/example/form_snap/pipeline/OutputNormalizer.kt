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
    fun normalize(
        source: Mat,
        candidate: DetectionCandidate,
        kind: DetectionKind,
        dpi: Int = 300,
    ): NormalizedOutput {
        require(!source.empty()) { "Source image is empty" }

        val widthPx = max(1, mmToPx(defaultWidthMm(kind), dpi))
        val heightPx = max(1, mmToPx(defaultHeightMm(kind), dpi))
        val crop = CropEngine.crop(source, candidate)
        val cleaned = BorderCleaner.clean(crop.image, kind)
        crop.image.release()

        val resized = Mat()
        Imgproc.resize(
            cleaned,
            resized,
            Size(widthPx.toDouble(), heightPx.toDouble()),
            0.0,
            0.0,
            if (kind == DetectionKind.PHOTO) Imgproc.INTER_AREA else Imgproc.INTER_CUBIC,
        )
        cleaned.release()

        return NormalizedOutput(
            image = resized,
            bounds = RectF(
                crop.bounds.left.toFloat(),
                crop.bounds.top.toFloat(),
                crop.bounds.right.toFloat(),
                crop.bounds.bottom.toFloat(),
            ),
        )
    }

    private fun mmToPx(mm: Double, dpi: Int): Int =
        (mm * dpi / 25.4).roundToInt()

    private fun defaultWidthMm(kind: DetectionKind): Double =
        if (kind == DetectionKind.PHOTO) 40.0 else 50.0

    private fun defaultHeightMm(kind: DetectionKind): Double =
        if (kind == DetectionKind.PHOTO) 50.0 else 20.0
}
