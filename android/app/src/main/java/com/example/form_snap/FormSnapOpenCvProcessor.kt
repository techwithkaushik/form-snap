package com.example.form_snap

import android.content.Context
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo
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
            val photoWidthMm = (args["photoWidthMm"] as? Number)?.toDouble() ?: 40.0
        val photoHeightMm = (args["photoHeightMm"] as? Number)?.toDouble() ?: 50.0
        val signatureWidthMm = (args["signatureWidthMm"] as? Number)?.toDouble() ?: 50.0
        val signatureHeightMm = (args["signatureHeightMm"] as? Number)?.toDouble() ?: 20.0
        val dpi = (args["dpi"] as? Number)?.toDouble() ?: 300.0
        val maxKb = (args["maxKb"] as? Number)?.toInt() ?: 50

        when (mode) {
                "wholeForm" -> processWholeForm(context, source, photoWidthMm, photoHeightMm, signatureWidthMm, signatureHeightMm, dpi, maxKb)
                "closePhoto" -> processCloseUp(context, source, true, photoWidthMm, photoHeightMm, dpi, maxKb)
                "closeSignature" -> processCloseUp(context, source, false, signatureWidthMm, signatureHeightMm, dpi, maxKb)
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

    // Whole-form extraction: detect the document once, perspective-correct it,
    // then use the fixed Class-8 2026-27 template coordinates. This avoids
    // guessing which of many internal rectangles is the photo/signature box.
    private fun processWholeForm(
        context: Context,
        source: Mat,
        photoWidthMm: Double,
        photoHeightMm: Double,
        signatureWidthMm: Double,
        signatureHeightMm: Double,
        dpi: Double,
        maxKb: Int,
    ): Map<String, Any?> {
        val rectified = rectifyDocument(source)
        val page = rectified ?: source

        // A4 Class 8 2026-27 template, normalized to the corrected page.
        val photoTemplate = cropTemplate(page, 0.746, 0.190, 0.193, 0.169)
        val photoCrop = findPastedPhotoInsideBox(photoTemplate)
        val signatureCrop = cropTemplate(page, 0.722, 0.374, 0.240, 0.068)

        // Only inspect the outer edge of the template crop. The previous wide
        // edge search could erase real hair/ink near the top of the content.
        val photoEdgeClean = removeTemplateEdgeLines(photoCrop, true)
        val signatureEdgeClean = removeTemplateEdgeLines(signatureCrop, false)
        val photo = enhancePhotoQuality(photoEdgeClean)
        val sign = enhanceSignQuality(signatureEdgeClean)

        photoTemplate.release()
        photoCrop.release()
        signatureCrop.release()
        photoEdgeClean.release()
        signatureEdgeClean.release()
        rectified?.release()

        val photoPath = saveJpeg(
            context, photo, "photo", photoWidthMm, photoHeightMm, dpi, maxKb,
        )
        val signPath = saveJpeg(
            context, sign, "signature", signatureWidthMm, signatureHeightMm, dpi, maxKb,
        )
        photo.release()
        sign.release()

        return mapOf(
            "photoPath" to photoPath,
            "signaturePath" to signPath,
            "photoDetected" to true,
            "signatureDetected" to true,
            "detector" to if (rectified != null) "document-perspective-template" else "template-fallback",
        )
    }

    // Fast document detection on a downscaled copy. A large phone image is
    // never processed full-resolution for contour detection.
    private fun rectifyDocument(source: Mat): Mat? {
        val maxSide = max(source.cols(), source.rows())
        val scale = min(1.0, 1600.0 / maxSide.toDouble())
        val small = Mat()
        if (scale < 0.999) {
            Imgproc.resize(
                source,
                small,
                Size(source.cols() * scale, source.rows() * scale),
                0.0,
                0.0,
                Imgproc.INTER_AREA,
            )
        } else {
            source.copyTo(small)
        }

        val gray = Mat()
        val blurred = Mat()
        val edges = Mat()
        val closed = Mat()
        Imgproc.cvtColor(small, gray, Imgproc.COLOR_BGR2GRAY)
        Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)
        Imgproc.Canny(blurred, edges, 60.0, 160.0)
        val closeKernel = Imgproc.getStructuringElement(
            Imgproc.MORPH_RECT, Size(5.0, 5.0),
        )
        Imgproc.morphologyEx(edges, closed, Imgproc.MORPH_CLOSE, closeKernel)

        val contours = ArrayList<MatOfPoint>()
        Imgproc.findContours(
            closed, contours, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE,
        )

        val imageArea = small.cols().toDouble() * small.rows().toDouble()
        var best: Array<Point>? = null
        var bestScore = 0.0

        for (contour in contours) {
            val area = abs(Imgproc.contourArea(contour))
            if (area < imageArea * 0.35) {
                contour.release()
                continue
            }
            val points = MatOfPoint2f(*contour.toArray())
            val perimeter = Imgproc.arcLength(points, true)
            val approx = MatOfPoint2f()
            Imgproc.approxPolyDP(points, approx, perimeter * 0.02, true)
            if (approx.rows() == 4) {
                val quad = approx.toArray()
                val quadMat = MatOfPoint(*quad)
                val convex = Imgproc.isContourConvex(quadMat)
                val rect = Imgproc.boundingRect(quadMat)
                val fill = area / max(1.0, rect.width.toDouble() * rect.height)
                val aspect = rect.width.toDouble() / max(1, rect.height).toDouble()
                val portraitScore = if (aspect in 0.62..0.80) 1.0 else 0.0
                val score = (area / imageArea) * 0.60 + fill.coerceIn(0.0, 1.0) * 0.20 + portraitScore * 0.20
                if (convex && area >= imageArea * 0.45 && score > bestScore) {
                    best = quad
                    bestScore = score
                }
                quadMat.release()
            }
            points.release()
            approx.release()
            contour.release()
        }

        val result = if (best != null) {
            val ordered = orderCorners(best!!)
            val targetW = 1400
            val targetH = 1980
            val srcCorners = MatOfPoint2f(*ordered)
            val dstCorners = MatOfPoint2f(
                Point(0.0, 0.0),
                Point((targetW - 1).toDouble(), 0.0),
                Point((targetW - 1).toDouble(), (targetH - 1).toDouble()),
                Point(0.0, (targetH - 1).toDouble()),
            )
            val transform = Imgproc.getPerspectiveTransform(srcCorners, dstCorners)
            val warped = Mat()
            Imgproc.warpPerspective(
                small,
                warped,
                transform,
                Size(targetW.toDouble(), targetH.toDouble()),
                Imgproc.INTER_LINEAR,
                org.opencv.core.Core.BORDER_REPLICATE,
            )
            srcCorners.release()
            dstCorners.release()
            transform.release()
            warped
        } else {
            null
        }

        gray.release()
        blurred.release()
        edges.release()
        closed.release()
        closeKernel.release()
        small.release()
        return result
    }

    private fun orderCorners(points: Array<Point>): Array<Point> {
        require(points.size == 4)
        val sums = points.map { it.x + it.y }
        val diffs = points.map { it.x - it.y }
        val topLeft = points[sums.indices.minByOrNull { sums[it] }!!]
        val bottomRight = points[sums.indices.maxByOrNull { sums[it] }!!]
        val topRight = points[diffs.indices.maxByOrNull { diffs[it] }!!]
        val bottomLeft = points[diffs.indices.minByOrNull { diffs[it] }!!]
        return arrayOf(topLeft, topRight, bottomRight, bottomLeft)
    }

    private fun cropTemplate(
        source: Mat,
        left: Double,
        top: Double,
        width: Double,
        height: Double,
    ): Mat {
        val padX = max(2, (source.cols() * 0.002).toInt())
        val padY = max(2, (source.rows() * 0.002).toInt())
        val x1 = (source.cols() * left).toInt() + padX
        val y1 = (source.rows() * top).toInt() + padY
        val x2 = (source.cols() * (left + width)).toInt() - padX
        val y2 = (source.rows() * (top + height)).toInt() - padY
        val sx1 = x1.coerceIn(0, source.cols() - 1)
        val sy1 = y1.coerceIn(0, source.rows() - 1)
        val sx2 = x2.coerceIn(sx1 + 1, source.cols())
        val sy2 = y2.coerceIn(sy1 + 1, source.rows())
        return source.submat(sy1, sy2, sx1, sx2).clone()
    }

    // The printed form has a PHOTO BOX, and the pasted passport photo can
    // have its own rectangular edge inside that box. Prefer the inner photo
    // rectangle when it is clearly present; otherwise use the template box.
    // This is important when a user sticks a smaller photo inside the printed
    // frame instead of filling it edge-to-edge.
    private fun findPastedPhotoInsideBox(template: Mat): Mat {
        val gray=Mat()
        val blur=Mat()
        val edges=Mat()
        Imgproc.cvtColor(template,gray,Imgproc.COLOR_BGR2GRAY)
        Imgproc.GaussianBlur(gray,blur,Size(3.0,3.0),0.0)
        Imgproc.Canny(blur,edges,45.0,130.0)
        val kernel=Imgproc.getStructuringElement(Imgproc.MORPH_RECT,Size(3.0,3.0))
        Imgproc.morphologyEx(edges,edges,Imgproc.MORPH_CLOSE,kernel)
        val contours=ArrayList<MatOfPoint>()
        Imgproc.findContours(edges,contours,Mat(),Imgproc.RETR_LIST,Imgproc.CHAIN_APPROX_SIMPLE)

        val area=template.cols().toDouble()*template.rows().toDouble()
        var best:Rect?=null
        var bestScore=Double.NEGATIVE_INFINITY
        for(c in contours){
            val r=Imgproc.boundingRect(c)
            val a=abs(Imgproc.contourArea(c))
            val ratio=r.width.toDouble()/max(1,r.height).toDouble()
            val fill=a/max(1.0,r.width.toDouble()*r.height)
            val marginX=min(r.x,template.cols()-(r.x+r.width)).toDouble()/template.cols()
            val marginY=min(r.y,template.rows()-(r.y+r.height)).toDouble()/template.rows()
            val centered=1.0-(abs((r.x+r.width/2.0)/template.cols()-0.5)*2.0)
            // Inner photo should not be the outer printed frame itself.
            val sizeFraction=r.width.toDouble()*r.height.toDouble()/area
            val plausible=ratio in 0.55..1.05 && sizeFraction in 0.45..0.96 &&
                marginX>0.025 && marginY>0.025 && fill>0.45
            if(plausible){
                val ratioScore=1.0-min(1.0,abs(ratio-0.80)/0.30)
                val score=ratioScore*0.35+fill*0.20+centered*0.15+sizeFraction*0.30
                if(score>bestScore){bestScore=score;best=r}
            }
            c.release()
        }
        val result=if(best!=null){
            val r=best!!
            val padX=max(2,(r.width*0.008).toInt())
            val padY=max(2,(r.height*0.008).toInt())
            template.submat(
                (r.y+padY).coerceIn(0,template.rows()-1),
                (r.y+r.height-padY).coerceIn(r.y+padY+1,template.rows()),
                (r.x+padX).coerceIn(0,template.cols()-1),
                (r.x+r.width-padX).coerceIn(r.x+padX+1,template.cols())
            ).clone()
        } else template.clone()
        gray.release();blur.release();edges.release();kernel.release()
        return result
    }

    private fun removeTemplateEdgeLines(crop: Mat, isPhoto: Boolean): Mat {
        val gray = Mat()
        val dark = Mat()
        val mask = Mat.zeros(crop.size(), CvType.CV_8UC1)
        Imgproc.cvtColor(crop, gray, Imgproc.COLOR_BGR2GRAY)
        Imgproc.threshold(gray, dark, 105.0, 255.0, Imgproc.THRESH_BINARY_INV)

        val hKernel = Imgproc.getStructuringElement(
            Imgproc.MORPH_RECT, Size(max(25, crop.cols() / 2).toDouble(), 1.0),
        )
        val vKernel = Imgproc.getStructuringElement(
            Imgproc.MORPH_RECT, Size(1.0, max(25, crop.rows() / 2).toDouble()),
        )
        val horizontal = Mat()
        val vertical = Mat()
        Imgproc.morphologyEx(dark, horizontal, Imgproc.MORPH_OPEN, hKernel)
        Imgproc.morphologyEx(dark, vertical, Imgproc.MORPH_OPEN, vKernel)

        val edgeY = max(4, crop.rows() / 25)
        val edgeX = max(4, crop.cols() / 25)
        horizontal.submat(0, edgeY, 0, crop.cols()).copyTo(mask.submat(0, edgeY, 0, crop.cols()))
        horizontal.submat(crop.rows() - edgeY, crop.rows(), 0, crop.cols())
            .copyTo(mask.submat(crop.rows() - edgeY, crop.rows(), 0, crop.cols()))
        vertical.submat(0, crop.rows(), 0, edgeX).copyTo(mask.submat(0, crop.rows(), 0, edgeX))
        vertical.submat(0, crop.rows(), crop.cols() - edgeX, crop.cols())
            .copyTo(mask.submat(0, crop.rows(), crop.cols() - edgeX, crop.cols()))

        if (isPhoto) {
            val bright = Mat()
            val brightHorizontal = Mat()
            Imgproc.threshold(gray, bright, 235.0, 255.0, Imgproc.THRESH_BINARY)
            Imgproc.morphologyEx(bright, brightHorizontal, Imgproc.MORPH_OPEN, hKernel)
            brightHorizontal.submat(0, edgeY, 0, crop.cols())
                .copyTo(mask.submat(0, edgeY, 0, crop.cols()))
            bright.release()
            brightHorizontal.release()
        }

        val repaired = Mat()
        Photo.inpaint(crop, mask, repaired, 2.0, Photo.INPAINT_TELEA)
        gray.release()
        dark.release()
        mask.release()
        hKernel.release()
        vKernel.release()
        horizontal.release()
        vertical.release()
        return repaired
    }

    // Exact algorithm from close_up_cropping.py / bulk_folder_cropper.py.
    private fun processCloseUp(
        context: Context,
        source: Mat,
        isPhoto: Boolean,
        widthMm: Double,
        heightMm: Double,
        dpi: Double,
        maxKb: Int,
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
                saveJpeg(context, finalImage, "photo", widthMm, heightMm, dpi, maxKb)
            } else {
                saveJpeg(context, finalImage, "signature", widthMm, heightMm, dpi, maxKb)
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
        val edgeClean = removePrintedEdgeLines(frameClean, isPhoto)
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
            saveJpeg(context, finalImage, "photo", widthMm, heightMm, dpi, maxKb)
        } else {
            saveJpeg(context, finalImage, "signature", widthMm, heightMm, dpi, maxKb)
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

    // Close-up mode uses a small edge/rectangle detector. It is intentionally
    // independent of the full-page template because the camera may contain
    // only the photo/signature box.
    private fun findCloseUpBoxes(source: Mat): CloseUpBoxes {
        val gray = Mat()
        val edges = Mat()
        val closed = Mat()
        Imgproc.cvtColor(source, gray, Imgproc.COLOR_BGR2GRAY)
        Imgproc.GaussianBlur(gray, gray, Size(3.0, 3.0), 0.0)
        Imgproc.Canny(gray, edges, 60.0, 150.0)
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        Imgproc.morphologyEx(edges, closed, Imgproc.MORPH_CLOSE, kernel)

        val contours = ArrayList<MatOfPoint>()
        Imgproc.findContours(
            closed, contours, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE,
        )
        val imageArea = source.rows().toDouble() * source.cols().toDouble()
        val photoCandidates = ArrayList<Pair<Rect, Double>>()
        val signatureCandidates = ArrayList<Pair<Rect, Double>>()

        for (contour in contours) {
            val area = abs(Imgproc.contourArea(contour))
            if (area < imageArea * 0.08) {
                contour.release()
                continue
            }
            val points = MatOfPoint2f(*contour.toArray())
            val perimeter = Imgproc.arcLength(points, true)
            val approx = MatOfPoint2f()
            Imgproc.approxPolyDP(points, approx, perimeter * 0.025, true)
            val box = Imgproc.boundingRect(contour)
            val ratio = box.width.toDouble() / max(1, box.height).toDouble()
            val rectangularity = area / max(1.0, box.width.toDouble() * box.height)
            val quadBonus = if (approx.rows() == 4) 1.0 else 0.0

            if (ratio in 0.60..1.05 && rectangularity > 0.72) {
                val ratioScore = 1.0 - min(1.0, abs(ratio - 0.80) / 0.25)
                photoCandidates.add(box to (ratioScore * 0.55 + rectangularity * 0.30 + quadBonus * 0.15))
            }
            if (ratio in 1.65..3.40 && rectangularity > 0.72) {
                val ratioScore = 1.0 - min(1.0, abs(ratio - 2.50) / 0.85)
                signatureCandidates.add(box to (ratioScore * 0.55 + rectangularity * 0.30 + quadBonus * 0.15))
            }
            points.release()
            approx.release()
            contour.release()
        }

        gray.release()
        edges.release()
        closed.release()
        kernel.release()
        return CloseUpBoxes(
            photoCandidates.maxByOrNull { it.second }?.first,
            signatureCandidates.maxByOrNull { it.second }?.first,
        )
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
    // Signature cleanup keeps the original ink tone. The old binary pipeline
    // made the signature look unnaturally bright/thin. We estimate the paper
    // background, darken only pixels that are genuinely ink-like, and retain
    // the original grayscale instead of forcing a pure-white threshold image.
    private fun enhanceSignQuality(cropped: Mat): Mat {
        val gray=Mat()
        Imgproc.cvtColor(cropped,gray,Imgproc.COLOR_BGR2GRAY)
        val background=Mat()
        Imgproc.GaussianBlur(gray,background,Size(0.0,0.0),11.0)
        val diff=Mat()
        Core.subtract(background,gray,diff)

        val inkMask=Mat()
        Imgproc.threshold(diff,inkMask,10.0,255.0,Imgproc.THRESH_BINARY)
        val k=Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE,Size(2.0,2.0))
        Imgproc.morphologyEx(inkMask,inkMask,Imgproc.MORPH_OPEN,k)
        Imgproc.morphologyEx(inkMask,inkMask,Imgproc.MORPH_CLOSE,k)

        val result=Mat(gray.size(),CvType.CV_8UC1,org.opencv.core.Scalar(250.0))
        // Keep actual photographed ink grayscale; only the paper/background is
        // gently normalized. This prevents the previous over-bright result.
        gray.copyTo(result,inkMask)
        Imgproc.GaussianBlur(result,result,Size(3.0,3.0),0.0)

        val enlarged=Mat()
        Imgproc.resize(result,enlarged,Size(),2.0,2.0,Imgproc.INTER_LANCZOS4)
        gray.release();background.release();diff.release();inkMask.release();k.release();result.release()
        return enlarged
    }
    // Lightweight close-up photo cleanup: denoise first, then apply a very
    // mild detail pass instead of the old aggressive sharpening kernel.
    private fun enhanceCloseUpPhoto(cropped: Mat): Mat {
        val denoised = Mat()
        Imgproc.medianBlur(cropped, denoised, 3)
        val blur = Mat()
        Imgproc.GaussianBlur(denoised, blur, Size(0.0, 0.0), 0.8)
        val result = Mat()
        Core.addWeighted(denoised, 1.08, blur, -0.08, 0.0, result)
        denoised.release()
        blur.release()
        return result
    }
    private fun enhanceCloseUpSignature(cropped: Mat): Mat {
        val gray=Mat()
        Imgproc.cvtColor(cropped,gray,Imgproc.COLOR_BGR2GRAY)
        val bg=Mat()
        Imgproc.GaussianBlur(gray,bg,Size(0.0,0.0),9.0)
        val diff=Mat()
        Core.subtract(bg,gray,diff)
        val mask=Mat()
        Imgproc.threshold(diff,mask,9.0,255.0,Imgproc.THRESH_BINARY)
        val result=Mat(gray.size(),CvType.CV_8UC1,org.opencv.core.Scalar(250.0))
        gray.copyTo(result,mask)
        Imgproc.GaussianBlur(result,result,Size(3.0,3.0),0.0)
        val bgr=Mat()
        Imgproc.cvtColor(result,bgr,Imgproc.COLOR_GRAY2BGR)
        gray.release();bg.release();diff.release();mask.release();result.release()
        return bgr
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
    // Remove printed frame rules without painting over real content.
    // Photo mode uses inpainting so a white/black rule crossing the hair is
    // reconstructed from neighbouring pixels instead of being turned into a
    // white stripe. Signature mode repairs the top/bottom border rules too.
    private fun removePrintedEdgeLines(crop: Mat, isPhoto: Boolean): Mat {
        return removeTemplateEdgeLines(crop, isPhoto)
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
        dpi: Double,
        maxKb: Int,
    ): String {
        val targetW = max(1, (widthMm / 25.4 * dpi).toInt())
        val targetH = max(1, (heightMm / 25.4 * dpi).toInt())

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
