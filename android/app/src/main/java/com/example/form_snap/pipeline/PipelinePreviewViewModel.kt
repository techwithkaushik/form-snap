package org.techwithkaushik.formSnap.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.android.Utils
import org.opencv.core.Mat
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
        val current = state.photoState ?: return
        state = state.copy(processing = true, error = null)
        state = runCatching { render(current, "photo_preview.jpg") }
            .fold({ state.copy(photoPreviewPath = it, processing = false) }, { state.copy(processing = false, error = it.message ?: "Photo preview failed") })
    }

    suspend fun renderSignature() {
        val current = state.signatureState ?: return
        state = state.copy(processing = true, error = null)
        state = runCatching { render(current, "signature_preview.jpg") }
            .fold({ state.copy(signaturePreviewPath = it, processing = false) }, { state.copy(processing = false, error = it.message ?: "Signature preview failed") })
    }

    private suspend fun render(correction: PreviewCorrectionState, name: String): String = withContext(Dispatchers.Default) {
        val input = state.source ?: error("No source image")
        val source = org.opencv.imgcodecs.Imgcodecs.imread(input.absolutePath)
        val session = ProcessingSession.create(context)
        try {
            val image = PreviewProcessor.render(source, correction)
            val target = session.file(name)
            check(org.opencv.imgcodecs.Imgcodecs.imwrite(target.absolutePath, image)) { "Unable to write preview" }
            image.release()
            target.absolutePath
        } finally {
            source.release()
            session.closeAndDelete()
        }
    }
}