package org.techwithkaushik.formSnap.pipeline

import android.content.Context
import java.io.File

data class ProcessedFileResult(
    val photoPath: String?,
    val signaturePath: String?,
    val photoQuality: QualityResult?,
    val signatureQuality: QualityResult?,
    val detection: DetectionResult,
)

object ProcessFileService {
    fun process(
        context: Context,
        input: File,
        dpi: Int = 300,
    ): ProcessedFileResult {
        val session = ProcessingSession.create(context)
        try {
            val result = UniversalPipelineBatch.processFile(input, dpi, context)
            val photoPath = result.photo?.image?.let {
                val file = session.file("photo.jpg")
                org.opencv.imgcodecs.Imgcodecs.imwrite(file.absolutePath, it)
                file.absolutePath
            }
            val signaturePath = result.signature?.image?.let {
                val file = session.file("signature.jpg")
                org.opencv.imgcodecs.Imgcodecs.imwrite(file.absolutePath, it)
                file.absolutePath
            }
            return ProcessedFileResult(
                photoPath = photoPath,
                signaturePath = signaturePath,
                photoQuality = result.photo?.quality,
                signatureQuality = result.signature?.quality,
                detection = result.detection,
            )
        } finally {
            session.closeAndDelete()
        }
    }
}