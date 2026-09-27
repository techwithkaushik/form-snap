package org.techwithkaushik.formSnap.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    val photoPreviewVersion: Long = 0L,
    val signaturePreviewVersion: Long = 0L,
    val processing: Boolean = false,
    val error: String? = null,
)

class PipelinePreviewViewModel(private val context: Context) : AutoCloseable {
    private var dpi: Int = 300
    private var photoWidthMm: Double = 40.0
    private var photoHeightMm: Double = 50.0
    private var signatureWidthMm: Double = 50.0
    private var signatureHeightMm: Double = 20.0

    private val _state = MutableStateFlow(PreviewProcessingState())
    val state: StateFlow<PreviewProcessingState> = _state

    private val sessionDir = File(ProcessingPaths.root(context), "preview_session").apply { mkdirs() }
    private val previewScope = CoroutineScope(Dispatchers.Main.immediate)
    private var previewJob: Job? = null

    suspend fun load(
        input: File,
        dpi: Int = 300,
        photoWidthMm: Double = 40.0,
        photoHeightMm: Double = 50.0,
        signatureWidthMm: Double = 50.0,
        signatureHeightMm: Double = 20.0,
    ) {
        this.dpi = dpi.coerceAtLeast(72)
        this.photoWidthMm = photoWidthMm.coerceAtLeast(1.0)
        this.photoHeightMm = photoHeightMm.coerceAtLeast(1.0)
        this.signatureWidthMm = signatureWidthMm.coerceAtLeast(1.0)
        this.signatureHeightMm = signatureHeightMm.coerceAtLeast(1.0)
        previewJob?.cancel()
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

    fun updateCorrectionState(kind: DetectionKind, correction: PreviewCorrectionState) {
        _state.value = when (kind) {
            DetectionKind.PHOTO -> _state.value.copy(photoState = correction)
            DetectionKind.SIGNATURE -> _state.value.copy(signatureState = correction)
        }
    }

    fun schedulePreview(kind: DetectionKind, correction: PreviewCorrectionState, delayMs: Long = 100L) {
        updateCorrectionState(kind, correction)
        previewJob?.cancel()
        previewJob = previewScope.launch {
            delay(delayMs)
            renderKind(kind)
        }
    }

    suspend fun applyCorrection(kind: DetectionKind, correction: PreviewCorrectionState) {
        updateCorrectionState(kind, correction)
        renderKind(kind)
    }

    suspend fun replacePreviewFromExternal(kind: DetectionKind, correctedFile: File) {
        require(correctedFile.exists()) { "Corrected crop does not exist" }
        withContext(Dispatchers.Default) {
            val suffix = if (kind == DetectionKind.PHOTO) "photo_ucrop.jpg" else "signature_ucrop.jpg"
            val target = File(sessionDir, suffix)
            correctedFile.inputStream().use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        }
        val path = File(sessionDir, if (kind == DetectionKind.PHOTO) "photo_preview.jpg" else "signature_preview.jpg").absolutePath
        withContext(Dispatchers.Default) {
            File(path).outputStream().use { output ->
                File(sessionDir, if (kind == DetectionKind.PHOTO) "photo_ucrop.jpg" else "signature_ucrop.jpg").inputStream().use { input ->
                    input.copyTo(output)
                }
            }
        }
        val current = _state.value
        _state.value = if (kind == DetectionKind.PHOTO) {
            current.copy(
                photoPreviewPath = path,
                photoPreviewVersion = current.photoPreviewVersion + 1L,
                processing = false,
                error = null,
            )
        } else {
            current.copy(
                signaturePreviewPath = path,
                signaturePreviewVersion = current.signaturePreviewVersion + 1L,
                processing = false,
                error = null,
            )
        }
    }

    private suspend fun stateFor(kind: DetectionKind): PreviewCorrectionState? =
        when (kind) {
            DetectionKind.PHOTO -> _state.value.photoState
            DetectionKind.SIGNATURE -> _state.value.signatureState
        }

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
                    if (kind == DetectionKind.PHOTO) latest.copy(photoPreviewPath = path, photoPreviewVersion = latest.photoPreviewVersion + 1L, processing = false)
                    else latest.copy(signaturePreviewPath = path, signaturePreviewVersion = latest.signaturePreviewVersion + 1L, processing = false)
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
                val image = PreviewProcessor.render(
                    source = source,
                    state = correction,
                    dpi = dpi,
                    widthMm = if (correction.kind == DetectionKind.PHOTO) photoWidthMm else signatureWidthMm,
                    heightMm = if (correction.kind == DetectionKind.PHOTO) photoHeightMm else signatureHeightMm,
                )
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
        previewJob?.cancel()
        previewScope.cancel()
        sessionDir.deleteRecursively()
    }
}
