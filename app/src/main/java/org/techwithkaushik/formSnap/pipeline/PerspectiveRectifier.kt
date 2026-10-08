package org.techwithkaushik.formSnap.pipeline

import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Finds a likely outer form boundary and rectifies perspective at full input
 * resolution. If no reliable document quadrilateral is found, the source is
 * returned unchanged.
 */
object PerspectiveRectifier {
    fun rectify(source: Mat): Result {
        if (source.empty() || source.cols() < 160 || source.rows() < 160) {
            return Result(source, false)
        }

        val gray = Mat()
        val blurred = Mat()
        val edges = Mat()
        val hierarchy = Mat()
        val contours = ArrayList<MatOfPoint>()

        try {
            Imgproc.cvtColor(source, gray, Imgproc.COLOR_BGR2GRAY)
            Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)
            Imgproc.Canny(blurred, edges, 60.0, 180.0)

            Imgproc.findContours(
                edges,
                contours,
                hierarchy,
                Imgproc.RETR_LIST,
                Imgproc.CHAIN_APPROX_SIMPLE,
            )

            val sourceArea = source.cols().toDouble() * source.rows().toDouble()
            val minArea = sourceArea * 0.55

            val candidate = contours
                .asSequence()
                .mapNotNull { contour ->
                    val area = abs(Imgproc.contourArea(contour))
                    if (area < minArea) return@mapNotNull null

                    val contour2f = MatOfPoint2f(*contour.toArray())
                    val perimeter = Imgproc.arcLength(contour2f, true)
                    if (perimeter <= 0.0) {
                        contour2f.release()
                        return@mapNotNull null
                    }

                    val approx = MatOfPoint2f()
                    Imgproc.approxPolyDP(contour2f, approx, 0.02 * perimeter, true)
                    contour2f.release()

                    val points = approx.toArray()
                    approx.release()

                    if (points.size != 4 || !isConvex(points)) {
                        null
                    } else {
                        val ordered = order(points)
                        val widthTop = distance(ordered[0], ordered[1])
                        val widthBottom = distance(ordered[3], ordered[2])
                        val heightLeft = distance(ordered[0], ordered[3])
                        val heightRight = distance(ordered[1], ordered[2])
                        val width = (widthTop + widthBottom) / 2.0
                        val height = (heightLeft + heightRight) / 2.0

                        if (width < 160.0 || height < 160.0) {
                            null
                        } else {
                            val rectangularity =
                                min(widthTop, widthBottom) / max(widthTop, widthBottom) *
                                    min(heightLeft, heightRight) / max(heightLeft, heightRight)
                            Candidate(ordered, area, rectangularity)
                        }
                    }
                }
                .filter { it.rectangularity >= 0.72 }
                .maxByOrNull { it.area * it.rectangularity }

            if (candidate == null) return Result(source, false)

            val points = candidate.points
            val width = max(
                distance(points[0], points[1]),
                distance(points[3], points[2]),
            ).coerceAtLeast(1.0)
            val height = max(
                distance(points[0], points[3]),
                distance(points[1], points[2]),
            ).coerceAtLeast(1.0)

            val maxOutputPixels = 16_000_000.0
            val scale = min(
                1.0,
                kotlin.math.sqrt(maxOutputPixels / (width * height)),
            )

            val destination = arrayOf(
                Point(0.0, 0.0),
                Point(width * scale - 1.0, 0.0),
                Point(width * scale - 1.0, height * scale - 1.0),
                Point(0.0, height * scale - 1.0),
            )

            val srcPoints = MatOfPoint2f(*points)
            val dstPoints = MatOfPoint2f(*destination)
            val transform = Imgproc.getPerspectiveTransform(srcPoints, dstPoints)
            srcPoints.release()
            dstPoints.release()

            val warped = Mat()
            Imgproc.warpPerspective(
                source,
                warped,
                transform,
                Size(width * scale, height * scale),
                Imgproc.INTER_CUBIC,
                Imgproc.BORDER_REPLICATE,
                Scalar(0.0, 0.0, 0.0),
            )
            transform.release()

            if (warped.empty()) {
                warped.release()
                return Result(source, false)
            }

            return Result(warped, true)
        } finally {
            gray.release()
            blurred.release()
            edges.release()
            hierarchy.release()
            contours.forEach { it.release() }
        }
    }

    data class Result(val image: Mat, val changed: Boolean)

    private data class Candidate(
        val points: Array<Point>,
        val area: Double,
        val rectangularity: Double,
    )

    private fun isConvex(points: Array<Point>): Boolean {
        var sign = 0
        for (i in points.indices) {
            val a = points[i]
            val b = points[(i + 1) % points.size]
            val c = points[(i + 2) % points.size]
            val cross =
                (b.x - a.x) * (c.y - b.y) -
                    (b.y - a.y) * (c.x - b.x)
            if (abs(cross) < 1e-3) continue
            val current = if (cross > 0) 1 else -1
            if (sign == 0) sign = current
            else if (sign != current) return false
        }
        return sign != 0
    }

    private fun order(points: Array<Point>): Array<Point> {
        val sums = points.map { it.x + it.y }
        val diffs = points.map { it.x - it.y }

        val topLeft = points[sums.indexOf(sums.minOrNull()!!)]
        val bottomRight = points[sums.indexOf(sums.maxOrNull()!!)]
        val topRight = points[diffs.indexOf(diffs.maxOrNull()!!)]
        val bottomLeft = points[diffs.indexOf(diffs.minOrNull()!!)]

        return arrayOf(topLeft, topRight, bottomRight, bottomLeft)
    }

    private fun distance(a: Point, b: Point): Double =
        hypot(a.x - b.x, a.y - b.y)
}
