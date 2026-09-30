package org.techwithkaushik.formSnap.pipeline

import org.techwithkaushik.formSnap.OpenCvGeometry
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Rect
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * First independent detector for the new FormSnap pipeline.
 *
 * It deliberately does not depend on the legacy FormSnapOpenCvProcessor.
 * Photo and signature detection are scored independently so that either,
 * both, or neither may be returned.
 */
object UniversalDetectionEngine {

    private data class ShapeCandidate(
        val rect: Rect,
        val score: Double,
        val rectangularity: Double,
        val edgeDensity: Double,
        val frameLike: Boolean,
    )

    fun detect(
        source: Mat,
        rejectedPhotoBounds: Set<android.graphics.RectF> = emptySet(),
        rejectedSignatureBounds: Set<android.graphics.RectF> = emptySet(),
    ): DetectionResult {
        require(!source.empty()) { "Source image is empty" }

        val work = Mat()
        val gray = Mat()
        val edges = Mat()
        val morph = Mat()

        try {
            val scale = min(
                1.0,
                1600.0 / max(source.cols(), source.rows()).toDouble(),
            )

            if (scale < 1.0) {
                Imgproc.resize(source, work, org.opencv.core.Size(), scale, scale)
            } else {
                source.copyTo(work)
            }

            when (work.channels()) {
                1 -> work.copyTo(gray)
                3 -> Imgproc.cvtColor(work, gray, Imgproc.COLOR_BGR2GRAY)
                4 -> Imgproc.cvtColor(work, gray, Imgproc.COLOR_BGRA2GRAY)
                else -> throw IllegalArgumentException(
                    "Unsupported image channel count: ${work.channels()}",
                )
            }
            Imgproc.GaussianBlur(gray, gray, org.opencv.core.Size(5.0, 5.0), 0.0)
            Imgproc.Canny(gray, edges, 45.0, 140.0)

            val kernel = Imgproc.getStructuringElement(
                Imgproc.MORPH_RECT,
                org.opencv.core.Size(5.0, 5.0),
            )
            try {
                Imgproc.morphologyEx(edges, morph, Imgproc.MORPH_CLOSE, kernel)
            } finally {
                kernel.release()
            }

            val candidates = collectCandidates(morph, gray, edges)
            // Rejection bounds are stored in original-image coordinates, while
            // candidates are measured on the downscaled working image.
            val scaledRejectedPhotos = scaleBounds(rejectedPhotoBounds, scale)
            val scaledRejectedSignatures = scaleBounds(rejectedSignatureBounds, scale)

            // Add ink-derived candidates so handwritten signatures can be found
            // even when the form has no printed signature box.
            val signatureCandidates = candidates + collectInkCandidates(gray, edges)

            val photo = selectPhoto(
                candidates,
                scaledRejectedPhotos,
                gray.cols(),
                gray.rows(),
            )
            val signature = selectSignature(
                signatureCandidates,
                gray,
                scaledRejectedSignatures,
            )

            val invScale = if (scale == 0.0) 1.0 else 1.0 / scale

            return DetectionResult(
                sourceWidth = source.cols(),
                sourceHeight = source.rows(),
                photo = photo?.toDetection(DetectionKind.PHOTO, invScale),
                signature = signature?.toDetection(DetectionKind.SIGNATURE, invScale),
            )
        } finally {
            morph.release()
            edges.release()
            gray.release()
            work.release()
        }
    }

