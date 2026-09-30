package org.techwithkaushik.formSnap.pipeline

import org.opencv.core.Mat
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

data class CropOutput(val image: Mat)

object CropEngine {

    fun crop(source: Mat, candidate: DetectionCandidate): CropOutput {
        require(!source.empty()) { "Source image is empty" }
        require(source.cols() > 0 && source.rows() > 0) { "Source image has invalid dimensions" }

        val bounds = candidate.bounds
        require(
            bounds.left.isFinite() && bounds.top.isFinite() &&
                bounds.right.isFinite() && bounds.bottom.isFinite(),
        ) { "Candidate bounds must be finite" }
        require(bounds.right > bounds.left && bounds.bottom > bounds.top) {
            "Candidate bounds must have positive width and height"
        }

        // Clip before integer conversion. A candidate completely outside the
        // source must not silently turn into a one-pixel edge crop.
        val clippedLeft = max(0f, bounds.left)
        val clippedTop = max(0f, bounds.top)
        val clippedRight = min(source.cols().toFloat(), bounds.right)
        val clippedBottom = min(source.rows().toFloat(), bounds.bottom)
        require(clippedRight > clippedLeft && clippedBottom > clippedTop) {
            "Candidate bounds do not intersect the source image"
        }

        // Floor leading edges and ceil trailing edges to preserve fractional
        // boundary pixels rather than accidentally clipping detected content.
        val leftBase = floor(clippedLeft.toDouble()).toInt().coerceIn(0, source.cols() - 1)
        val topBase = floor(clippedTop.toDouble()).toInt().coerceIn(0, source.rows() - 1)
        val rightBase = ceil(clippedRight.toDouble()).toInt().coerceIn(leftBase + 1, source.cols())
        val bottomBase = ceil(clippedBottom.toDouble()).toInt().coerceIn(topBase + 1, source.rows())

        val rawWidth = rightBase - leftBase
        val rawHeight = bottomBase - topBase
        // Printed-frame candidates have already been inset from the detected
        // border. Padding them would reintroduce the very border we removed.
        // Unframed signature/photo candidates get a small safety margin so thin
        // strokes and edge-adjacent image content are not clipped.
        val paddingX = if (candidate.hasPrintedFrame) 0 else max(2, (rawWidth * 0.025f).toInt())
        val paddingY = if (candidate.hasPrintedFrame) 0 else max(2, (rawHeight * 0.04f).toInt())

        val left = max(0, leftBase - paddingX)
        val top = max(0, topBase - paddingY)
        val right = min(source.cols(), rightBase + paddingX)
        val bottom = min(source.rows(), bottomBase + paddingY)
        val width = right - left
        val height = bottom - top

        val roi = source.submat(org.opencv.core.Rect(left, top, width, height))
        val output = Mat()
        try {
            roi.copyTo(output)
            return CropOutput(output)
        } catch (failure: Throwable) {
            output.release()
            throw failure
        } finally {
            roi.release()
        }
    }

}
