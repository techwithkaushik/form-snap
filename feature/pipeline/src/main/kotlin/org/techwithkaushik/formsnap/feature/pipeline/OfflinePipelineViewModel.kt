package org.techwithkaushik.formsnap.feature.pipeline

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.RectF
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgcodecs.Imgcodecs
import org.techwithkaushik.formsnap.core.database.DetectionSample
import org.techwithkaushik.formsnap.core.database.FormSnapDatabaseProvider
import org.techwithkaushik.formsnap.core.database.DatabaseDriverFactory
import org.techwithkaushik.formsnap.core.database.LearningContext
import org.techwithkaushik.formsnap.core.database.LearningRepository
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
    private val database = FormSnapDatabaseProvider(DatabaseDriverFactory(appContext)).database
    private val learning = LearningRepository(database)
    private val processor = NativeProcessor()
    private val stateHolder = MutableStateFlow(PipelineState())

    override val state: StateFlow<PipelineState> = stateHolder.asStateFlow()

    private var dpi = 300
    private var photoWidthMm = 40.0
    private var photoHeightMm = 50.0
    private var signatureWidthMm = 50.0
    private var signatureHeightMm = 20.0

    override suspend fun process(
        input: File,
        dpi: Int,
        photoWidthMm: Double,
        photoHeightMm: Double,
        signatureWidthMm: Double,
        signatureHeightMm: Double,
    ) {
        require(input.isFile) { "Input image does not exist." }
        this.dpi = dpi.coerceAtLeast(72)
        this.photoWidthMm = photoWidthMm.coerceAtLeast(1.0)
        this.photoHeightMm = photoHeightMm.coerceAtLeast(1.0)
        this.signatureWidthMm = signatureWidthMm.coerceAtLeast(1.0)
        this.signatureHeightMm = signatureHeightMm.coerceAtLeast(1.0)
        runPipeline(input)
    }

    override suspend fun reprocess() {
        stateHolder.value.input?.let(::runPipeline)
    }

    private suspend fun runPipeline(input: File) {
        val revision = stateHolder.value.revision + 1L
        stateHolder.value = PipelineState(input = input, processing = true, revision = revision)

        val result = runCatching {
            withContext(dispatcher) { detectAndRender(input) }
        }

        stateHolder.value = result.fold(
            onSuccess = { it.copy(input = input, processing = false, revision = revision + 1L) },
            onFailure = { error ->
                PipelineState(
                    input = input,
                    processing = false,
                    error = error.message ?: "Processing failed.",
                    revision = revision + 1L,
                )
            },
        )
    }

    private fun detectAndRender(input: File): PipelineState {
        val bitmap = BitmapFactory.decodeFile(input.absolutePath)
            ?: error("Unable to decode input image.")
        val source = Mat()
        try {
            Utils.bitmapToMat(bitmap, source)
            require(!source.empty()) { "Input image is empty." }

            val photo = processor.detect(
                source,
                kind = 0,
                adaptiveBias = learning.recommendedBias("PHOTO"),
            )
            val signature = processor.detect(
                source,
                kind = 1,
                adaptiveBias = learning.recommendedBias("SIGNATURE"),
            )

            return PipelineState(
                photo = photo?.toBounds(DetectionKind.PHOTO, source.cols(), source.rows()),
                signature = signature?.toBounds(DetectionKind.SIGNATURE, source.cols(), source.rows()),
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
    ): DetectionBounds {
        val left = bounds.left.coerceIn(0f, (sourceWidth - 1).coerceAtLeast(0).toFloat())
        val top = bounds.top.coerceIn(0f, (sourceHeight - 1).coerceAtLeast(0).toFloat())
        val right = bounds.right.coerceIn(left + 1f, sourceWidth.toFloat())
        val bottom = bounds.bottom.coerceIn(top + 1f, sourceHeight.toFloat())
        return DetectionBounds(
            kind = kind,
            estimated = RectF(left, top, right, bottom),
            confidence = confidence.coerceIn(0f, 1f),
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            hasPrintedFrame = kind == DetectionKind.PHOTO,
        )
    }

    private fun renderOutput(
        source: Mat,
        detection: NativeDetection,
        isPhoto: Boolean,
    ): String {
        val left = detection.bounds.left.roundToInt().coerceIn(0, source.cols() - 1)
        val top = detection.bounds.top.roundToInt().coerceIn(0, source.rows() - 1)
        val right = detection.bounds.right.roundToInt().coerceIn(left + 1, source.cols())
        val bottom = detection.bounds.bottom.roundToInt().coerceIn(top + 1, source.rows())
        val outputDirectory = File(appContext.cacheDir, "formsnap_pipeline").apply { mkdirs() }
        val outputFile = File(
            outputDirectory,
            if (isPhoto) "photo_output.jpg" else "signature_output.jpg",
        )
        val roi = source.submat(top, bottom, left, right)
        return try {
            require(Imgcodecs.imwrite(outputFile.absolutePath, roi)) {
                "Unable to write pipeline output."
            }
            outputFile.absolutePath
        } finally {
            roi.release()
        }
    }

    override suspend fun reject(kind: DetectionKind) {
        val current = stateHolder.value
        val detection = when (kind) {
            DetectionKind.PHOTO -> current.photo
            DetectionKind.SIGNATURE -> current.signature
        } ?: return

        val now = System.currentTimeMillis()
        val sample = detection.toSample(
            sampleKey = sampleKey(current.input, kind, detection),
            corrected = detection.estimated,
            accepted = false,
            now = now,
        )
        learning.recordRejection(sample, contextFor(detection))
        stateHolder.value = when (kind) {
            DetectionKind.PHOTO -> current.copy(photo = null, photoOutputPath = null, revision = current.revision + 1L)
            DetectionKind.SIGNATURE -> current.copy(signature = null, signatureOutputPath = null, revision = current.revision + 1L)
        }
    }

    override suspend fun accept(kind: DetectionKind) {
        val current = stateHolder.value
        val detection = when (kind) {
            DetectionKind.PHOTO -> current.photo
            DetectionKind.SIGNATURE -> current.signature
        } ?: return

        learning.recordAcceptedCorrection(
            sample = detection.toSample(
                sampleKey = sampleKey(current.input, kind, detection),
                corrected = detection.estimated,
                accepted = true,
                now = System.currentTimeMillis(),
            ),
            context = contextFor(detection),
        )
    }

    override suspend fun applyEditedResult(
        kind: DetectionKind,
        editedFile: File,
    ) {
        require(editedFile.isFile) { "Edited file does not exist." }
        val directory = File(appContext.cacheDir, "formsnap_pipeline").apply { mkdirs() }
        val target = File(
            directory,
            if (kind == DetectionKind.PHOTO) "photo_edited.jpg" else "signature_edited.jpg",
        )
        editedFile.inputStream().use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        val current = stateHolder.value
        stateHolder.value = when (kind) {
            DetectionKind.PHOTO -> current.copy(photoOutputPath = target.absolutePath, revision = current.revision + 1L)
            DetectionKind.SIGNATURE -> current.copy(signatureOutputPath = target.absolutePath, revision = current.revision + 1L)
        }
    }

    private fun DetectionBounds.toSample(
        sampleKey: String,
        corrected: RectF,
        accepted: Boolean,
        now: Long,
    ): DetectionSample = DetectionSample(
        sampleKey = sampleKey,
        kind = kind.name,
        sourceWidth = sourceWidth,
        sourceHeight = sourceHeight,
        estimatedX = estimated.left.toDouble(),
        estimatedY = estimated.top.toDouble(),
        estimatedWidth = estimated.width().toDouble(),
        estimatedHeight = estimated.height().toDouble(),
        correctedX = corrected.left.toDouble(),
        correctedY = corrected.top.toDouble(),
        correctedWidth = corrected.width().toDouble(),
        correctedHeight = corrected.height().toDouble(),
        thresholdBias = learning.recommendedBias(kind.name),
        blockSize = 31,
        accepted = accepted,
        createdAt = now,
    )

    private fun contextFor(detection: DetectionBounds): LearningContext =
        LearningContext(
            brightnessBucket = 0,
            edgeDensityBucket = 0,
            aspectBucket = ((detection.estimated.width() / detection.estimated.height()) * 10f)
                .roundToInt()
                .coerceIn(0, 100),
        )

    private fun sampleKey(
        input: File?,
        kind: DetectionKind,
        detection: DetectionBounds,
    ): String {
        val raw = listOf(
            input?.absolutePath.orEmpty(),
            kind.name,
            detection.sourceWidth,
            detection.sourceHeight,
            detection.estimated.left,
            detection.estimated.top,
            detection.estimated.width(),
            detection.estimated.height(),
        ).joinToString("|")
        return MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    override fun close() {
        File(appContext.cacheDir, "formsnap_pipeline").deleteRecursively()
        stateHolder.value = PipelineState()
    }
}