    private fun collectCandidates(mask: Mat, gray: Mat, edgeMap: Mat): List<ShapeCandidate> {
        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(
            mask,
            contours,
            hierarchy,
            Imgproc.RETR_LIST,
            Imgproc.CHAIN_APPROX_SIMPLE,
        )

        val imageArea = mask.cols().toDouble() * mask.rows().toDouble()
        val result = ArrayList<ShapeCandidate>()

        try {
            for (contour in contours) {
                val rect = OpenCvGeometry.boundingRect(contour)
                val rectArea = rect.width.toDouble() * rect.height.toDouble()
                if (rectArea < imageArea * 0.003) {
                    continue
                }

                val contourArea = abs(OpenCvGeometry.contourArea(contour))
                val rectangularity = contourArea / max(1.0, rectArea)
                if (rectangularity < 0.45) {
                    continue
                }

                val ratio = rect.width.toDouble() / max(1, rect.height).toDouble()
                val edgeDensity = edgeDensity(edgeMap, rect)
                val frameLike = isFrameLike(edgeMap, rect)

                val score = shapeScore(
                    ratio = ratio,
                    rectangularity = rectangularity,
                    edgeDensity = edgeDensity,
                    frameLike = frameLike,
                    areaRatio = rectArea / imageArea,
                )

                result += ShapeCandidate(
                    rect = rect,
                    score = score,
                    rectangularity = rectangularity,
                    edgeDensity = edgeDensity,
                    frameLike = frameLike,
                )

            }
        } finally {
            contours.forEach { it.release() }
            hierarchy.release()
        }

        return result
            .sortedByDescending { it.score }
            .take(60)
    }

    /**
     * Finds horizontal ink groups independently of printed rectangles.
     * Vertical position is only a weak scoring cue: signatures may appear
     * above, below, or beside the photograph.
     */
    private fun collectInkCandidates(gray: Mat, edgeMap: Mat): List<ShapeCandidate> {
        val binary = Mat()
        val grouped = Mat()
        val hierarchy = Mat()
        var kernel: Mat? = null
        val contours = ArrayList<MatOfPoint>()
        try {
            Imgproc.threshold(
                gray,
                binary,
                0.0,
                255.0,
                Imgproc.THRESH_BINARY_INV or Imgproc.THRESH_OTSU,
            )
            kernel = Imgproc.getStructuringElement(
                Imgproc.MORPH_RECT,
                org.opencv.core.Size(17.0, 3.0),
            )
            Imgproc.morphologyEx(binary, grouped, Imgproc.MORPH_CLOSE, kernel)
            Imgproc.findContours(
                grouped,
                contours,
                hierarchy,
                Imgproc.RETR_EXTERNAL,
                Imgproc.CHAIN_APPROX_SIMPLE,
            )

            val imageArea = gray.cols().toDouble() * gray.rows().toDouble()
            val result = ArrayList<ShapeCandidate>()
            for (contour in contours) {
                val rect = OpenCvGeometry.boundingRect(contour)
                if (rect.width < 140 || rect.height < 18) continue
                if (rect.height > max(90, (gray.rows() * 0.12).toInt())) continue

                val ratio = rect.width.toDouble() / max(1, rect.height).toDouble()
                if (ratio !in 1.45..6.0) continue
                val rectArea = rect.width.toDouble() * rect.height.toDouble()
                if (rectArea > imageArea * 0.25) continue

                val ink = inkScore(gray, rect)
                if (ink < 0.012) continue

                val ratioFit = 1.0 - min(1.0, abs(ratio - 2.6) / 3.4)
                val widthScore = min(1.0, rect.width / max(1.0, gray.cols() * 0.35))
                val lowerPageScore = (rect.y.toDouble() / max(1, gray.rows())).coerceIn(0.0, 1.0)
                val edgeScore = min(1.0, edgeDensity(edgeMap, rect) / 0.28)
                val score = (
                    ratioFit * 0.30 +
                        min(1.0, ink * 4.0) * 0.35 +
                        widthScore * 0.15 +
                        lowerPageScore * 0.10 +
                        edgeScore * 0.10
                    ).coerceIn(0.0, 1.0)

                result += ShapeCandidate(
                    rect = rect,
                    score = score,
                    rectangularity = abs(OpenCvGeometry.contourArea(contour)) /
                        max(1.0, rectArea),
                    edgeDensity = edgeScore,
                    frameLike = false,
                )
            }
            return result.sortedByDescending { it.score }.take(40)
        } finally {
            contours.forEach { it.release() }
            kernel?.release()
            hierarchy.release()
            grouped.release()
            binary.release()
        }
    }

