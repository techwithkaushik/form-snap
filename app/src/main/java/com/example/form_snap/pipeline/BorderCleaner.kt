package org.techwithkaushik.formSnap.pipeline

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min

object BorderCleaner {

    fun clean(input: Mat, kind: DetectionKind): Mat {
        require(!input.empty()) { "Image is empty" }

        val gray = Mat()
        val dark = Mat()
        val mask = Mat.zeros(input.size(), CvType.CV_8UC1)
        val output = Mat()

        return try {
            Imgproc.cvtColor(input, gray, Imgproc.COLOR_BGR2GRAY)

            val threshold = when (kind) {
                DetectionKind.PHOTO -> 90.0
                DetectionKind.SIGNATURE -> 125.0
            }

            Imgproc.threshold(
                gray,
                dark,
                threshold,
                255.0,
                Imgproc.THRESH_BINARY_INV,
            )

            val edgeX = max(2, input.cols() / 35)
            val edgeY = max(2, input.rows() / 35)

            val horizontalKernel = Imgproc.getStructuringElement(
                Imgproc.MORPH_RECT,
                Size(max(9, input.cols() / 3).toDouble(), 1.0),
            )
            val verticalKernel = Imgproc.getStructuringElement(
                Imgproc.MORPH_RECT,
                Size(1.0, max(9, input.rows() / 3).toDouble()),
            )

            val horizontal = Mat()
            val vertical = Mat()

            Imgproc.morphologyEx(
                dark,
                horizontal,
                Imgproc.MORPH_OPEN,
                horizontalKernel,
            )
            Imgproc.morphologyEx(
                dark,
                vertical,
                Imgproc.MORPH_OPEN,
                verticalKernel,
            )

            horizontal.rowRange(0, min(edgeY, horizontal.rows()))
                .copyTo(mask.rowRange(0, min(edgeY, mask.rows())))

            horizontal.rowRange(
                max(0, horizontal.rows() - edgeY),
                horizontal.rows(),
            ).copyTo(
                mask.rowRange(
                    max(0, mask.rows() - edgeY),
                    mask.rows(),
                ),
            )

            vertical.colRange(0, min(edgeX, vertical.cols()))
                .copyTo(mask.colRange(0, min(edgeX, mask.cols())))

            vertical.colRange(
                max(0, vertical.cols() - edgeX),
                vertical.cols(),
            ).copyTo(
                mask.colRange(
                    max(0, mask.cols() - edgeX),
                    mask.cols(),
                ),
            )

            val repaired = Mat()
            input.copyTo(repaired)

            if (Core.countNonZero(mask) > 0) {
                org.opencv.photo.Photo.inpaint(
                    repaired,
                    mask,
                    output,
                    2.0,
                    org.opencv.photo.Photo.INPAINT_TELEA,
                )
                repaired.release()
                output.clone()
            } else {
                repaired
            }.also {
                horizontal.release()
                vertical.release()
                horizontalKernel.release()
                verticalKernel.release()
            }
        } finally {
            mask.release()
            dark.release()
            gray.release()
        }
    }
}
