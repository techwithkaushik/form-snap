package com.example.form_snap

import android.content.Context
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

object FormSnapOpenCvProcessor {
    private const val MAX_DETECTION_SIDE = 1800

    @JvmStatic
    fun process(context: Context, args: Map<*, *>): Map<String, Any?> {
        val sourcePath = args["sourcePath"] as? String ?: error("sourcePath is required")
        val mode = args["mode"] as? String ?: "wholeForm"
        val photo = args["photo"] as? Map<*, *> ?: emptyMap<String, Any>()
        val signature = args["signature"] as? Map<*, *> ?: emptyMap<String, Any>()
        val source = Imgcodecs.imread(sourcePath, Imgcodecs.IMREAD_COLOR)
        if (source.empty()) error("OpenCV could not decode the selected image")
        val w = source.cols()
        val h = source.rows()
        return try {
            when (mode) {
                "wholeForm" -> processWhole(context, source, photo, signature).toMutableMap().apply {
                    put("width", w); put("height", h)
                }
                "closePhoto" -> processClose(context, source, 0.8, 40.0, 50.0, 100, "photo").toMutableMap().apply {
                    put("width", w); put("height", h)
                }
                "closeSignature" -> processClose(context, source, 2.5, 50.0, 20.0, 60, "signature").toMutableMap().apply {
                    put("width", w); put("height", h)
                }
                "inspect" -> mapOf("width" to w, "height" to h)
                else -> error("Unknown capture mode: $mode")
            }
        } finally {
            source.release()
        }
    }

    private fun processWhole(context: Context, source: Mat, photo: Map<*, *>, signature: Map<*, *>): Map<String, Any?> {
        val page = detectDocument(source)
        var rectified = if (page != null) warpDocument(source, page) else source.clone()
        if (rectified.cols() > rectified.rows()) {
            val rotated = Mat()
            Core.rotate(rectified, rotated, Core.ROTATE_90_CLOCKWISE)
            rectified.release()
            rectified = rotated
        }

        val photoCrop = cropTemplate(rectified, photo, 0.035, 0.035)
        val signatureCrop = cropTemplate(rectified, signature, 0.035, 0.08)
        val photoPath = saveJpeg(context, photoCrop, "photo", 40.0, 50.0, 100)
        val signaturePath = saveJpeg(context, signatureCrop, "signature", 50.0, 20.0, 60)
        photoCrop.release()
        signatureCrop.release()
        rectified.release()

        return mapOf(
            "photoPath" to photoPath,
            "signaturePath" to signaturePath,
            "photoDetected" to (page != null),
            "signatureDetected" to (page != null),
            "detector" to if (page != null) "opencv-document-quad" else "template-fallback"
        )
    }

    private fun detectDocument(source: Mat): Array<Point>? {
        val scale = min(1.0, MAX_DETECTION_SIDE.toDouble() / max(source.cols(), source.rows()))
        val small = Mat()
        Imgproc.resize(source, small, Size(), scale, scale, Imgproc.INTER_AREA)
        val gray = Mat()
        Imgproc.cvtColor(small, gray, Imgproc.COLOR_BGR2GRAY)
        Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)

        val variants = ArrayList<Mat>()
        val canny = Mat()
        Imgproc.Canny(gray, canny, 35.0, 130.0)
        val k = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
        Imgproc.dilate(canny, canny, k)
        variants.add(canny)

        val adaptive = Mat()
        Imgproc.adaptiveThreshold(gray, adaptive, 255.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY, 31, 7.0)
        variants.add(adaptive)

        val imageArea = small.cols().toDouble() * small.rows()
        var best: Array<Point>? = null
        var bestScore = 0.0