    private fun shapeScore(
        ratio: Double,
        rectangularity: Double,
        edgeDensity: Double,
        frameLike: Boolean,
        areaRatio: Double,
    ): Double {
        val commonRatio =
            if (ratio in 0.55..1.05) {
                1.0 - min(1.0, abs(ratio - 0.80) / 0.35)
            } else if (ratio in 1.5..4.0) {
                1.0 - min(1.0, abs(ratio - 2.5) / 1.5)
            } else {
                0.15
            }

        val sizeScore = min(1.0, max(0.0, areaRatio / 0.18))
        val edgeScore = min(1.0, edgeDensity / 0.28)
        val frameScore = if (frameLike) 1.0 else 0.0

        return (
            commonRatio * 0.32 +
                rectangularity.coerceIn(0.0, 1.0) * 0.28 +
                edgeScore * 0.16 +
                frameScore * 0.14 +
                sizeScore * 0.10
            ).coerceIn(0.0, 1.0)
    }

    private fun selectPhoto(
        candidates: List<ShapeCandidate>,
        rejectedBounds: Set<android.graphics.RectF>,
        imageWidth: Int,
        imageHeight: Int,
    ): ShapeCandidate? {
        val imageArea = imageWidth.toDouble() * imageHeight.toDouble()
        return candidates
            .asSequence()
            .filter { candidate -> !isRejected(candidate.rect, rejectedBounds) }
            // The outer sheet of paper is often portrait-shaped too. Exclude
            // large page-sized contours so they cannot win as a "photo".
            .filter {
                it.rect.width.toDouble() * it.rect.height.toDouble() <= imageArea * 0.45
            }
            .filter {
                val ratio = it.rect.width.toDouble() / max(1, it.rect.height).toDouble()
                ratio in 0.55..1.15
            }
            .filter { it.rect.width >= 120 && it.rect.height >= 120 }
            .maxByOrNull {
                val ratio = it.rect.width.toDouble() / max(1, it.rect.height).toDouble()
                it.score +
                    (1.0 - min(1.0, abs(ratio - 0.80) / 0.35)) * 0.20
            }
    }

    private fun selectSignature(
        candidates: List<ShapeCandidate>,
        gray: Mat,
        rejectedBounds: Set<android.graphics.RectF>,
    ): ShapeCandidate? {
        return candidates
            .asSequence()
            .filter { candidate -> !isRejected(candidate.rect, rejectedBounds) }
            .filter {
                val ratio = it.rect.width.toDouble() / max(1, it.rect.height).toDouble()
                ratio in 1.55..4.2
            }
            .filter { it.rect.width >= 140 && it.rect.height >= 20 }
            // Reject page-sized and large table regions; signatures occupy a
            // comparatively small area even when there is no printed frame.
            .filter {
                it.rect.width.toDouble() * it.rect.height.toDouble() <=
                    gray.cols().toDouble() * gray.rows().toDouble() * 0.25
            }
            // Printed labels and ordinary text often have a wide aspect ratio
            // and dark pixels, but their glyph heights are unusually uniform.
            // Require irregular connected-stroke geometry before treating ink
            // as a signature. A blank printed signature box is not a signature.
            .filter { hasInk(gray, it.rect) && looksHandwritten(gray, it.rect) }
            .maxByOrNull {
                val ratio = it.rect.width.toDouble() / max(1, it.rect.height).toDouble()
                val ratioFit = 1.0 - min(1.0, abs(ratio - 2.5) / 1.5)
                it.score + ratioFit * 0.20 + min(0.20, inkScore(gray, it.rect))
            }
    }

    private fun scaleBounds(
        bounds: Set<android.graphics.RectF>,
        scale: Double,
    ): Set<android.graphics.RectF> {
        if (scale == 1.0 || bounds.isEmpty()) return bounds
        return bounds.mapTo(mutableSetOf()) { rect ->
            android.graphics.RectF(
                (rect.left * scale).toFloat(),
                (rect.top * scale).toFloat(),
                (rect.right * scale).toFloat(),
                (rect.bottom * scale).toFloat(),
            )
        }
    }

