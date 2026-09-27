package org.techwithkaushik.formSnap.pipeline

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.techwithkaushik.formSnap.foundation.ProcessingSession
import java.io.File

data class PreviewProcessingState(
    val source: File? = null,
    val photoState: PreviewCorrectionState? = null,
    val signatureState: PreviewCorrectionState? = null,
    val photoPreviewPath: String? = null,
    val signaturePreviewPath: String? = null,
    val processing: Boolean = false,
    val error: String? = null,
)

class PipelinePreviewViewModel(private val context: Context) {
    var state: PreviewProcessingState = PreviewProcessingState()
        private set

    suspend fun load(input: File) {
        state = PreviewProcessingState(source = input, processing = true)
        try {
            val detection = withContext(Dispatchers.Default) {
                val source = org.opencv.imgcodecs.Imgcodecs.imread(input.absolutePath)
                require(!source.empty()) { "Unable to decode input image" }
                try { UniversalDetectionEngine.detect(source) } finally { source.release() }
            }
            state = PreviewProcessingState(
                source = input,
                photoState = detection.photo?.let { PreviewCorrectionStateFactory.fromCandidate(it, detection.sourceWidth, detection.sourceHeight) },
                signatureState = detection.signature?.let { PreviewCorrectionStateFactory.fromCandidate(it, detection.sourceWidth, detection.sourceHeight) },
            )
        } catch (t: Throwable) {
            state = PreviewProcessingState(source = input, error = t.message ?: "Preview failed")
        }
    }

    suspend fun renderPhoto() {
        renderKind(DetectionKind.PHOTO)
    }

    suspend fun renderSignature() {
        renderKind(DetectionKind.SIGNATURE)
    }

    private suspend fun renderKind(kind: DetectionKind) {
        val current = if (kind == DetectionKind.PHOTO) state.photoState else state.signatureState
        if (current == null) return
        state = state.copy(processing = true, error = null)
        val name = if (kind == DetectionKind.PHOTO) "photo_preview.jpg" else "signature_preview.jpg"
        state = runCatching { render(current, name) }
            .fold(
                { path -> if (kind == DetectionKind.PHOTO) state.copy(photoPreviewPath = path, processing = false) else state.copy(signaturePreviewPath = path, processing = false) },
                { error -> state.copy(processing = false, error = error.message ?: "Preview failed") },
            )
    }

    private suspend fun render(correction: PreviewCorrectionState, name: String): String = withContext(Dispatchers.Default) {
        val input = state.source ?: error("No source image")
        val source = org.opencv.imgcodecs.Imgcodecs.imread(input.absolutePath)
        require(!source.empty()) { "Unable to decode source image" }
        val session = ProcessingSession.create(context)
        try {
            val image = try { PreviewProcessor.render(source, correction) } finally { source.release() }
            try {
                val target = session.file(name)
                check(org.opencv.imgcodecs.Imgcodecs.imwrite(target.absolutePath, image)) { "Unable to write preview" }
                target.absolutePath
            } finally {
                image.release()
            }
        } finally {
            session.closeAndDelete()
        }
    }
}