        for (binary in variants) {
            val contours = ArrayList<MatOfPoint>()
            Imgproc.findContours(binary, contours, Mat(), Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
            for (contour in contours) {
                val area = abs(Imgproc.contourArea(contour))
                if (area < imageArea * 0.05) {
                    contour.release()
                    continue
                }
                val curve = MatOfPoint2f(*contour.toArray())
                val approx = MatOfPoint2f()
                Imgproc.approxPolyDP(curve, approx, Imgproc.arcLength(curve, true) * 0.018, true)
                if (approx.total() == 4L) {
                    val pts = order(approx.toArray())
                    val convex = Imgproc.isContourConvex(MatOfPoint(*pts))
                    if (convex) {
                        val score = documentScore(pts, area, imageArea)
                        if (score > bestScore) {
                            bestScore = score
                            best = pts
                        }
                    }
                }
                curve.release()
                approx.release()
                contour.release()
            }
        }

        variants.forEach { it.release() }
        k.release()
        gray.release()
        small.release()

        return best?.map { Point(it.x / scale, it.y / scale) }?.toTypedArray()
    }

    private fun documentScore(p: Array<Point>, area: Double, imageArea: Double): Double {
        val tl = p[0]; val tr = p[1]; val br = p[2]; val bl = p[3]
        val width = (distance(tl, tr) + distance(bl, br)) / 2.0
        val height = (distance(tl, bl) + distance(tr, br)) / 2.0
        if (width <= 0 || height <= 0) return 0.0
        val ratio = width / height
        val ratioQuality = 1.0 - min(1.0, abs(ratio - 0.7071) / 0.55)
        val angleQuality = 1.0 - min(1.0, max(max(cosine(tl, tr, br), cosine(tr, br, bl)), max(cosine(br, bl, tl), cosine(bl, tl, tr))) / 0.55)
        val areaRatio = area / imageArea
        return areaRatio * 0.55 + angleQuality * 0.30 + ratioQuality * 0.15
    }

    private fun warpDocument(source: Mat, points: Array<Point>): Mat {
        val p = order(points)
        val width = max(distance(p[0], p[1]), distance(p[3], p[2]))
        val height = max(distance(p[0], p[3]), distance(p[1], p[2]))
        val maxSide = 2200.0
        val scale = min(1.0, maxSide / max(width, height))
        val outW = max(1000, (width * scale).toInt())
        val outH = max(1400, (height * scale).toInt())
        val srcPts = MatOfPoint2f(*p)
        val dstPts = MatOfPoint2f(Point(0.0, 0.0), Point(outW - 1.0, 0.0), Point(outW - 1.0, outH - 1.0), Point(0.0, outH - 1.0))
        val matrix = Imgproc.getPerspectiveTransform(srcPts, dstPts)
        val out = Mat()
        Imgproc.warpPerspective(source, out, matrix, Size(outW.toDouble(), outH.toDouble()), Imgproc.INTER_CUBIC, Core.BORDER_REPLICATE)
        srcPts.release(); dstPts.release(); matrix.release()
        return out
    }

    private fun cropTemplate(image: Mat, region: Map<*, *>, xInset: Double, yInset: Double): Mat {
        val left = number(region["left"], 0.746)
        val top = number(region["top"], 0.190)
        val width = number(region["width"], 0.193)
        val height = number(region["height"], 0.169)

        val x = (image.cols() * left).toInt().coerceIn(0, image.cols() - 2)
        val y = (image.rows() * top).toInt().coerceIn(0, image.rows() - 2)
        val w = (image.cols() * width).toInt().coerceAtLeast(10).coerceAtMost(image.cols() - x)
        val h = (image.rows() * height).toInt().coerceAtLeast(10).coerceAtMost(image.rows() - y)
        val ix = (w * xInset).toInt()
        val iy = (h * yInset).toInt()
        val x1 = (x + ix).coerceAtMost(image.cols() - 1)
        val y1 = (y + iy).coerceAtMost(image.rows() - 1)
        val x2 = (x + w - ix).coerceIn(x1 + 1, image.cols())
        val y2 = (y + h - iy).coerceIn(y1 + 1, image.rows())
        return image.submat(y1, y2, x1, x2).clone()
    }

