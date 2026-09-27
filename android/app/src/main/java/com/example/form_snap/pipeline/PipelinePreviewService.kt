package org.techwithkaushik.formSnap.pipeline

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import org.opencv.android.Utils
import org.opencv.core.Mat
import java.io.File

data class PreviewResult(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val photoPath: String?,
    val signaturePath: String?,
    val photoCandidate: DetectionCandidate?,
    val signatureCandidate: DetectionCandidate?,
)

object PipelinePreviewService {
    fun process(context: android.content.Context, input: File, dpi: Int = 300): PreviewResult {
        require(input.exists()) { "Input image does not exist" }
        val detection = UniversalDetectionEngine.detect(org.opencv.imgcodecs.Imgcodecs.imread(input.absolutePath))
        var photoPath: String? = null
        var signaturePath: String? = null
        val session = org.techwithkaushik.formSnap.foundation.ProcessingSession.create(context)
        try {
            UniversalPipelineBatch.processFile(input, dpi, context).use { result ->
                result.photo?.image?.let { image ->
                    val file = session.file("photo_preview.jpg")
                    check(org.opencv.imgcodecs.Imgcodecs.imwrite(file.absolutePath, image)) { "Unable to write photo preview" }
                    photoPath = file.absolutePath
                }
                result.signature?.image?.let { image ->
                    val file = session.file("signature_preview.jpg")
                    check(org.opencv.imgcodecs.Imgcodecs.imwrite(file.absolutePath, image)) { "Unable to write signature preview" }
                    signaturePath = file.absolutePath
                }
            }
            return PreviewResult(detection.sourceWidth, detection.sourceHeight, photoPath, signaturePath, detection.photo, detection.signature)
        } finally {
            session.closeAndDelete()
        }
    }
}