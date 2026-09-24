package com.example.form_snap

import android.content.Context
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/*
 * Native port of the user's working Python OpenCV extractors.
 *
 * Whole form = form_cropper.py
 * Close capture = close_up_cropping.py / bulk_folder_cropper.py
 *
 * The important detection rules are intentionally kept the same:
 * adaptiveThreshold -> RETR_EXTERNAL -> boundingRect -> area > 5%.
 */
object FormSnapOpenCvProcessor {

    @JvmStatic
    fun process(context: Context, args: Map<*, *>): Map<String, Any?> {
        val sourcePath = args["sourcePath"] as? String
            ?: error("sourcePath is required")
        val mode = args["mode"] as? String ?: "wholeForm"

        val source = Imgcodecs.imread(sourcePath, Imgcodecs.IMREAD_COLOR)
        if (source.empty()) error("OpenCV could not decode the selected image")

        return try {
            when (mode) {
                "wholeForm" -> processWholeForm(context, source)
                "closePhoto" -> processCloseUp(context, source, true)
                "closeSignature" -> processCloseUp(context, source, false)
                "inspect" -> mapOf(
                    "width" to source.cols(),
                    "height" to source.rows(),
                )
                else -> error("Unknown capture mode: " + mode)
            }
        } finally {
            source.release()
        }
    }

    // Exact algorithm from form_cropper.py.
    private fun processWholeForm(
        context: Context,
        source: Mat,
    ): Map<String, Any?> {
        val h = source.rows()
        val w = source.cols()
        val roiStartX = (w * 0.55).toInt().coerceIn(0, w - 1)
        val roi = source.submat(0, h, roiStartX, w)

        try {
            val boxes = findWholeFormBoxes(roi)
            val pad = 6

            val photoCrop = if (boxes.first != null) {
                cropWithPadding(roi, boxes.first!!, pad)
            } else {
                cropNormalized(source, 0.20, 0.325, 0.75, 0.925, 6)
            }

            val signCrop = if (boxes.second != null) {
                cropWithPadding(roi, boxes.second!!, pad)
            } else {
                cropNormalized(source, 0.373, 0.432, 0.745, 0.930, 7)
            }

            val photo = enhancePhotoQuality(photoCrop)
            val sign = enhanceSignQuality(signCrop)
            photoCrop.release()
            signCrop.release()

            val photoPath = saveJpeg(context, photo, "photo", 40.0, 50.0, 50)
            val signPath = saveJpeg(context, sign, "signature", 50.0, 20.0, 50)
            photo.release()
            sign.release()

            return mapOf(
                "photoPath" to photoPath,
                "signaturePath" to signPath,
                "photoDetected" to (boxes.first != null),
                "signatureDetected" to (boxes.second != null),
                "detector" to "python-form-cropper",
            )
        } finally {
            roi.release()
        }
    }

    // Returns photo box and signature box independently, selecting the
    // largest matching contour exactly as form_cropper.py does.
    private fun findWholeFormBoxes(roi: Mat): Pair<Rect?, Rect?> {
        val gray = Mat()
        val blurred = Mat()
        val threshold = Mat()

        Imgproc.cvtColor(roi, gray, Imgproc.COLOR_BGR2GRAY)
        Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)
        Imgproc.adaptiveThreshold(
            blurred, threshold, 255.0,
            Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
            Imgproc.THRESH_BINARY_INV,
            15, 5.0,
        )

        val contours = ArrayList<MatOfPoint>()
        Imgproc.findContours(
            threshold, contours, Mat(),
            Imgproc.RETR_EXTERNAL,
            Imgproc.CHAIN_APPROX_SIMPLE,
        )

        val minArea = roi.rows().toDouble() * roi.cols().toDouble() * 0.05
        var photo: Rect? = null
        var sign: Rect? = null
        var maxPhotoArea = 0.0
        var maxSignArea = 0.0

        for (contour in contours) {
            val box = Imgproc.boundingRect(contour)
            val area = box.width.toDouble() * box.height.toDouble()

            if (area > minArea && box.height > 0) {
                val ratio = box.width.toDouble() / box.height.toDouble()

                if (
                    box.width > roi.cols() * 0.30 &&
                    box.height > roi.rows() * 0.10 &&
                    ratio > 0.6 && ratio < 1.0 &&
                    area > maxPhotoArea
                ) {
                    maxPhotoArea = area
                    photo = Rect(box.x, box.y, box.width, box.height)
                }

                if (
                    box.width > roi.cols() * 0.30 &&
                    box.height > roi.rows() * 0.04 &&
                    ratio > 1.8 && ratio < 3.2 &&
                    area > maxSignArea
                ) {
                    maxSignArea = area
                    sign = Rect(box.x, box.y, box.width, box.height)
                }
            }
            contour.release()
        }

