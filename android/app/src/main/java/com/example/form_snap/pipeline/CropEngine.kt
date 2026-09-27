package org.techwithkaushik.formSnap.pipeline

import android.graphics.Rect
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min

data class CropOutput(
    val image: Mat,
    val bounds: Rect,
)

object CropEngine {

    fun crop(source: Mat, candidate: DetectionCandidate): CropOutput {
        require(!source.empty()) { "Source image is empty" }

        val scaleX = source.cols().toFloat() / max(1f, source.cols().toFloat())
        val scaleY = source.rows().toFloat() / max(1f, source.rows().toFloat())

        val raw = Rect(
            candidate.bounds.left.toInt(),
            candidate.bounds.top.toInt(),
            candidate.bounds.width().toInt(),
            candidate.bounds.height().toInt(),
        )

        val paddingX = max(4, (raw.width * 0.06f).toInt())
        val paddingY = max(4, (raw.height * 0.10f).toInt())

        val left = max(0, raw.left - paddingX)
        val top = max(0, raw.top - paddingY)
        val right = min(source.cols(), raw.right + paddingX)
        val bottom = min(source.rows(), raw.bottom + paddingY)

        val width = max(1, right - left)
        val height = max(1, bottom - top)

        val roi = source.submat(
            org.opencv.core.Rect(left, top, width, height),
        )
        val output = Mat()
        roi.copyTo(output)
        roi.release()

        return CropOutput(
            image = output,
            bounds = Rect(left, top, right, bottom),
        )
    }

    fun normalizeOrientation(input: Mat): Mat {
        require(!input.empty()) { "Input image is empty" }
        val output = Mat()
        input.copyTo(output)
        return output
    }
}
