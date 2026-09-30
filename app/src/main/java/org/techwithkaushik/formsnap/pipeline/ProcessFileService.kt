package org.techwithkaushik.formSnap.pipeline

import android.content.Context
import java.io.File
import org.techwithkaushik.formSnap.foundation.ProcessingSession

/**
 * Result files remain available until the caller closes this result.
 * Always consume with `use { result -> ... }` when possible to clean up promptly.
 */
data class ProcessedFileResult(
    val photoPath: String?,
    val signaturePath: String?,
    val photoQuality: QualityResult?,
    val signatureQuality: QualityResult?,
    val detection: DetectionResult,
    private val session: ProcessingSession,
) : AutoCloseable {
    override fun close() {
        session.closeAndDelete()
    }
}

object ProcessFileService {
    fun process(
        context: Context,
        input: File,
        dpi: Int = 300,
    ): ProcessedFileResult {
        val session = ProcessingSession.create(context)
        return try {
            UniversalPipelineBatch.processFile(input, dpi).use { result ->
                val photoPath = result.photo?.image?.let { image ->
                    val file = session.file("photo.jpg")
                    check(org.opencv.imgcodecs.Imgcodecs.imwrite(file.absolutePath, image)) {
                        "Unable to write photo output"
                    }
                    file.absolutePath
                }
                val signaturePath = result.signature?.image?.let { image ->
                    val file = session.file("signature.jpg")
                    check(org.opencv.imgcodecs.Imgcodecs.imwrite(file.absolutePath, image)) {
                        "Unable to write signature output"
                    }
                    file.absolutePath
                }
                ProcessedFileResult(
                    photoPath = photoPath,
                    signaturePath = signaturePath,
                    photoQuality = result.photo?.quality,
                    signatureQuality = result.signature?.quality,
                    detection = result.detection,
                    session = session,
                )
            }
        } catch (t: Throwable) {
            session.closeAndDelete()
            throw t
        }
    }
}