    private fun isRejected(
        rect: Rect,
        rejectedBounds: Set<android.graphics.RectF>,
    ): Boolean {
        if (rejectedBounds.isEmpty()) return false
        val candidate = android.graphics.RectF(
            rect.x.toFloat(),
            rect.y.toFloat(),
            (rect.x + rect.width).toFloat(),
            (rect.y + rect.height).toFloat(),
        )
        return rejectedBounds.any { rejected ->
            val overlapLeft = max(candidate.left, rejected.left)
            val overlapTop = max(candidate.top, rejected.top)
            val overlapRight = min(candidate.right, rejected.right)
            val overlapBottom = min(candidate.bottom, rejected.bottom)
            if (overlapRight <= overlapLeft || overlapBottom <= overlapTop) {
                false
            } else {
                val intersection = (overlapRight - overlapLeft) * (overlapBottom - overlapTop)
                val candidateArea = max(1f, candidate.width() * candidate.height())
                val rejectedArea = max(1f, rejected.width() * rejected.height())
                intersection / min(candidateArea, rejectedArea) >= 0.55f
            }
        }
    }

    private fun ShapeCandidate.toDetection(
        kind: DetectionKind,
        invScale: Double,
    ): DetectionCandidate {
        val x = (rect.x * invScale).toFloat()
        val y = (rect.y * invScale).toFloat()
        val w = (rect.width * invScale).toFloat()
        val h = (rect.height * invScale).toFloat()

        return DetectionCandidate(
            kind = kind,
            bounds = android.graphics.RectF(x, y, x + w, y + h),
            confidence = score.coerceIn(0.0, 1.0).toFloat(),
            source = "opencv-universal-v1",
            hasPrintedFrame = frameLike,
        )
    }

    /**
     * Measures edge density from the full-image Canny map computed once per
     * detection. Re-running Canny for every contour was expensive on large forms.
     */
    private fun edgeDensity(edgeMap: Mat, rect: Rect): Double {
        val clipped = clip(rect, edgeMap)
        if (clipped.width <= 2 || clipped.height <= 2) return 0.0

        val roi = edgeMap.submat(clipped)
        return try {
            Core.countNonZero(roi).toDouble() /
                max(1.0, clipped.width.toDouble() * clipped.height.toDouble())
        } finally {
            roi.release()
        }
    }

    /**
     * Lightweight handwriting-vs-printed-text gate. Printed labels generally
     * contain many similarly sized glyphs; a signature tends to contain
     * connected strokes with a wider spread of component heights and widths.
     * This is a conservative heuristic, not an OCR or identity classifier.
     */
    private fun looksHandwritten(gray: Mat, rect: Rect): Boolean {
        val clipped = clip(rect, gray)
        if (clipped.width < 80 || clipped.height < 16) return false
        val roi = gray.submat(clipped)
        val binary = Mat()
        val labels = Mat()
        val stats = Mat()
        val centroids = Mat()
        try {
            Imgproc.threshold(
                roi, binary, 0.0, 255.0,
                Imgproc.THRESH_BINARY_INV or Imgproc.THRESH_OTSU,
            )
            // Ignore the outer frame band: otherwise a printed box border
            // becomes one giant connected component and looks like handwriting.
            val marginX = max(2, clipped.width / 24)
            val marginY = max(2, clipped.height / 12)
            binary.rowRange(0, marginY).apply {
                setTo(org.opencv.core.Scalar(0.0))
                release()
            }
            binary.rowRange(clipped.height - marginY, clipped.height).apply {
                setTo(org.opencv.core.Scalar(0.0))
                release()
            }
            binary.colRange(0, marginX).apply {
                setTo(org.opencv.core.Scalar(0.0))
                release()
            }
            binary.colRange(clipped.width - marginX, clipped.width).apply {
                setTo(org.opencv.core.Scalar(0.0))
                release()
            }
            val inkDensity = Core.countNonZero(binary).toDouble() /
                max(1.0, (clipped.width - 2 * marginX).toDouble() * (clipped.height - 2 * marginY))
            if (inkDensity !in 0.008..0.48) return false

            val count = Imgproc.connectedComponentsWithStats(
                binary, labels, stats, centroids, 8, CvType.CV_32S,
            )
            val widths = ArrayList<Int>()
            val heights = ArrayList<Int>()
            for (i in 1 until count) {
                val area = stats.get(i, Imgproc.CC_STAT_AREA)?.firstOrNull()?.toInt() ?: 0
                if (area < 4) continue
                val width = stats.get(i, Imgproc.CC_STAT_WIDTH)?.firstOrNull()?.toInt() ?: 0
                val height = stats.get(i, Imgproc.CC_STAT_HEIGHT)?.firstOrNull()?.toInt() ?: 0
                if (width < 2 || height < 2) continue
                widths += width
                heights += height
            }
            if (heights.size < 2) {
                // A continuous cursive stroke can be one connected component.
                return clipped.width >= 180 &&
                    clipped.width.toDouble() / max(1, clipped.height) >= 2.7 &&
                    inkDensity in 0.015..0.28
            }

            val meanHeight = heights.average().coerceAtLeast(1.0)
            val variance = heights.sumOf { (it - meanHeight) * (it - meanHeight) } /
                heights.size.toDouble()
            val heightVariation = kotlin.math.sqrt(variance) / meanHeight
            val medianHeight = heights.sorted()[heights.size / 2].coerceAtLeast(1)
            val medianWidth = widths.sorted()[widths.size / 2].coerceAtLeast(1)
            val distinctiveStrokes = heights.indices.count { index ->
                heights[index] >= medianHeight * 1.65 ||
                    widths[index] >= medianWidth * 2.8
            }
            val ratio = clipped.width.toDouble() / max(1, clipped.height)
            return ratio >= 1.45 &&
                (heightVariation >= 0.48 && distinctiveStrokes >= 2 ||
                    (distinctiveStrokes >= 2 &&
                        distinctiveStrokes.toDouble() / heights.size >= 0.22 &&
                        inkDensity in 0.012..0.36))
        } finally {
            centroids.release()
            stats.release()
            labels.release()
            binary.release()
            roi.release()
        }
    }

