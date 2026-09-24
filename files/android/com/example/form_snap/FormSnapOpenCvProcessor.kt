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

    // Whole-form extraction uses the same detector for camera and imported
    // images, but searches the full captured page so a photo placed near the
    // center of a tightly cropped input is not missed.
    private fun processWholeForm(
        context: Context,
        source: Mat,
    ): Map<String, Any?> {
        val boxes = findWholeFormBoxes(source)
        val pad = 6

        val photoCrop = if (boxes.first != null) {
            cropWithPadding(source, boxes.first!!, pad)
        } else {
            cropNormalized(source, 0.20, 0.325, 0.75, 0.925, pad)
        }

        val signCrop = if (boxes.second != null) {
            cropWithPadding(source, boxes.second!!, pad)
        } else {
            cropNormalized(source, 0.373, 0.432, 0.745, 0.930, 7)
        }

        // The detector returns the printed frame. Trim the remaining frame
        // line before enhancement so the saved output contains only the image/sign.
        val photoTrimmed = trimPrintedFrame(photoCrop, 15)
        val signTrimmed = trimPrintedFrame(signCrop, 15)
        val photoClean = removePrintedEdgeLines(photoTrimmed)
        val signClean = removePrintedEdgeLines(signTrimmed)
        val photo = enhancePhotoQuality(photoClean)
        val sign = enhanceSignQuality(signClean)
        photoTrimmed.release()
        signTrimmed.release()
        photoCrop.release()
        signCrop.release()
        photoClean.release()
        signClean.release()

        val photoPath = saveJpeg(context, photo, "photo", 40.0, 50.0, 50)
        val signPath = saveJpeg(context, sign, "signature", 50.0, 20.0, 50)
        photo.release()
        sign.release()

        return mapOf(
            "photoPath" to photoPath,
            "signaturePath" to signPath,
            "photoDetected" to (boxes.first != null),
            "signatureDetected" to (boxes.second != null),
            "detector" to "native-full-page-contour",
        )
    }


    // Detect the printed photo/signature boxes across the full image.
    // Camera and imported images use this exact same native detector.
    private fun findWholeFormBoxes(source: Mat): Pair<Rect?, Rect?> {
        val gray = Mat()
        val blurred = Mat()
        val threshold = Mat()

        Imgproc.cvtColor(source, gray, Imgproc.COLOR_BGR2GRAY)
        Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)
        Imgproc.adaptiveThreshold(
            blurred,
            threshold,
            255.0,
            Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
            Imgproc.THRESH_BINARY_INV,
            15,
            5.0,
        )

        val contours = ArrayList<MatOfPoint>()
        Imgproc.findContours(
            threshold,
            contours,
            Mat(),
            Imgproc.RETR_LIST,
            Imgproc.CHAIN_APPROX_SIMPLE,
        )

        val imageArea = source.rows().toDouble() * source.cols().toDouble()
        val minArea = imageArea * 0.015
        val photoCandidates = ArrayList<Pair<Rect, Double>>()
        val signatureCandidates = ArrayList<Pair<Rect, Double>>()

        for (contour in contours) {
            val box = Imgproc.boundingRect(contour)
            val area = box.width.toDouble() * box.height.toDouble()

            if (area > minArea && box.height > 0) {
                val ratio = box.width.toDouble() / box.height.toDouble()
                val rectangularity =
                    abs(Imgproc.contourArea(contour)) / max(1.0, area)

                if (
                    ratio > 0.62 &&
                    ratio < 1.00 &&
                    box.height > source.rows() * 0.08 &&
                    rectangularity > 0.70
                ) {
                    val ratioScore =
                        1.0 - min(1.0, abs(ratio - 0.80) / 0.20)
                    val score =
                        ratioScore * 0.65 +
                            rectangularity * 0.25 +
                            min(1.0, area / (imageArea * 0.20)) * 0.10
                    photoCandidates.add(
                        Rect(box.x, box.y, box.width, box.height) to score,
                    )
                }

                if (
                    ratio > 1.70 &&
                    ratio < 3.40 &&
                    box.width > source.cols() * 0.20 &&
                    rectangularity > 0.70
                ) {
                    val ratioScore =
                        1.0 - min(1.0, abs(ratio - 2.50) / 0.70)
                    val score =
                        ratioScore * 0.65 +
                            rectangularity * 0.25 +
                            min(1.0, area / (imageArea * 0.12)) * 0.10
                    signatureCandidates.add(
                        Rect(box.x, box.y, box.width, box.height) to score,
                    )
                }
            }

            contour.release()
        }

        gray.release()
        blurred.release()
        threshold.release()

        // The form has an outer printed frame and an inner actual image frame.
        // Among near-best candidates, select the smaller rectangle so the
        // outer frame is not returned as the photo.
        val photoBest = photoCandidates.maxOfOrNull { it.second }
        val photo = if (photoBest != null) {
            photoCandidates
                .filter { it.second >= photoBest - 0.08 }
                .minByOrNull {
                    it.first.width.toLong() * it.first.height.toLong()
                }
                ?.first
        } else {
            null
        }

        val signatureBest = signatureCandidates.maxOfOrNull { it.second }
        val signature = if (signatureBest != null) {
            signatureCandidates
                .filter { it.second >= signatureBest - 0.08 }
                .maxByOrNull {
                    it.first.width.toLong() * it.first.height.toLong()
                }
                ?.first
        } else {
            null
        }

        return Pair(photo, signature)
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
        val frameClean = trimPrintedFrame(crop, 15)
        val edgeClean = removePrintedEdgeLines(frameClean)
        val finalImage = if (isPhoto) {
            val clean = removeBlackBorderLines(edgeClean)
            val result = enhanceCloseUpPhoto(clean)
            clean.release()
            result
        } else {
            val clean = removeBlackBorderLines(edgeClean)
            val result = enhanceCloseUpSignature(clean)
            clean.release()
            result
        }
        crop.release()
        frameClean.release()
        edgeClean.release()

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

    // Photo cleanup is intentionally conservative.
    // The previous bilateral + unsharp-mask pass made the printed skin texture
    // look harsher and amplified small JPEG/sensor artifacts. A small median
    // filter removes isolated pixel noise without inventing facial detail.
    // No sharpening or contrast boost is applied here.
    private fun enhancePhotoQuality(cropped: Mat): Mat {
        val denoised = Mat()
        Imgproc.medianBlur(cropped, denoised, 3)

        val enlarged = Mat()
        Imgproc.resize(
            denoised, enlarged, Size(),
            2.0, 2.0, Imgproc.INTER_LANCZOS4,
        )

        denoised.release()
        return enlarged
    }
    // Signature is deliberately cleaned as ink-on-white instead of keeping
    // the photographed paper texture. Small isolated dust/noise components
    // are removed, while the connected handwritten strokes are preserved.
    private fun enhanceSignQuality(cropped: Mat): Mat {
        val gray = Mat()
        Imgproc.cvtColor(cropped, gray, Imgproc.COLOR_BGR2GRAY)

        // Suppress fine paper texture while preserving handwriting strokes.
        val background = Mat()
        Imgproc.GaussianBlur(gray, background, Size(0.0, 0.0), 15.0)

        val normalized = Mat()
        Core.subtract(background, gray, normalized)

        val binary = Mat()
        Imgproc.threshold(
            normalized, binary, 18.0, 255.0, Imgproc.THRESH_BINARY,
        )

        // Remove isolated dots but keep connected handwriting.
        val kernel = Imgproc.getStructuringElement(
            Imgproc.MORPH_ELLIPSE, Size(2.0, 2.0),
        )
        val opened = Mat()
        Imgproc.morphologyEx(binary, opened, Imgproc.MORPH_OPEN, kernel)

        val labels = Mat()
        val stats = Mat()
        val centroids = Mat()
        Imgproc.connectedComponentsWithStats(
            opened, labels, stats, centroids, 8, CvType.CV_32S,
        )

        val filtered = Mat.zeros(opened.size(), CvType.CV_8UC1)
        val minArea = max(18, (opened.rows() * opened.cols() * 0.00008).toInt())
        for (label in 1 until stats.rows()) {
            val area = stats.get(label, Imgproc.CC_STAT_AREA)[0].toInt()
            if (area >= minArea) {
                val mask = Mat()
                Core.compare(labels, org.opencv.core.Scalar(label.toDouble()), mask, Core.CMP_EQ)
                filtered.setTo(org.opencv.core.Scalar(255.0), mask)
                mask.release()
            }
        }

        // Tight crop around actual ink, with a small white margin.
        val contours = ArrayList<MatOfPoint>()
        Imgproc.findContours(
            filtered.clone(), contours, Mat(),
            Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE,
        )

        var union: Rect? = null
        for (contour in contours) {
            val r = Imgproc.boundingRect(contour)
            union = if (union == null) r else {
                val u = union!!
                val x1 = min(u.x, r.x)
                val y1 = min(u.y, r.y)
                val x2 = max(u.x + u.width, r.x + r.width)
                val y2 = max(u.y + u.height, r.y + r.height)
                Rect(x1, y1, x2 - x1, y2 - y1)
            }
            contour.release()
        }

        val inkCrop = if (union != null) {
            val r = union!!
            val margin = max(5, min(r.width, r.height) / 10)
            val x1 = (r.x - margin).coerceIn(0, filtered.cols() - 1)
            val y1 = (r.y - margin).coerceIn(0, filtered.rows() - 1)
            val x2 = (r.x + r.width + margin).coerceIn(x1 + 1, filtered.cols())
            val y2 = (r.y + r.height + margin).coerceIn(y1 + 1, filtered.rows())
            filtered.submat(y1, y2, x1, x2).clone()
        } else {
            Mat.zeros(80, 200, CvType.CV_8UC1)
        }

        val contentW = inkCrop.cols()
        val contentH = inkCrop.rows()
        val canvasW = max(contentW + 24, (contentH * 2.5).toInt())
        val canvasH = max(contentH + 24, (canvasW / 2.5).toInt())
        val canvas = Mat(
            canvasH, canvasW, CvType.CV_8UC1,
            org.opencv.core.Scalar(0.0),
        )

        val offsetX = (canvasW - contentW) / 2
        val offsetY = (canvasH - contentH) / 2
        val target = canvas.submat(
            offsetY, offsetY + contentH,
            offsetX, offsetX + contentW,
        )
        inkCrop.copyTo(target)
        target.release()

        // White background + black handwriting.
        Core.bitwise_not(canvas, canvas)

        val enlarged = Mat()
        Imgproc.resize(
            canvas, enlarged, Size(), 2.0, 2.0, Imgproc.INTER_LANCZOS4,
        )

        gray.release()
        background.release()
        normalized.release()
        binary.release()
        kernel.release()
        opened.release()
        labels.release()
        stats.release()
        centroids.release()
        filtered.release()
        inkCrop.release()
        canvas.release()

        return enlarged
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

    // The printed photo/signature frame can remain inside the detected
    // bounding rectangle. Remove a small safety inset from every side before
    // enhancement; this is intentionally shared by whole-form and close-up
    // capture so both camera and imported images behave identically.
    // Remove dark printed border rules that can survive the contour crop.
    // We only inspect a narrow edge band, so real face/signature content in the
    // center is never treated as a border.
    private fun removePrintedEdgeLines(crop: Mat): Mat {
        val result = crop.clone()
        val gray = Mat()
        Imgproc.cvtColor(result, gray, Imgproc.COLOR_BGR2GRAY)

        val dark = Mat()
        Imgproc.threshold(gray, dark, 105.0, 255.0, Imgproc.THRESH_BINARY_INV)

        // Long horizontal/vertical morphology isolates the printed box rules
        // without touching the face or handwritten strokes in the center.
        val hKernel = Imgproc.getStructuringElement(
            Imgproc.MORPH_RECT, Size(max(15, crop.cols() / 3).toDouble(), 1.0),
        )
        val vKernel = Imgproc.getStructuringElement(
            Imgproc.MORPH_RECT, Size(1.0, max(15, crop.rows() / 3).toDouble()),
        )
        val horizontal = Mat()
        val vertical = Mat()
        Imgproc.morphologyEx(dark, horizontal, Imgproc.MORPH_OPEN, hKernel)
        Imgproc.morphologyEx(dark, vertical, Imgproc.MORPH_OPEN, vKernel)

        // Only erase lines close to an outer edge. This protects actual
        // handwriting and facial details from being classified as a frame.
        val edgeMask = Mat.zeros(dark.size(), CvType.CV_8UC1)
        val edgeBandY = max(18, crop.rows() / 7)
        val edgeBandX = max(18, crop.cols() / 7)

        if (crop.rows() > 1) {
            horizontal.submat(
                0, min(edgeBandY, crop.rows()), 0, crop.cols(),
            ).copyTo(edgeMask.submat(
                0, min(edgeBandY, crop.rows()), 0, crop.cols(),
            ))
            horizontal.submat(
                max(0, crop.rows() - edgeBandY), crop.rows(), 0, crop.cols(),
            ).copyTo(edgeMask.submat(
                max(0, crop.rows() - edgeBandY), crop.rows(), 0, crop.cols(),
            ))
        }
        vertical.submat(
            0, crop.rows(), 0, min(edgeBandX, crop.cols()),
        ).copyTo(edgeMask.submat(
            0, crop.rows(), 0, min(edgeBandX, crop.cols()),
        ))
        vertical.submat(
            0, crop.rows(), max(0, crop.cols() - edgeBandX), crop.cols(),
        ).copyTo(edgeMask.submat(
            0, crop.rows(), max(0, crop.cols() - edgeBandX), crop.cols(),
        ))

        // Paint detected frame rules white, preserving the surrounding photo
        // rather than leaving a dark rectangular edge in the output.
        result.setTo(
            org.opencv.core.Scalar(255.0, 255.0, 255.0),
            edgeMask,
        )

        gray.release()
        dark.release()
        hKernel.release()
        vKernel.release()
        horizontal.release()
        vertical.release()
        edgeMask.release()
        return result
    }
    private fun trimPrintedFrame(crop: Mat, inset: Int): Mat {
        val safeX = min(inset, max(0, (crop.cols() - 2) / 4))
        val safeY = min(inset, max(0, (crop.rows() - 2) / 4))
        val x1 = safeX
        val y1 = safeY
        val x2 = max(x1 + 1, crop.cols() - safeX)
        val y2 = max(y1 + 1, crop.rows() - safeY)
        return crop.submat(y1, y2, x1, x2).clone()
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