    private fun processClose(context: Context, source: Mat, targetRatio: Double, widthMm: Double, heightMm: Double, maxKb: Int, prefix: String): Map<String, Any?> {
        val quad = detectCloseRectangle(source, targetRatio)
        val crop = if (quad != null) perspectiveCrop(source, quad, targetRatio) else centerCrop(source, targetRatio)
        val path = saveJpeg(context, crop, prefix, widthMm, heightMm, maxKb)
        crop.release()
        return mapOf(
            "photoPath" to if (prefix == "photo") path else null,
            "signaturePath" to if (prefix == "signature") path else null,
            "photoDetected" to (prefix == "photo" && quad != null),
            "signatureDetected" to (prefix == "signature" && quad != null),
            "detector" to if (quad != null) "opencv-close-rectangle" else "center-fallback"
        )
    }

    private fun detectCloseRectangle(source: Mat, targetRatio: Double): Array<Point>? {
        val scale = min(1.0, 1600.0 / max(source.cols(), source.rows()))
        val small = Mat()
        Imgproc.resize(source, small, Size(), scale, scale, Imgproc.INTER_AREA)
        val gray = Mat()
        Imgproc.cvtColor(small, gray, Imgproc.COLOR_BGR2GRAY)
        Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)
        val edge = Mat()
        Imgproc.Canny(gray, edge, 30.0, 120.0)
        val k = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        Imgproc.dilate(edge, edge, k)

        val contours = ArrayList<MatOfPoint>()
        Imgproc.findContours(edge, contours, Mat(), Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
        val imageArea = small.cols().toDouble() * small.rows()
        var best: Array<Point>? = null
        var bestScore = 0.0

        for (contour in contours) {
            val area = abs(Imgproc.contourArea(contour))
            if (area < imageArea * 0.015) {
                contour.release()
                continue
            }
            val curve = MatOfPoint2f(*contour.toArray())
            val approx = MatOfPoint2f()
            Imgproc.approxPolyDP(curve, approx, Imgproc.arcLength(curve, true) * 0.025, true)
            if (approx.total() == 4L) {
                val pts = order(approx.toArray())
                val intPts = MatOfPoint(*pts)
                if (Imgproc.isContourConvex(intPts)) {
                    val w = (distance(pts[0], pts[1]) + distance(pts[3], pts[2])) / 2.0
                    val h = (distance(pts[0], pts[3]) + distance(pts[1], pts[2])) / 2.0
                    if (w > 30 && h > 20) {
                        val ratioError = abs((w / h) - targetRatio) / targetRatio
                        val ratioScore = max(0.0, 1.0 - ratioError * 2.5)
                        val areaScore = min(1.0, area / (imageArea * 0.70))
                        val score = areaScore * 0.65 + ratioScore * 0.35
                        if (score > bestScore) {
                            bestScore = score
                            best = pts.map { Point(it.x / scale, it.y / scale) }.toTypedArray()
                        }
                    }
                }
                intPts.release()
            }
            curve.release()
            approx.release()
            contour.release()
        }

        edge.release(); k.release(); gray.release(); small.release()
        return if (bestScore >= 0.45) best else null
    }

    private fun perspectiveCrop(source: Mat, points: Array<Point>, targetRatio: Double): Mat {
        val p = order(points)
        val rawW = max(distance(p[0], p[1]), distance(p[3], p[2]))
        val rawH = max(distance(p[0], p[3]), distance(p[1], p[2]))
        var outW = max(300, rawW.toInt())
        var outH = max(200, rawH.toInt())
        if (outW.toDouble() / outH > targetRatio) outH = max(1, (outW / targetRatio).toInt()) else outW = max(1, (outH * targetRatio).toInt())

        val srcPts = MatOfPoint2f(*p)
        val dstPts = MatOfPoint2f(Point(0.0, 0.0), Point(outW - 1.0, 0.0), Point(outW - 1.0, outH - 1.0), Point(0.0, outH - 1.0))
        val matrix = Imgproc.getPerspectiveTransform(srcPts, dstPts)
        val out = Mat()
        Imgproc.warpPerspective(source, out, matrix, Size(outW.toDouble(), outH.toDouble()), Imgproc.INTER_CUBIC, Core.BORDER_REPLICATE)
        srcPts.release(); dstPts.release(); matrix.release()
        val ix = max(1, (out.cols() * 0.035).toInt())
        val iy = max(1, (out.rows() * 0.035).toInt())
        val cropped = out.submat(iy, max(iy + 1, out.rows() - iy), ix, max(ix + 1, out.cols() - ix)).clone()
        out.release()
        return cropped
    }