        gray.release()
        blurred.release()
        threshold.release()

        return Pair(photo, sign)
    }

    // Exact algorithm from close_up_cropping.py / bulk_folder_cropper.py.
    private fun processCloseUp(
        context: Context,
        source: Mat,
        isPhoto: Boolean,
    ): Map<String, Any?> {
        val boxes = findCloseUpBoxes(source)
        val box = if (isPhoto) boxes.photo else boxes.signature

        if (box == null) {
            val ratio = if (isPhoto) 0.8 else 2.5
            val crop = centerCrop(source, ratio)
            val finalImage = if (isPhoto) {
                val clean = removeBlackBorderLines(crop)
                val result = enhanceCloseUpPhoto(clean)
                clean.release()
                result
            } else {
                val clean = removeBlackBorderLines(crop)
                val result = enhanceCloseUpSignature(clean)
                clean.release()
                result
            }
            crop.release()

            val path = if (isPhoto) {
                saveJpeg(context, finalImage, "photo", 40.0, 50.0, 50)
            } else {
                saveJpeg(context, finalImage, "signature", 50.0, 20.0, 50)
            }
            finalImage.release()

            return mapOf(
                "photoPath" to if (isPhoto) path else null,
                "signaturePath" to if (!isPhoto) path else null,
                "photoDetected" to false,
                "signatureDetected" to false,
                "detector" to "close-up-center-fallback",
            )
        }

        val crop = cropWithPadding(source, box, if (isPhoto) 10 else 8)
        val finalImage = if (isPhoto) {
            val clean = removeBlackBorderLines(crop)
            val result = enhanceCloseUpPhoto(clean)
            clean.release()
            result
        } else {
            val clean = removeBlackBorderLines(crop)
            val result = enhanceCloseUpSignature(clean)
            clean.release()
            result
        }
        crop.release()

        val path = if (isPhoto) {
            saveJpeg(context, finalImage, "photo", 40.0, 50.0, 50)
        } else {
            saveJpeg(context, finalImage, "signature", 50.0, 20.0, 50)
        }
        finalImage.release()

        return mapOf(
            "photoPath" to if (isPhoto) path else null,
            "signaturePath" to if (!isPhoto) path else null,
            "photoDetected" to isPhoto,
            "signatureDetected" to !isPhoto,
            "detector" to "close-up-aspect-rectangle",
        )
    }

    private data class CloseUpBoxes(
        val photo: Rect?,
        val signature: Rect?,
    )

    private fun findCloseUpBoxes(source: Mat): CloseUpBoxes {
        val gray = Mat()
        val blurred = Mat()
        val threshold = Mat()

        Imgproc.cvtColor(source, gray, Imgproc.COLOR_BGR2GRAY)
        Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)
        Imgproc.adaptiveThreshold(
            blurred, threshold, 255.0,
            Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
            Imgproc.THRESH_BINARY_INV,
            15, 5.0,
        )

        val contours = ArrayList<MatOfPoint>()
        Imgproc.findContours(
            threshold, contours, Mat(),
            Imgproc.RETR_LIST,
            Imgproc.CHAIN_APPROX_SIMPLE,
        )

        val minArea = source.rows().toDouble() * source.cols().toDouble() * 0.05
        val photoCandidates = ArrayList<Pair<Rect, Double>>()
        val signatureCandidates = ArrayList<Pair<Rect, Double>>()

        for (contour in contours) {
            val box = Imgproc.boundingRect(contour)
            val area = box.width.toDouble() * box.height.toDouble()
            if (area > minArea && box.height > 0) {
                val ratio = box.width.toDouble() / box.height.toDouble()
                val rectangularity = abs(Imgproc.contourArea(contour)) / area

                if (ratio > 0.68 && ratio < 0.95 && rectangularity > 0.80) {
                    val ratioScore = 1.0 - min(1.0, kotlin.math.abs(ratio - 0.80) / 0.15)
                    val score = ratioScore * 0.65 + rectangularity * 0.35
                    photoCandidates.add(Rect(box.x, box.y, box.width, box.height) to score)
                }

                if (ratio > 2.0 && ratio < 2.9 && rectangularity > 0.85) {
                    val ratioScore = 1.0 - min(1.0, kotlin.math.abs(ratio - 2.5) / 0.45)
                    val score = ratioScore * 0.65 + rectangularity * 0.35
                    signatureCandidates.add(Rect(box.x, box.y, box.width, box.height) to score)
                }
            }
            contour.release()
        }

        gray.release()
        blurred.release()
        threshold.release()

        // Prefer the innermost/largest-content photo rectangle rather than the
        // outer printed frame. If several nested rectangles have the same ratio,
        // the smaller one is the actual photo window. For signature there is
        // normally one printed rectangle, so use the highest geometric score.
        // Nested photo frames produce several nearly identical scores.
        // Keep candidates close to the best score, then choose the smallest
        // rectangle: that is the actual inner photo window, not its frame.
        val photoBestScore = photoCandidates.maxOfOrNull { it.second }
        val photo = if (photoBestScore != null) {
            photoCandidates
                .filter { it.second >= photoBestScore - 0.03 }
                .minByOrNull { it.first.width.toLong() * it.first.height.toLong() }
                ?.first
        } else null

        val signature = signatureCandidates
            .maxByOrNull { it.second }
            ?.first

        return CloseUpBoxes(photo, signature)
    }

    // Exact form_cropper.py photo enhancement.
    private fun enhancePhotoQuality(cropped: Mat): Mat {
        val enlarged = Mat()
        Imgproc.resize(
            cropped, enlarged, Size(),
            2.0, 2.0, Imgproc.INTER_LANCZOS4,
        )

        val blur = Mat()
        Imgproc.GaussianBlur(enlarged, blur, Size(), 2.0)

        val sharpened = Mat()
        Core.addWeighted(enlarged, 1.8, blur, -0.8, 0.0, sharpened)

        val finalPhoto = Mat()
        sharpened.convertTo(finalPhoto, -1, 1.05, 2.0)

        enlarged.release()
        blur.release()
        sharpened.release()
        return finalPhoto
    }

    // Exact form_cropper.py signature enhancement.
    private fun enhanceSignQuality(cropped: Mat): Mat {
        val enlarged = Mat()
        Imgproc.resize(
            cropped, enlarged, Size(),
            2.0, 2.0, Imgproc.INTER_CUBIC,
        )

        val gray = Mat()
        Imgproc.cvtColor(enlarged, gray, Imgproc.COLOR_BGR2GRAY)

        val clean = Mat()
        Imgproc.adaptiveThreshold(
            gray, clean, 255.0,
            Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
            Imgproc.THRESH_BINARY,
            25, 12.0,
        )

        val result = Mat()
        Imgproc.cvtColor(clean, result, Imgproc.COLOR_GRAY2BGR)

        enlarged.release()
        gray.release()
        clean.release()
        return result
    }

    // Exact close_up_cropping.py / bulk_folder_cropper.py photo sharpening.
    private fun enhanceCloseUpPhoto(cropped: Mat): Mat {
        val kernel = Mat(3, 3, CvType.CV_32F)
        kernel.put(
            0, 0,
            0.0, -0.5, 0.0,
            -0.5, 3.0, -0.5,
            0.0, -0.5, 0.0,
        )

        val result = Mat()
        Imgproc.filter2D(cropped, result, -1, kernel)
        kernel.release()
        return result
    }

    private fun enhanceCloseUpSignature(cropped: Mat): Mat {
        val gray = Mat()
        Imgproc.cvtColor(cropped, gray, Imgproc.COLOR_BGR2GRAY)

        val clean = Mat()
        Imgproc.adaptiveThreshold(
            gray, clean, 255.0,
            Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
            Imgproc.THRESH_BINARY,
            25, 12.0,
        )

        val result = Mat()
        Imgproc.cvtColor(clean, result, Imgproc.COLOR_GRAY2BGR)

        gray.release()
        clean.release()
        return result
    }

    // Exact border cleanup used by both close-up Python scripts.
    private fun removeBlackBorderLines(crop: Mat): Mat {
        val gray = Mat()
        Imgproc.cvtColor(crop, gray, Imgproc.COLOR_BGR2GRAY)

        val threshold = Mat()
        Imgproc.threshold(
            gray, threshold, 60.0, 255.0, Imgproc.THRESH_BINARY_INV,
        )

        val h = threshold.rows()
        val w = threshold.cols()
        var top = 0
        var bottom = h
        var left = 0
        var right = w

        for (r in 0 until min(12, h)) {
            if (blackRowCount(threshold, r) > w * 0.15) top = r + 1
        }
        for (r in h - 1 downTo max(h - 12, 0)) {
            if (blackRowCount(threshold, r) > w * 0.15) bottom = r
        }
        for (c in 0 until min(12, w)) {
            if (blackColumnCount(threshold, c) > h * 0.15) left = c + 1
        }
        for (c in w - 1 downTo max(w - 12, 0)) {
            if (blackColumnCount(threshold, c) > h * 0.15) right = c
        }

        gray.release()
        threshold.release()

        val x1 = left.coerceIn(0, w - 1)
        val y1 = top.coerceIn(0, h - 1)
        val x2 = right.coerceIn(x1 + 1, w)
        val y2 = bottom.coerceIn(y1 + 1, h)
        return crop.submat(y1, y2, x1, x2).clone()
    }

    private fun blackRowCount(binary: Mat, row: Int): Int {
        var count = 0
        for (x in 0 until binary.cols()) {
            if (binary.get(row, x)[0] > 0.0) count++
        }
        return count
    }

    private fun blackColumnCount(binary: Mat, col: Int): Int {
        var count = 0
        for (y in 0 until binary.rows()) {
            if (binary.get(y, col)[0] > 0.0) count++
        }
        return count
    }

    private fun cropWithPadding(source: Mat, box: Rect, pad: Int): Mat {
        val x1 = (box.x + pad).coerceIn(0, source.cols() - 1)
        val y1 = (box.y + pad).coerceIn(0, source.rows() - 1)
        val x2 = (box.x + box.width - pad).coerceIn(x1 + 1, source.cols())
        val y2 = (box.y + box.height - pad).coerceIn(y1 + 1, source.rows())
        return source.submat(y1, y2, x1, x2).clone()
    }

    // left/top/right/bottom are normalized coordinates.
    private fun cropNormalized(
        source: Mat,
        top: Double,
        bottom: Double,
        left: Double,
        right: Double,
        pad: Int,
    ): Mat {
        val x1 = (source.cols() * left).toInt() + pad
        val y1 = (source.rows() * top).toInt() + pad
        val x2 = (source.cols() * right).toInt() - pad
        val y2 = (source.rows() * bottom).toInt() - pad

        val sx1 = x1.coerceIn(0, source.cols() - 1)
        val sy1 = y1.coerceIn(0, source.rows() - 1)
        val sx2 = x2.coerceIn(sx1 + 1, source.cols())
        val sy2 = y2.coerceIn(sy1 + 1, source.rows())

        return source.submat(sy1, sy2, sx1, sx2).clone()
    }

    private fun centerCrop(source: Mat, targetRatio: Double): Mat {
        var width = source.cols()
        var height = (width / targetRatio).toInt()

        if (height > source.rows()) {
            height = source.rows()
            width = (height * targetRatio).toInt()
        }

        val x = max(0, (source.cols() - width) / 2)
        val y = max(0, (source.rows() - height) / 2)

        return source.submat(
            y,
            min(source.rows(), y + height),
            x,
            min(source.cols(), x + width),
        ).clone()
    }

    private fun saveJpeg(
        context: Context,
        source: Mat,
        prefix: String,
        widthMm: Double,
        heightMm: Double,
        maxKb: Int,
    ): String {
        val targetW = max(1, (widthMm / 25.4 * 300.0).toInt())
        val targetH = max(1, (heightMm / 25.4 * 300.0).toInt())

        val resized = Mat()
        Imgproc.resize(
            source,
            resized,
            Size(targetW.toDouble(), targetH.toDouble()),
            0.0,
            0.0,
            Imgproc.INTER_LANCZOS4,
        )

        var low = 5
        var high = 95
        var best: ByteArray? = null

        while (low <= high) {
            val quality = (low + high) / 2
            val buffer = MatOfByte()
            val params = MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, quality)
            Imgcodecs.imencode(".jpg", resized, buffer, params)
            val bytes = buffer.toArray()
            buffer.release()
            params.release()

            if (bytes.size <= maxKb * 1024) {
                best = bytes
                low = quality + 1
            } else {
                high = quality - 1
            }
        }

        if (best == null) {
            val buffer = MatOfByte()
            val params = MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, 45)
            Imgcodecs.imencode(".jpg", resized, buffer, params)
            best = buffer.toArray()
            buffer.release()
            params.release()
        }

        val outputDir = File(context.cacheDir, "formsnap_outputs")
        outputDir.mkdirs()
        val output = File(
            outputDir,
            prefix + "_" + System.currentTimeMillis() + ".jpg",
        )

        FileOutputStream(output).use { it.write(best) }
        resized.release()
        return output.absolutePath
    }
}
