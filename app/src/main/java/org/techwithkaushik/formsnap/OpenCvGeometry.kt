package org.techwithkaushik.formSnap

import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Geometry operations kept in Kotlin so FormSnap does not depend on optional
 * Java wrappers for these functions in different OpenCV Android SDK releases.
 */
object OpenCvGeometry {
    fun contourArea(contour: MatOfPoint): Double = polygonArea(contour.toArray())

    fun boundingRect(contour: MatOfPoint): Rect = bounds(contour.toArray())
    fun boundingRect(contour: MatOfPoint2f): Rect = bounds(contour.toArray())

    fun arcLength(contour: MatOfPoint2f, closed: Boolean): Double {
        val points = contour.toArray()
        if (points.size < 2) return 0.0
        var length = 0.0
        for (i in 1 until points.size) length += distance(points[i - 1], points[i])
        if (closed) length += distance(points.last(), points.first())
        return length
    }

    fun isContourConvex(contour: MatOfPoint): Boolean {
        val points = contour.toArray()
        if (points.size < 3) return false
        var sign = 0
        for (i in points.indices) {
            val a = points[i]
            val b = points[(i + 1) % points.size]
            val c = points[(i + 2) % points.size]
            val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            if (abs(cross) < 1e-9) continue
            val nextSign = if (cross > 0.0) 1 else -1
            if (sign != 0 && sign != nextSign) return false
            sign = nextSign
        }
        return sign != 0
    }

    fun approxPolyDP(
        source: MatOfPoint2f,
        destination: MatOfPoint2f,
        epsilon: Double,
        closed: Boolean,
    ) {
        val points = source.toArray()
        if (points.size <= 2) {
            destination.fromArray(*points)
            return
        }
        val simplified = if (!closed) {
            simplifyOpen(points, epsilon)
        } else {
            val first = points.first()
            var farthest = 1
            var farthestDistance = -1.0
            for (i in 1 until points.size) {
                val d = squaredDistance(first, points[i])
                if (d > farthestDistance) {
                    farthestDistance = d
                    farthest = i
                }
            }
            val one = simplifyOpen(points.copyOfRange(0, farthest + 1), epsilon)
            val two = simplifyOpen((points.copyOfRange(farthest, points.size) + first), epsilon)
            (one.dropLast(1) + two.dropLast(1)).distinctBy { Pair(it.x, it.y) }.toTypedArray()
        }
        destination.fromArray(*simplified)
    }

    fun getPerspectiveTransform(source: MatOfPoint2f, destination: MatOfPoint2f): Mat {
        val src = source.toArray()
        val dst = destination.toArray()
        require(src.size == 4 && dst.size == 4) { "Perspective transform requires four source and destination points" }
        val equations = Array(8) { DoubleArray(9) }
        for (i in 0..3) {
            val x = src[i].x
            val y = src[i].y
            val u = dst[i].x
            val v = dst[i].y
            equations[i * 2] = doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -u * x, -u * y, u)
            equations[i * 2 + 1] = doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -v * x, -v * y, v)
        }
        val h = solve(equations) + 1.0
        val matrix = Mat(3, 3, CvType.CV_64F)
        matrix.put(0, 0, h[0], h[1], h[2], h[3], h[4], h[5], h[6], h[7], 1.0)
        return matrix
    }

    private fun bounds(points: Array<Point>): Rect {
        if (points.isEmpty()) return Rect()
        val left = floor(points.minOf { it.x }).toInt()
        val top = floor(points.minOf { it.y }).toInt()
        val right = ceil(points.maxOf { it.x }).toInt()
        val bottom = ceil(points.maxOf { it.y }).toInt()
        return Rect(left, top, max(1, right - left + 1), max(1, bottom - top + 1))
    }

    private fun polygonArea(points: Array<Point>): Double {
        if (points.size < 3) return 0.0
        var twiceArea = 0.0
        for (i in points.indices) {
            val a = points[i]
            val b = points[(i + 1) % points.size]
            twiceArea += a.x * b.y - b.x * a.y
        }
        return abs(twiceArea) / 2.0
    }

    private fun simplifyOpen(points: Array<Point>, epsilon: Double): List<Point> {
        if (points.size <= 2) return points.toList()
        val first = points.first()
        val last = points.last()
        var maxDistance = -1.0
        var index = -1
        for (i in 1 until points.lastIndex) {
            val d = perpendicularDistance(points[i], first, last)
            if (d > maxDistance) { maxDistance = d; index = i }
        }
        if (index >= 0 && maxDistance > epsilon) {
            val left = simplifyOpen(points.copyOfRange(0, index + 1), epsilon)
            val right = simplifyOpen(points.copyOfRange(index, points.size), epsilon)
            return left.dropLast(1) + right
        }
        return listOf(first, last)
    }

    private fun perpendicularDistance(p: Point, a: Point, b: Point): Double {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val length = hypot(dx, dy)
        return if (length == 0.0) distance(p, a)
        else abs(dy * p.x - dx * p.y + b.x * a.y - b.y * a.x) / length
    }

    private fun distance(a: Point, b: Point): Double = hypot(a.x - b.x, a.y - b.y)
    private fun squaredDistance(a: Point, b: Point): Double {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return dx * dx + dy * dy
    }

    private fun solve(input: Array<DoubleArray>): DoubleArray {
        val a = Array(input.size) { input[it].clone() }
        val n = a.size
        for (col in 0 until n) {
            var pivot = col
            for (row in col + 1 until n) {
                if (abs(a[row][col]) > abs(a[pivot][col])) pivot = row
            }
            require(abs(a[pivot][col]) > 1e-12) { "Perspective transform is degenerate" }
            val swap = a[col]
            a[col] = a[pivot]
            a[pivot] = swap
            val divisor = a[col][col]
            for (j in col..n) a[col][j] /= divisor
            for (row in 0 until n) {
                if (row == col) continue
                val factor = a[row][col]
                for (j in col..n) a[row][j] -= factor * a[col][j]
            }
        }
        return DoubleArray(n) { a[it][n] }
    }
}
