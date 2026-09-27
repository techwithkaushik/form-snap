package org.techwithkaushik.formSnap.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.techwithkaushik.formSnap.foundation.ProcessingPaths
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

class PipelinePreviewViewModel(private val context: Context) : AutoCloseable {
    private val _state = MutableStateFlow(PreviewProcessingState())
    val state: StateFlow<PreviewProcessingState> = _state

    private val sessionDir = File(ProcessingPaths.root(context), "preview_session").apply { mkdirs() }

    suspend fun load(input: File) {
        _state.value = PreviewProcessingState(source = input, processing = true)
        try {
            val detection = withContext(Dispatchers.Default) {
                val source = org.opencv.imgcodecs.Imgcodecs.imread(input.absolutePath)
                require(!source.empty()) { "Unable to decode input image" }
                try { UniversalDetectionEngine.detect(source) } finally { source.release() }
            }
            _state.value = PreviewProcessingState(
                source = input,
                photoState = detection.photo?.let { PreviewCorrectionStateFactory.fromCandidate(it, detection.sourceWidth, detection.sourceHeight) },
                signatureState = detection.signature?.let { PreviewCorrectionStateFactory.fromCandidate(it, detection.sourceWidth, detection.sourceHeight) },
            )
            renderDetectedPreviews()
        } catch (t: Throwable) {
            _state.value = PreviewProcessingState(source = input, error = t.message ?: "Preview failed")
        }
    }

    suspend fun renderPhoto() = renderKind(DetectionKind.PHOTO)
    suspend fun renderSignature() = renderKind(DetectionKind.SIGNATURE)

    private suspend fun renderDetectedPreviews() {
        val current = _state.value
        if (current.photoState != null) renderKind(DetectionKind.PHOTO)
        if (current.signatureState != null) renderKind(DetectionKind.SIGNATURE)
    }

    private suspend fun renderKind(kind: DetectionKind) {
        val snapshot = _state.value
        val correction = if (kind == DetectionKind.PHOTO) snapshot.photoState else snapshot.signatureState
        if (correction == null) return

        _state.value = snapshot.copy(processing = true, error = null)
        val name = if (kind == DetectionKind.PHOTO) "photo_preview.jpg" else "signature_preview.jpg"
        _state.value = runCatching { render(correction, name) }
            .fold(
                { path ->
                    val latest = _state.value
                    if (kind == DetectionKind.PHOTO) latest.copy(photoPreviewPath = path, processing = false)
                    else latest.copy(signaturePreviewPath = path, processing = false)
                },
                { error -> _state.value.copy(processing = false, error = error.message ?: "Preview failed") }
            )
    }

    private suspend fun render(correction: PreviewCorrectionState, name: String): String =
        withContext(Dispatchers.Default) {
            val input = _state.value.source ?: error("No source image")
            val source = org.opencv.imgcodecs.Imgcodecs.imread(input.absolutePath)
            require(!source.empty()) { "Unable to decode source image" }
            try {
                val image = PreviewProcessor.render(source, correction)
                try {
                    val target = File(sessionDir, name)
                    check(org.opencv.imgcodecs.Imgcodecs.imwrite(target.absolutePath, image)) {
                        "Unable to write preview"
                    }
                    target.absolutePath
                } finally {
                    image.release()
                }
            } finally {
                source.release()
            }
        }

    fun loadBitmap(path: String?): Bitmap? {
        if (path.isNullOrBlank()) return null
        return BitmapFactory.decodeFile(path)
    }

    override fun close() {
        sessionDir.deleteRecursively()
    }
}
