package org.techwithkaushik.formSnap.pipeline

import org.techwithkaushik.formSnap.OpenCvGeometry
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
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


    /**
     * Conservatively rectifies a photo crop only when a strong four-corner
     * contour occupies most of the crop. Signature crops are left untouched:
     * their long ink strokes must not be mistaken for a rectangular document.
     * The returned Mat is always owned by the caller.
     */
    fun rectifyCrop(source: Mat, kind: DetectionKind): Mat {
        require(!source.empty()) { "Crop image is empty" }
        if (kind != DetectionKind.PHOTO || source.cols() < 40 || source.rows() < 40) {
            return source.clone()
        }

        val gray = Mat()
        val edges = Mat()
        val closed = Mat()
        val hierarchy = Mat()
        val contours = ArrayList<MatOfPoint>()
        var kernel: Mat? = null
        try {
            when (source.channels()) {
                1 -> source.copyTo(gray)
                3 -> Imgproc.cvtColor(source, gray, Imgproc.COLOR_BGR2GRAY)
                4 -> Imgproc.cvtColor(source, gray, Imgproc.COLOR_BGRA2GRAY)
                else -> return source.clone()
            }
            Imgproc.GaussianBlur(gray, gray, org.opencv.core.Size(3.0, 3.0), 0.0)
            Imgproc.Canny(gray, edges, 45.0, 140.0)
            kernel = Imgproc.getStructuringElement(
                Imgproc.MORPH_RECT,
                org.opencv.core.Size(3.0, 3.0),
            )
            Imgproc.morphologyEx(edges, closed, Imgproc.MORPH_CLOSE, kernel)
            Imgproc.findContours(
                closed,
                contours,
                hierarchy,
                Imgproc.RETR_LIST,
                Imgproc.CHAIN_APPROX_SIMPLE,
            )

            val imageArea = source.cols().toDouble() * source.rows().toDouble()
            var bestQuad: Array<Point>? = null
            var bestScore = 0.0
            for (contour in contours) {
                val contour2f = MatOfPoint2f(*contour.toArray())
                val approx = MatOfPoint2f()
                var polygon: MatOfPoint? = null
                try {
                    val perimeter = Imgproc.arcLength(contour2f, true)
                    if (perimeter < 1.0) continue
                    Imgproc.approxPolyDP(contour2f, approx, perimeter * 0.02, true)
                    val points = approx.toArray()
                    if (points.size != 4) continue

                    polygon = MatOfPoint(*points)
                    if (!Imgproc.isContourConvex(polygon)) continue
                    val area = abs(Imgproc.contourArea(polygon))
                    val areaRatio = area / max(1.0, imageArea)
                    if (areaRatio !in 0.40..0.97) continue

                    val bounds = Imgproc.boundingRect(polygon)
                    if (bounds.width < source.cols() * 0.35 ||
                        bounds.height < source.rows() * 0.35
                    ) continue

                    val ratio = bounds.width.toDouble() / max(1, bounds.height)
                    if (ratio !in 0.30..2.20) continue
                    val margin = min(
                        min(bounds.x, bounds.y),
                        min(source.cols() - bounds.x - bounds.width,
                            source.rows() - bounds.y - bounds.height),
                    )
                    // Reject the crop's own outer border; it is not evidence of
                    // a photographed object and often produces a bad warp.
                    if (margin < 2) continue

                    val score = areaRatio * (1.0 - abs(1.0 - ratio).coerceAtMost(1.0) * 0.10)
                    if (score > bestScore) {
                        bestScore = score
                        bestQuad = points
                    }
                } finally {
                    polygon?.release()
                    approx.release()
                    contour2f.release()
                }
            }

            val quad = bestQuad ?: return source.clone()
            return warp(source, quad) ?: source.clone()
        } finally {
            contours.forEach { it.release() }
            kernel?.release()
            hierarchy.release()
            closed.release()
            edges.release()
            gray.release()
        }
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
        var matrix: Mat? = null
        val output = Mat()
        var keepOutput = false
        try {
            val transform = OpenCvGeometry.getPerspectiveTransform(src, dst)
            matrix = transform
            Imgproc.warpPerspective(
                source,
                output,
                transform,
                org.opencv.core.Size(width.toDouble(), height.toDouble()),
            )
            keepOutput = true
            return output
        } finally {
            matrix?.release()
            src.release()
            dst.release()
            if (!keepOutput) output.release()
        }
    }

    private fun distance(a: Point, b: Point): Double {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }
}
