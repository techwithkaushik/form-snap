package org.techwithkaushik.formsnap.feature.pipeline

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import android.content.Context
import android.graphics.BitmapFactory
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgcodecs.Imgcodecs
import org.techwithkaushik.formsnap.core.database.FeedbackInput
import org.techwithkaushik.formsnap.core.database.FeedbackRepository
import org.techwithkaushik.formsnap.core.processor.NativeDetection
import org.techwithkaushik.formsnap.core.processor.NativeProcessor
import java.io.File
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.roundToInt

class OfflinePipelineViewModel(
    context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : PipelineEngine {

    private val appContext = context.applicationContext
    private val repository = FeedbackRepository(appContext)
    private val processor = NativeProcessor()
    private val _state = MutableStateFlow(PipelineState())

    override val state: StateFlow<PipelineState> = _state.asStateFlow()

    private var dpi: Int = 300
    private var photoWidthMm: Double = 40.0
    private var photoHeightMm: Double = 50.0
    private var signatureWidthMm: Double = 50.0
    private var signatureHeightMm: Double = 20.0

    override suspend fun process(
        input: File,
        dpi: Int,
        photoWidthMm: Double,
        photoHeightMm: Double,
        signatureWidthMm: Double,
        signatureHeightMm: Double,
    ) {
        require(input.isFile) { "Input image does not exist" }
        this.dpi = dpi.coerceAtLeast(72)
        this.photoWidthMm = photoWidthMm.coerceAtLeast(1.0)
        this.photoHeightMm = photoHeightMm.coerceAtLeast(1.0)
        this.signatureWidthMm = signatureWidthMm.coerceAtLeast(1.0)
        this.signatureHeightMm = signatureHeightMm.coerceAtLeast(1.0)
        runPipeline(input)
    }

    override suspend fun reprocess() {
        val input = _state.value.input ?: return
        runPipeline(input)
    }

    private suspend fun runPipeline(input: File) {
        val revision = _state.value.revision + 1L
        _state.value = PipelineState(
            input = input,
            processing = true,
            revision = revision,
        )

        val result = runCatching {
            withContext(dispatcher) {
                detectAndRender(input)
            }
        }

        _state.value = result.fold(
            onSuccess = { detected ->
                detected.copy(
                    input = input,
                    processing = false,
                    revision = revision + 1L,
                )
            },
            onFailure = { error ->
                PipelineState(
                    input = input,
                    processing = false,
                    error = error.message ?: "Processing failed",
                    revision = revision + 1L,
                )
            },
        )
    }

    private fun detectAndRender(input: File): PipelineState {
        val bitmap = BitmapFactory.decodeFile(input.absolutePath)
            ?: error("Unable to decode input image")
        val source = Mat()
        try {
            Utils.bitmapToMat(bitmap, source)
            require(!source.empty()) { "Input image is empty" }

            val photo = processor.detect(
                source = source,
                kind = 0,
                adaptiveBias = adaptiveBias("PHOTO"),
                blockSize = adaptiveBlock("PHOTO"),
                localC = adaptiveC("PHOTO"),
            )
            val signature = processor.detect(
                source = source,
                kind = 1,
                adaptiveBias = adaptiveBias("SIGNATURE"),
                blockSize = adaptiveBlock("SIGNATURE"),
                localC = adaptiveC("SIGNATURE"),
            )

            val photoBounds = photo?.toBounds(DetectionKind.PHOTO, source.cols(), source.rows())
            val signatureBounds = signature?.toBounds(DetectionKind.SIGNATURE, source.cols(), source.rows())

            return PipelineState(
                photo = photoBounds,
                signature = signatureBounds,
                photoOutputPath = photo?.let { renderOutput(source, it, true) },
                signatureOutputPath = signature?.let { renderOutput(source, it, false) },
            )
        } finally {
            source.release()
            bitmap.recycle()
        }
    }

    private fun NativeDetection.toBounds(
        kind: DetectionKind,
        sourceWidth: Int,
        sourceHeight: Int,
    ): DetectionBounds = DetectionBounds(
        kind = kind,
        estimated = android.graphics.RectF(
            bounds.left.coerceIn(0f, sourceWidth - 1f),
            bounds.top.coerceIn(0f, sourceHeight - 1f),
            bounds.right.coerceIn(1f, sourceWidth.toFloat()),
            bounds.bottom.coerceIn(1f, sourceHeight.toFloat()),
        ),
        confidence = confidence.coerceIn(0f, 1f),
        sourceWidth = sourceWidth,
        sourceHeight = sourceHeight,
        hasPrintedFrame = kind == DetectionKind.PHOTO,
    )

    private fun renderOutput(
        source: Mat,
        detection: NativeDetection,
        isPhoto: Boolean,
    ): String {
        val width = source.cols()
        val height = source.rows()
        val left = detection.bounds.left.roundToInt().coerceIn(0, width - 1)
        val top = detection.bounds.top.roundToInt().coerceIn(0, height - 1)
        val right = detection.bounds.right.roundToInt().coerceIn(left + 1, width)
        val bottom = detection.bounds.bottom.roundToInt().coerceIn(top + 1, height)

        val roi = source.submat(top, bottom, left, right)
        try {
            val directory = File(appContext.cacheDir, "formsnap_pipeline").apply { mkdirs() }
            val file = File(directory, if (isPhoto) "photo_output.jpg" else "signature_output.jpg")
            require(Imgcodecs.imwrite(file.absolutePath, roi)) { "Unable to write output image" }
            return file.absolutePath
        } finally {
            roi.release()
        }
    }

    override suspend fun reject(kind: DetectionKind) {
        val current = _state.value
        _state.value = when (kind) {
            DetectionKind.PHOTO -> current.copy(
                photo = null,
                photoOutputPath = null,
                revision = current.revision + 1L,
            )
            DetectionKind.SIGNATURE -> current.copy(
                signature = null,
                signatureOutputPath = null,
                revision = current.revision + 1L,
            )
        }
    }

    override suspend fun accept(kind: DetectionKind) {
        val current = _state.value
        val detection = when (kind) {
            DetectionKind.PHOTO -> current.photo
            DetectionKind.SIGNATURE -> current.signature
        } ?: return

        val bounds = detection.estimated
        val width = bounds.width().coerceAtLeast(1f)
        val height = bounds.height().coerceAtLeast(1f)
        val now = System.currentTimeMillis()
        val kindName = detection.kind.name
        val sampleKey = sha256(
            listOf(
                current.input?.absolutePath.orEmpty(),
                kindName,
                detection.sourceWidth,
                detection.sourceHeight,
                width,
                height,
            ).joinToString("|"),
        )

        repository.record(
            FeedbackInput(
                sampleKey = sampleKey,
                kind = kindName,
                sourceWidth = detection.sourceWidth,
                sourceHeight = detection.sourceHeight,
                estimatedLeft = bounds.left.toDouble(),
                estimatedTop = bounds.top.toDouble(),
                estimatedRight = bounds.right.toDouble(),
                estimatedBottom = bounds.bottom.toDouble(),
                correctedLeft = bounds.left.toDouble(),
                correctedTop = bounds.top.toDouble(),
                correctedRight = bounds.right.toDouble(),
                correctedBottom = bounds.bottom.toDouble(),
                adaptiveBias = adaptiveBias(kindName),
                blockSize = adaptiveBlock(kindName),
                localC = adaptiveC(kindName),
                accepted = true,
                actionIndex = PipelineAction.ACCEPT.ordinal,
                contextBrightness = 0.5,
                contextEdgeDensity = 0.25,
                contextAspect = width.toDouble() / height.toDouble(),
                createdAt = now,
            ),
        )
    }

    override suspend fun applyEditedResult(
        kind: DetectionKind,
        editedFile: File,
    ) {
        require(editedFile.isFile) { "Edited file does not exist" }
        val directory = File(appContext.cacheDir, "formsnap_pipeline").apply { mkdirs() }
        val target = File(
            directory,
            if (kind == DetectionKind.PHOTO) "photo_edited.jpg" else "signature_edited.jpg",
        )
        editedFile.inputStream().use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        _state.value = when (kind) {
            DetectionKind.PHOTO -> _state.value.copy(
                photoOutputPath = target.absolutePath,
                revision = _state.value.revision + 1L,
            )
            DetectionKind.SIGNATURE -> _state.value.copy(
                signatureOutputPath = target.absolutePath,
                revision = _state.value.revision + 1L,
            )
        }
    }

    private fun adaptiveBias(kind: String): Double {
        val drift = repository.drift(kind) ?: return 0.0
        if (drift.samples < 4L) return 0.0
        val average = (drift.left + drift.right + drift.top + drift.bottom) / 4.0
        val confidence = (drift.samples / 32.0).coerceIn(0.0, 1.0)
        return (average * 0.01 * confidence).coerceIn(-8.0, 8.0)
    }

    private fun adaptiveBlock(kind: String): Int {
        val samples = repository.drift(kind)?.samples ?: 0L
        return when {
            samples >= 64L -> 35
            samples >= 24L -> 33
            else -> 31
        }
    }

    private fun adaptiveC(kind: String): Double {
        val drift = repository.drift(kind) ?: return 8.0
        if (drift.samples < 4L) return 8.0
        val magnitude = abs(drift.left) + abs(drift.top) + abs(drift.right) + abs(drift.bottom)
        return (8.0 + magnitude * 0.003).coerceIn(4.0, 14.0)
    }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    override fun close() {
        File(appContext.cacheDir, "formsnap_pipeline").deleteRecursively()
        _state.value = PipelineState()
    }
}