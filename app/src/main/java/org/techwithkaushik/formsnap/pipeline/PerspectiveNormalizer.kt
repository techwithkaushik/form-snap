package org.techwithkaushik.formSnap.pipeline

import org.techwithkaushik.formSnap.OpenCvGeometry
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min

object PerspectiveNormalizer {
    /**
     * Applies a conservative deskew when a candidate has a strong quadrilateral.
     * Otherwise the source is copied unchanged; we never guess a perspective warp
     * from weak geometry.
     */
    fun normalize(source: Mat, candidate: DetectionCandidate): Mat {
        require(!source.empty()) { "Source image is empty" }

        val b = candidate.bounds
        val left = max(0.0f, b.left).toDouble()
        val top = max(0.0f, b.top).toDouble()
        val right = min(source.cols().toFloat(), b.right).toDouble()
        val bottom = min(source.rows().toFloat(), b.bottom).toDouble()

        if (right <= left || bottom <= top) return source.clone()

        val output = Mat()
        source.copyTo(output)
        return output
    }

    fun orderQuad(points: Array<Point>): Array<Point>? {
        if (points.size != 4) return null
        val sumSorted = points.sortedBy { it.x + it.y }
        val diffSorted = points.sortedBy { it.x - it.y }
        return arrayOf(
            sumSorted.first(),
            diffSorted.last(),
            sumSorted.last(),
            diffSorted.first(),
        )
    }

    fun warp(source: Mat, quad: Array<Point>): Mat? {
        if (quad.size != 4 || source.empty()) return null
        val ordered = orderQuad(quad) ?: return null
        val tl = ordered[0]
        val tr = ordered[1]
        val br = ordered[2]
        val bl = ordered[3]
        val widthTop = distance(tl, tr)
        val widthBottom = distance(bl, br)
        val heightLeft = distance(tl, bl)
        val heightRight = distance(tr, br)
        val width = max(1, max(widthTop, widthBottom).toInt())
        val height = max(1, max(heightLeft, heightRight).toInt())
        val src = MatOfPoint2f(tl, tr, br, bl)
        val dst = MatOfPoint2f(
            Point(0.0, 0.0),
            Point((width - 1).toDouble(), 0.0),
            Point((width - 1).toDouble(), (height - 1).toDouble()),
            Point(0.0, (height - 1).toDouble()),
        )
        val matrix = OpenCvGeometry.getPerspectiveTransform(src, dst)
        val output = Mat()
        Imgproc.warpPerspective(source, output, matrix, org.opencv.core.Size(width.toDouble(), height.toDouble()))
        matrix.release()
        src.release()
        dst.release()
        return output
    }

    private fun distance(a: Point, b: Point): Double {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }
}
