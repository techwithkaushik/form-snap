package org.techwithkaushik.formSnap.pipeline

import org.techwithkaushik.formSnap.foundation.ProcessingSession
import java.io.File

data class PreviewResult(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val photoPath: String?,
    val signaturePath: String?,
    val photoCandidate: DetectionCandidate?,
    val signatureCandidate: DetectionCandidate?,
    private val session: ProcessingSession,
) : AutoCloseable {
    /**
     * Preview files belong to this result and remain available until close().
     * Consume with use { ... } when the preview is no longer needed.
     */
    override fun close() {
        session.closeAndDelete()
    }
}

object PipelinePreviewService {
    fun process(context: android.content.Context, input: File, dpi: Int = 300): PreviewResult {
        require(input.exists()) { "Input image does not exist" }
        val session = ProcessingSession.create(context)
        return try {
            UniversalPipelineBatch.processFile(input, dpi).use { result ->
                var photoPath: String? = null
                var signaturePath: String? = null

                result.photo?.image?.let { image ->
                    val file = session.file("photo_preview.jpg")
                    check(org.opencv.imgcodecs.Imgcodecs.imwrite(file.absolutePath, image)) {
                        "Unable to write photo preview"
                    }
                    photoPath = file.absolutePath
                }
                result.signature?.image?.let { image ->
                    val file = session.file("signature_preview.jpg")
                    check(org.opencv.imgcodecs.Imgcodecs.imwrite(file.absolutePath, image)) {
                        "Unable to write signature preview"
                    }
                    signaturePath = file.absolutePath
                }

                PreviewResult(
                    sourceWidth = result.detection.sourceWidth,
                    sourceHeight = result.detection.sourceHeight,
                    photoPath = photoPath,
                    signaturePath = signaturePath,
                    photoCandidate = result.photo?.detection?.photo ?: result.detection.photo,
                    signatureCandidate = result.signature?.detection?.signature ?: result.detection.signature,
                    session = session,
                )
            }
        } catch (t: Throwable) {
            session.closeAndDelete()
            throw t
        }
    }
}
