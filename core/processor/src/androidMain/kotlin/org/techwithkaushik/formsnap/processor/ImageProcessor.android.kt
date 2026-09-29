package org.techwithkaushik.formsnap.processor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.ByteArrayOutputStream

actual class ImageProcessor {

    init {
        require(OpenCVLoader.initLocal()) {
            "Unable to initialize the bundled OpenCV Android SDK."
        }
    }

    actual fun processForm(
        imageData: ByteArray,
        adaptiveBlockSize: Int,
        adaptiveConstant: Double,
    ): ByteArray {
        require(imageData.isNotEmpty()) { "Image data is empty." }

        val bitmap = BitmapFactory.decodeByteArray(imageData, 0, imageData.size)
            ?: error("Unable to decode input image.")

        val source = Mat()
        val gray = Mat()
        val blurred = Mat()
        val binary = Mat()

        return try {
            Utils.bitmapToMat(bitmap, source)
            Imgproc.cvtColor(source, gray, Imgproc.COLOR_RGBA2GRAY)

            val blockSize = adaptiveBlockSize.coerceIn(3, 99).let {
                if (it % 2 == 0) it + 1 else it
            }

            Imgproc.GaussianBlur(
                gray,
                blurred,
                Size(5.0, 5.0),
                0.0,
            )

            Imgproc.adaptiveThreshold(
                blurred,
                binary,
                255.0,
                Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                Imgproc.THRESH_BINARY_INV,
                blockSize,
                adaptiveConstant.coerceIn(-32.0, 32.0),
            )

            val outputBitmap = Bitmap.createBitmap(
                binary.cols(),
                binary.rows(),
                Bitmap.Config.ARGB_8888,
            )

            Utils.matToBitmap(binary, outputBitmap)

            ByteArrayOutputStream().use { stream ->
                outputBitmap.compress(Bitmap.CompressFormat.JPEG, 92, stream)
                outputBitmap.recycle()
                stream.toByteArray()
            }
        } finally {
            bitmap.recycle()
            source.release()
            gray.release()
            blurred.release()
            binary.release()
        }
    }
}
