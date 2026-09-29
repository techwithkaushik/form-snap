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

private data class PreviewDetectionBundle(
    val detection: DetectionResult,
    val learnedPhotoBounds: android.graphics.RectF?,
    val learnedSignatureBounds: android.graphics.RectF?,
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
    ) {
        this.dpi = dpi.coerceAtLeast(72)
        this.photoWidthMm = photoWidthMm.coerceAtLeast(1.0)
        this.photoHeightMm = photoHeightMm.coerceAtLeast(1.0)
        this.signatureWidthMm = signatureWidthMm.coerceAtLeast(1.0)
        this.signatureHeightMm = signatureHeightMm.coerceAtLeast(1.0)

        previewJob?.cancel()
        clearCurrentResults()
        if (!preserveRejectedCandidates) clearRejectedCandidates()
        _state.value = PreviewProcessingState(source = input, processing = true)

        try {
            val loaded = withContext(Dispatchers.Default) {
                val source = org.opencv.imgcodecs.Imgcodecs.imread(input.absolutePath)
                require(!source.empty()) { "Unable to decode input image" }
                try {
                    val detection = UniversalDetectionEngine.detect(
                        source = source,
                        rejectedPhotoBounds = rejectedPhotoBounds + RejectedDetectionStore.load(
                            context,
                            input,
                            DetectionKind.PHOTO,
                            source.cols(),
                            source.rows(),
                        ),
                        rejectedSignatureBounds = rejectedSignatureBounds + RejectedDetectionStore.load(
                            context,
                            input,
                            DetectionKind.SIGNATURE,
                            source.cols(),
                            source.rows(),
                        ),
                    )

                    // Match the normalized spatial relationship of the detected
                    // photo/signature pair first. A topology profile is applied only
                    // when its measured match score is at least 0.75; otherwise fall
                    // back to condition-aware correction learning.
                    val topology = LayoutTopologyMatcher.signature(
                        sourceWidth = source.cols(),
                        sourceHeight = source.rows(),
                        photoBounds = detection.photo?.bounds,
                        signatureBounds = detection.signature?.bounds,
                    )
                    fun learnedBounds(candidate: DetectionCandidate?): android.graphics.RectF? {
                        candidate ?: return null
                        if (topology != null) {
                            val topologyProfile = LayoutTopologyStore.best(context, topology, candidate.kind)
                            if (topologyProfile != null) {
                                val corrected = LayoutTopologyMatcher.apply(
                                    bounds = candidate.bounds,
                                    profile = topologyProfile,
                                    actualSignature = topology,
                                    sourceWidth = source.cols(),
                                    sourceHeight = source.rows(),
                                )
                                if (corrected != null) return corrected
                            }
                        }
                        val features = ImageConditionFeatures.measure(source, candidate.bounds)
                        val aspect = candidate.bounds.height() /
                            candidate.bounds.width().coerceAtLeast(1f)
                        val learned = LearningStore.best(
                            context = context,
                            kind = candidate.kind,
                            conditionBrightness = features?.brightness,
                            conditionContrast = features?.contrast,
                            conditionSaturation = features?.saturation,
                            conditionEdgeDensity = features?.edgeDensity,
                            aspectRatio = features?.aspectRatio ?: aspect,
                        )
                        return android.graphics.RectF(
                            LearnedProfileApplier.apply(candidate, learned, features).bounds,
                        )
                    }

                    PreviewDetectionBundle(
                        detection = detection,
                        learnedPhotoBounds = learnedBounds(detection.photo),
                        learnedSignatureBounds = learnedBounds(detection.signature),
                    )
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
                            loaded.learnedPhotoBounds ?: candidate.bounds,
                        ),
                    )
                },
                signatureState = detection.signature?.let { candidate ->
                    PreviewCorrectionStateFactory.fromCandidate(
                        candidate,
                        detection.sourceWidth,
                        detection.sourceHeight,
                    ).copy(
                        currentBounds = android.graphics.RectF(
                            loaded.learnedSignatureBounds ?: candidate.bounds,
                        ),
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

    suspend fun accept(kind: DetectionKind, recordFeedback: Boolean = true) {
        val correction = stateFor(kind) ?: return
        val automatic = candidateFor(kind) ?: return

        if (recordFeedback) {
            val sourceFile = _state.value.source
            val features = sourceFile?.takeIf { it.isFile }?.let { file ->
                withContext(Dispatchers.Default) {
                    val source = org.opencv.imgcodecs.Imgcodecs.imread(file.absolutePath)
                    try {
                        if (source.empty()) null
                        else ImageConditionFeatures.measure(source, automatic.bounds)
                    } finally {
                        source.release()
                    }
                }
            }
            val feedback = correction.correction(automatic).copy(
                accepted = true,
                conditionFeatures = features,
            )
            FeedbackRecorder.record(context, feedback)

            // Record normalized layout topology only for an explicit manual edit;
            // simply accepting an untouched detector crop must not teach a zero delta.
            if (correction.dirty && FeedbackRecorder.isSafeFeedback(feedback)) {
                val snapshot = _state.value
                val topology = LayoutTopologyMatcher.signature(
                    sourceWidth = correction.sourceWidth,
                    sourceHeight = correction.sourceHeight,
                    photoBounds = snapshot.photoState?.automaticBounds,
                    signatureBounds = snapshot.signatureState?.automaticBounds,
                )
                if (topology != null) {
                    val deltas = CropDeltaNormalizer.normalize(
                        leftPixels = correction.currentBounds.left - automatic.bounds.left,
                        topPixels = correction.currentBounds.top - automatic.bounds.top,
                        rightPixels = correction.currentBounds.right - automatic.bounds.right,
                        bottomPixels = correction.currentBounds.bottom - automatic.bounds.bottom,
                        width = automatic.bounds.width().coerceAtLeast(1f),
                        height = automatic.bounds.height().coerceAtLeast(1f),
                    )
                    LayoutTopologyStore.record(
                        context,
                        LayoutCorrectionProfile(
                            kind = kind,
                            signature = topology,
                            deltas = deltas,
                            sampleCount = 1,
                            confidence = 1f,
                        ),
                    )
                }
            }
        }
        updateCorrectionState(kind, correction.accept())
    }

    fun reject(kind: DetectionKind) {
        val current = _state.value
        val correction = stateFor(kind)
        if (correction != null) {
            val rejected = android.graphics.RectF(correction.automaticBounds)
            current.source?.let { sourceFile ->
                RejectedDetectionStore.record(
                    context = context,
                    source = sourceFile,
                    kind = kind,
                    bounds = rejected,
                    sourceWidth = correction.sourceWidth,
                    sourceHeight = correction.sourceHeight,
                )
            }
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

    suspend fun applyExternalCorrection(kind: DetectionKind, bounds: android.graphics.RectF) {
        val current = stateFor(kind) ?: return
        val width = current.sourceWidth.toFloat().coerceAtLeast(1f)
        val height = current.sourceHeight.toFloat().coerceAtLeast(1f)
        val left = bounds.left.coerceIn(0f, width - 1f)
        val top = bounds.top.coerceIn(0f, height - 1f)
        val right = bounds.right.coerceIn(left + 1f, width)
        val bottom = bounds.bottom.coerceIn(top + 1f, height)
        applyCorrection(kind, current.withBounds(android.graphics.RectF(left, top, right, bottom)))
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
    ) {
        require(correctedFile.exists()) { "Corrected crop does not exist" }

        withContext(Dispatchers.Default) {
            val target = File(
                sessionDir,
                if (kind == DetectionKind.PHOTO) "photo_ucrop.jpg" else "signature_ucrop.jpg",
            )
            correctedFile.inputStream().use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }

            val previewTarget = File(
                sessionDir,
                if (kind == DetectionKind.PHOTO) "photo_preview.jpg" else "signature_preview.jpg",
            )
            target.inputStream().use { input ->
                previewTarget.outputStream().use { output -> input.copyTo(output) }
            }
        }

        val current = _state.value
        _state.value = when (kind) {
            DetectionKind.PHOTO -> current.copy(
                photoPreviewPath = File(sessionDir, "photo_preview.jpg").absolutePath,
                photoPreviewVersion = current.photoPreviewVersion + 1L,
                processing = false,
                error = null,
            )
            DetectionKind.SIGNATURE -> current.copy(
                signaturePreviewPath = File(sessionDir, "signature_preview.jpg").absolutePath,
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
