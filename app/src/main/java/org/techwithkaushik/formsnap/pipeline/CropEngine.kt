package org.techwithkaushik.formSnap.pipeline

import android.graphics.Rect
import org.opencv.core.Mat
import kotlin.math.max
import kotlin.math.min

data class CropOutput(
    val image: Mat,
    val bounds: Rect,
)

object CropEngine {

    fun crop(source: Mat, candidate: DetectionCandidate): CropOutput {
        require(!source.empty()) { "Source image is empty" }

        val rawLeft = candidate.bounds.left.toInt()
        val rawTop = candidate.bounds.top.toInt()
        val rawRight = candidate.bounds.right.toInt()
        val rawBottom = candidate.bounds.bottom.toInt()

        val leftBase = max(0, min(source.cols() - 1, rawLeft))
        val topBase = max(0, min(source.rows() - 1, rawTop))
        val rightBase = max(leftBase + 1, min(source.cols(), rawRight))
        val bottomBase = max(topBase + 1, min(source.rows(), rawBottom))

        val rawWidth = max(1, rightBase - leftBase)
        val rawHeight = max(1, bottomBase - topBase)

        val paddingX = max(4, (rawWidth * 0.06f).toInt())
        val paddingY = max(4, (rawHeight * 0.10f).toInt())

        val left = max(0, leftBase - paddingX)
        val top = max(0, topBase - paddingY)
        val right = min(source.cols(), rightBase + paddingX)
        val bottom = min(source.rows(), bottomBase + paddingY)

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