    private fun hasInk(gray: Mat, rect: Rect): Boolean =
        inkScore(gray, rect) >= 0.01

    private fun inkScore(gray: Mat, rect: Rect): Double {
        val clipped = clip(rect, gray)
        if (clipped.width <= 2 || clipped.height <= 2) return 0.0

        val roi = gray.submat(clipped)
        val threshold = Mat()
        return try {
            Imgproc.threshold(
                roi,
                threshold,
                145.0,
                255.0,
                Imgproc.THRESH_BINARY_INV,
            )
            Core.countNonZero(threshold).toDouble() /
                max(1.0, clipped.width.toDouble() * clipped.height.toDouble())
        } finally {
            threshold.release()
            roi.release()
        }
    }

    /**
     * Reuses the single full-image Canny map instead of running Canny once for
     * every contour. This keeps candidate scoring bounded on dense forms.
     */
    private fun isFrameLike(edgeMap: Mat, rect: Rect): Boolean {
        val clipped = clip(rect, edgeMap)
        if (clipped.width <= 8 || clipped.height <= 8) return false

        val roi = edgeMap.submat(clipped)
        try {
            val horizontalBand = max(1, roi.rows() / 12)
            val verticalBand = max(1, roi.cols() / 12)
            val top = roi.rowRange(0, horizontalBand)
            val bottom = roi.rowRange(
                max(0, roi.rows() - horizontalBand),
                roi.rows(),
            )
            val left = roi.colRange(0, verticalBand)
            val right = roi.colRange(
                max(0, roi.cols() - verticalBand),
                roi.cols(),
            )
            try {
                val horizontal = (
                    Core.countNonZero(top) + Core.countNonZero(bottom)
                    ).toDouble()
                val vertical = (
                    Core.countNonZero(left) + Core.countNonZero(right)
                    ).toDouble()
                val perimeterScale = max(
                    1.0,
                    (
                        top.rows() * top.cols() + bottom.rows() * bottom.cols() +
                            left.rows() * left.cols() + right.rows() * right.cols()
                        ).toDouble(),
                )
                return (horizontal + vertical) / perimeterScale > 0.10
            } finally {
                right.release()
                left.release()
                bottom.release()
                top.release()
            }
        } finally {
            roi.release()
        }
    }

    private fun clip(rect: Rect, image: Mat): Rect {
        val x = rect.x.coerceIn(0, max(0, image.cols() - 1))
        val y = rect.y.coerceIn(0, max(0, image.rows() - 1))
        val right = (rect.x + rect.width).coerceIn(x + 1, image.cols())
        val bottom = (rect.y + rect.height).coerceIn(y + 1, image.rows())
        return Rect(x, y, right - x, bottom - y)
    }
}
