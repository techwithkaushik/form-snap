package org.techwithkaushik.formSnap.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.techwithkaushik.formSnap.foundation.ProcessingPaths
import java.io.File
import kotlin.math.roundToInt

private data class PreviewDetectionBundle(
    val detection: DetectionResult,
)

data class PreviewProcessingState(
    val source: File? = null,
    val photoState: PreviewCorrectionState? = null,
    val signatureState: PreviewCorrectionState? = null,
    val photoConfidence: Float? = null,
    val signatureConfidence: Float? = null,
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
    private var activeForcedKind: DetectionKind? = null

    private val _state = MutableStateFlow(PreviewProcessingState())
    val state: StateFlow<PreviewProcessingState> = _state

    // Every preview gets an isolated temporary directory; parallel/reopened sessions cannot overwrite each other.
    private val sessionDir = ProcessingPaths.session(context)
    private val previewScope = CoroutineScope(Dispatchers.Main.immediate)
    private var previewJob: Job? = null
    private val rejectedPhotoBounds = mutableSetOf<android.graphics.RectF>()
    private val rejectedSignatureBounds = mutableSetOf<android.graphics.RectF>()

    private fun clearCurrentResults() {
        _state.value = PreviewProcessingState(
            source = _state.value.source,
            processing = true,
            error = null,
        )
    }

    private fun clearRejectedCandidates() {
        rejectedPhotoBounds.clear()
        rejectedSignatureBounds.clear()
    }

    suspend fun load(
        input: File,
        dpi: Int = 300,
        photoWidthMm: Double = 40.0,
        photoHeightMm: Double = 50.0,
        signatureWidthMm: Double = 50.0,
        signatureHeightMm: Double = 20.0,
        preserveRejectedCandidates: Boolean = false,
        forcedKind: DetectionKind? = null,
    ) {
        this.dpi = dpi.coerceAtLeast(72)
        this.photoWidthMm = photoWidthMm.coerceAtLeast(1.0)
        this.photoHeightMm = photoHeightMm.coerceAtLeast(1.0)
        this.signatureWidthMm = signatureWidthMm.coerceAtLeast(1.0)
        this.signatureHeightMm = signatureHeightMm.coerceAtLeast(1.0)
        if (!preserveRejectedCandidates || forcedKind != null) {
            activeForcedKind = forcedKind
        }
        val effectiveForcedKind = activeForcedKind

        previewJob?.cancel()
        clearCurrentResults()
        if (!preserveRejectedCandidates) clearRejectedCandidates()
        _state.value = PreviewProcessingState(source = input, processing = true)

        try {
            val loaded = withContext(Dispatchers.Default) {
                val source = org.opencv.imgcodecs.Imgcodecs.imread(input.absolutePath)
                require(!source.empty()) { "Unable to decode input image" }
                try {
                    val detection = if (effectiveForcedKind != null) {
                        // Close-up capture mode means the user has already framed
                        // the requested subject. Treat the full source as the crop
                        // rather than asking the form detector to find a box inside
                        // an image that may contain no printed frame at all.
                        val candidate = DetectionCandidate(
                            kind = effectiveForcedKind,
                            bounds = android.graphics.RectF(
                                0f,
                                0f,
                                source.cols().toFloat(),
                                source.rows().toFloat(),
                            ),
                            confidence = 1f,
                            source = "opencv-close-capture",
                        )
                        DetectionResult(
                            sourceWidth = source.cols(),
                            sourceHeight = source.rows(),
                            photo = candidate.takeIf { effectiveForcedKind == DetectionKind.PHOTO },
                            signature = candidate.takeIf { effectiveForcedKind == DetectionKind.SIGNATURE },
                            detectorVersion = "opencv-close-capture-v1",
                        )
                    } else {
                        UniversalDetectionEngine.detect(
                            source = source,
                            rejectedPhotoBounds = rejectedPhotoBounds,
                            rejectedSignatureBounds = rejectedSignatureBounds,
                        )
                    }

                    PreviewDetectionBundle(detection = detection)
                } finally {
                    source.release()
                }
            }
            val detection = loaded.detection

            _state.value = PreviewProcessingState(
                source = input,
                photoState = detection.photo?.let { candidate ->
                    PreviewCorrectionStateFactory.fromCandidate(
                        candidate,
                        detection.sourceWidth,
                        detection.sourceHeight,
                    ).copy(
                        currentBounds = android.graphics.RectF(
                            candidate.bounds,
                        ),
                        appearance = AppearanceAdjustments(),
                    )
                },
                signatureState = detection.signature?.let { candidate ->
                    PreviewCorrectionStateFactory.fromCandidate(
                        candidate,
                        detection.sourceWidth,
                        detection.sourceHeight,
                    ).copy(
                        currentBounds = android.graphics.RectF(
                            candidate.bounds,
                        ),
                        appearance = AppearanceAdjustments(),
                    )
                },
                photoConfidence = detection.photo?.confidence,
                signatureConfidence = detection.signature?.confidence,
            )

            renderDetectedPreviews()
        } catch (t: Throwable) {
            // Never turn lifecycle/job cancellation into a visible processing error.
            // Propagating cancellation lets Compose stop obsolete work immediately.
            if (t is CancellationException) throw t
            _state.value = PreviewProcessingState(
                source = input,
                error = t.message ?: "Preview failed",
            )
        }
    }

    suspend fun renderPhoto() = renderKind(DetectionKind.PHOTO)
    suspend fun renderSignature() = renderKind(DetectionKind.SIGNATURE)

    suspend fun redetect() {
        val input = _state.value.source ?: return
        clearCurrentResults()
        load(
            input = input,
            dpi = dpi,
            photoWidthMm = photoWidthMm,
            photoHeightMm = photoHeightMm,
            signatureWidthMm = signatureWidthMm,
            signatureHeightMm = signatureHeightMm,
            preserveRejectedCandidates = true,
        )
    }

    suspend fun accept(kind: DetectionKind) {
        val correction = stateFor(kind) ?: return
        // Accept confirms the current crop for this output only. It does not
        // create a training profile or change future detections.
        updateCorrectionState(kind, correction.accept())
    }

    fun reject(kind: DetectionKind) {
        val current = _state.value
        val correction = stateFor(kind)
        if (correction != null) {
            val rejected = android.graphics.RectF(correction.automaticBounds)
            when (kind) {
                DetectionKind.PHOTO -> rejectedPhotoBounds.add(rejected)
                DetectionKind.SIGNATURE -> rejectedSignatureBounds.add(rejected)
            }
        }
        _state.value = when (kind) {
            DetectionKind.PHOTO -> current.copy(
                photoState = null,
                photoConfidence = null,
                photoPreviewPath = null,
                photoPreviewVersion = current.photoPreviewVersion + 1L,
                processing = false,
                error = null,
            )
            DetectionKind.SIGNATURE -> current.copy(
                signatureState = null,
                signatureConfidence = null,
                signaturePreviewPath = null,
                signaturePreviewVersion = current.signaturePreviewVersion + 1L,
                processing = false,
                error = null,
            )
        }
    }

    fun updateCorrectionState(kind: DetectionKind, correction: PreviewCorrectionState) {
        _state.value = when (kind) {
            DetectionKind.PHOTO -> _state.value.copy(photoState = correction)
            DetectionKind.SIGNATURE -> _state.value.copy(signatureState = correction)
        }
    }

    fun schedulePreview(
        kind: DetectionKind,
        correction: PreviewCorrectionState,
        delayMs: Long = 220L,
    ) {
        updateCorrectionState(kind, correction)
        previewJob?.cancel()
        previewJob = previewScope.launch {
            delay(delayMs)
            val current = stateFor(kind)
            if (current == correction) {
                renderKind(kind)
            }
        }
    }

    suspend fun applyExternalCorrection(
        kind: DetectionKind,
        bounds: android.graphics.RectF,
        brightness: Float? = null,
        contrast: Float? = null,
        sharpness: Float? = null,
        saturation: Float? = null,
        denoise: Float? = null,
    ) {
        val current = stateFor(kind) ?: return
        val width = current.sourceWidth.toFloat().coerceAtLeast(1f)
        val height = current.sourceHeight.toFloat().coerceAtLeast(1f)
        val left = bounds.left.coerceIn(0f, width - 1f)
        val top = bounds.top.coerceIn(0f, height - 1f)
        val right = bounds.right.coerceIn(left + 1f, width)
        val bottom = bounds.bottom.coerceIn(top + 1f, height)
        val updated = current.withBounds(android.graphics.RectF(left, top, right, bottom)).copy(
            appearance = current.appearance.copy(
                brightness = brightness ?: current.appearance.brightness,
                contrast = contrast ?: current.appearance.contrast,
                sharpness = sharpness ?: current.appearance.sharpness,
                saturation = saturation ?: current.appearance.saturation,
                denoise = denoise ?: current.appearance.denoise,
            ),
        )
        applyCorrection(kind, updated)
    }

    suspend fun applyCorrection(
        kind: DetectionKind,
        correction: PreviewCorrectionState,
    ) {
        updateCorrectionState(kind, correction)
        renderKind(kind)
    }

    suspend fun replacePreviewFromExternal(
        kind: DetectionKind,
        correctedFile: File,
        correctedBounds: android.graphics.RectF? = null,
    ) {
        require(correctedFile.isFile && correctedFile.length() > 0L) {
            "Corrected crop does not exist or is empty"
        }

        val widthMm = if (kind == DetectionKind.PHOTO) photoWidthMm else signatureWidthMm
        val heightMm = if (kind == DetectionKind.PHOTO) photoHeightMm else signatureHeightMm
        val widthPx = (widthMm * dpi / 25.4).roundToInt().coerceAtLeast(1)
        val heightPx = (heightMm * dpi / 25.4).roundToInt().coerceAtLeast(1)
        require(widthPx.toLong() * heightPx.toLong() <= 24_000_000L) {
            "Requested output dimensions are too large"
        }

        val previewTarget = File(
            sessionDir,
            if (kind == DetectionKind.PHOTO) "photo_preview.jpg" else "signature_preview.jpg",
        )
        withContext(Dispatchers.Default) {
            val source = org.opencv.imgcodecs.Imgcodecs.imread(correctedFile.absolutePath)
            require(!source.empty()) { "Unable to decode the adjusted crop" }
            val resized = org.opencv.core.Mat()
            val params = org.opencv.core.MatOfInt(
                org.opencv.imgcodecs.Imgcodecs.IMWRITE_JPEG_QUALITY,
                96,
            )
            try {
                org.opencv.imgproc.Imgproc.resize(
                    source,
                    resized,
                    org.opencv.core.Size(widthPx.toDouble(), heightPx.toDouble()),
                    0.0,
                    0.0,
                    if (kind == DetectionKind.PHOTO) {
                        org.opencv.imgproc.Imgproc.INTER_AREA
                    } else {
                        org.opencv.imgproc.Imgproc.INTER_CUBIC
                    },
                )
                check(org.opencv.imgcodecs.Imgcodecs.imwrite(previewTarget.absolutePath, resized, params)) {
                    "Unable to save the adjusted crop preview"
                }
            } finally {
                params.release()
                resized.release()
                source.release()
            }
        }

        val current = _state.value
        val correction = stateFor(kind)
        val mappedCorrection = correctedBounds?.let { bounds ->
            correction?.let { state ->
                val width = state.sourceWidth.toFloat().coerceAtLeast(1f)
                val height = state.sourceHeight.toFloat().coerceAtLeast(1f)
                val left = bounds.left.coerceIn(0f, width - 1f)
                val top = bounds.top.coerceIn(0f, height - 1f)
                val right = bounds.right.coerceIn(left + 1f, width)
                val bottom = bounds.bottom.coerceIn(top + 1f, height)
                state.withBounds(android.graphics.RectF(left, top, right, bottom))
            }
        }
        _state.value = when (kind) {
            DetectionKind.PHOTO -> current.copy(
                photoState = mappedCorrection ?: current.photoState,
                photoPreviewPath = previewTarget.absolutePath,
                photoPreviewVersion = current.photoPreviewVersion + 1L,
                processing = false,
                error = null,
            )
            DetectionKind.SIGNATURE -> current.copy(
                signatureState = mappedCorrection ?: current.signatureState,
                signaturePreviewPath = previewTarget.absolutePath,
                signaturePreviewVersion = current.signaturePreviewVersion + 1L,
                processing = false,
                error = null,
            )
        }
    }

    private fun candidateFor(kind: DetectionKind): DetectionCandidate? =
        when (kind) {
            DetectionKind.PHOTO -> _state.value.photoState?.let {
                DetectionCandidate(
                    kind = it.kind,
                    bounds = it.automaticBounds,
                    confidence = _state.value.photoConfidence ?: 0f,
                    source = "preview-automatic",
                )
            }
            DetectionKind.SIGNATURE -> _state.value.signatureState?.let {
                DetectionCandidate(
                    kind = it.kind,
                    bounds = it.automaticBounds,
                    confidence = _state.value.signatureConfidence ?: 0f,
                    source = "preview-automatic",
                )
            }
        }

    private fun stateFor(kind: DetectionKind): PreviewCorrectionState? =
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
        val correction = when (kind) {
            DetectionKind.PHOTO -> snapshot.photoState
            DetectionKind.SIGNATURE -> snapshot.signatureState
        } ?: return

        _state.value = snapshot.copy(processing = true, error = null)

        val name = if (kind == DetectionKind.PHOTO) {
            "photo_preview.jpg"
        } else {
            "signature_preview.jpg"
        }

        _state.value = runCatching {
            render(correction, name)
        }.fold(
            { path ->
                val latest = _state.value
                if (kind == DetectionKind.PHOTO) {
                    latest.copy(
                        photoPreviewPath = path,
                        photoPreviewVersion = latest.photoPreviewVersion + 1L,
                        processing = false,
                    )
                } else {
                    latest.copy(
                        signaturePreviewPath = path,
                        signaturePreviewVersion = latest.signaturePreviewVersion + 1L,
                        processing = false,
                    )
                }
            },
            { error ->
                // runCatching also catches CancellationException; restore structured
                // concurrency by propagating it instead of publishing a stale error.
                if (error is CancellationException) throw error
                _state.value.copy(
                    processing = false,
                    error = error.message ?: "Preview failed",
                )
            },
        )
    }

    private suspend fun render(
        correction: PreviewCorrectionState,
        name: String,
    ): String = withContext(Dispatchers.Default) {
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
                check(
                    org.opencv.imgcodecs.Imgcodecs.imwrite(
                        target.absolutePath,
                        image,
                    ),
                ) { "Unable to write preview" }
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