    private fun centerCrop(source: Mat, targetRatio: Double): Mat {
        var w = source.cols()
        var h = (w / targetRatio).toInt()
        if (h > source.rows()) {
            h = source.rows()
            w = (h * targetRatio).toInt()
        }
        val x = max(0, (source.cols() - w) / 2)
        val y = max(0, (source.rows() - h) / 2)
        return source.submat(y, min(source.rows(), y + h), x, min(source.cols(), x + w)).clone()
    }

    private fun saveJpeg(context: Context, source: Mat, prefix: String, widthMm: Double, heightMm: Double, maxKb: Int): String {
        val targetW = max(1, (widthMm / 25.4 * 300.0).toInt())
        val targetH = max(1, (heightMm / 25.4 * 300.0).toInt())
        val resized = Mat()
        Imgproc.resize(source, resized, Size(targetW.toDouble(), targetH.toDouble()), 0.0, 0.0, Imgproc.INTER_LANCZOS4)

        var low = 45
        var high = 95
        var best: ByteArray? = null
        while (low <= high) {
            val q = (low + high) / 2
            val buffer = MatOfByte()
            val params = org.opencv.core.MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, q)
            Imgcodecs.imencode(".jpg", resized, buffer, params)
            val bytes = buffer.toArray()
            buffer.release(); params.release()
            if (bytes.size <= maxKb * 1024) {
                best = bytes
                low = q + 1
            } else high = q - 1
        }
        if (best == null) {
            val buffer = MatOfByte()
            val params = org.opencv.core.MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, 45)
            Imgcodecs.imencode(".jpg", resized, buffer, params)
            best = buffer.toArray()
            buffer.release(); params.release()
        }

        val dir = File(context.cacheDir, "formsnap_outputs")
        dir.mkdirs()
        val file = File(dir, prefix + "_" + System.currentTimeMillis() + ".jpg")
        FileOutputStream(file).use { it.write(best) }
        resized.release()
        return file.absolutePath
    }

    private fun order(points: Array<Point>): Array<Point> {
        require(points.size == 4)
        var tl = points[0]; var tr = points[0]; var br = points[0]; var bl = points[0]
        var minSum = Double.POSITIVE_INFINITY; var maxSum = Double.NEGATIVE_INFINITY
        var minDiff = Double.POSITIVE_INFINITY; var maxDiff = Double.NEGATIVE_INFINITY
        for (p in points) {
            val sum = p.x + p.y
            val diff = p.y - p.x
            if (sum < minSum) { minSum = sum; tl = p }
            if (sum > maxSum) { maxSum = sum; br = p }
            if (diff < minDiff) { minDiff = diff; tr = p }
            if (diff > maxDiff) { maxDiff = diff; bl = p }
        }
        return arrayOf(tl, tr, br, bl)
    }

    private fun cosine(a: Point, b: Point, c: Point): Double {
        val abx = a.x - b.x; val aby = a.y - b.y
        val cbx = c.x - b.x; val cby = c.y - b.y
        val denom = sqrt((abx * abx + aby * aby) * (cbx * cbx + cby * cby))
        if (denom == 0.0) return 1.0
        return abs((abx * cbx + aby * cby) / denom)
    }

    private fun distance(a: Point, b: Point): Double =
        sqrt((a.x - b.x).pow(2) + (a.y - b.y).pow(2))

    private fun number(v: Any?, fallback: Double): Double = if (v is Number) v.toDouble() else fallback
}
