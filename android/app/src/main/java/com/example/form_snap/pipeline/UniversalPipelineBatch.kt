package org.techwithkaushik.formSnap.pipeline

import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgcodecs.Imgcodecs
import android.graphics.BitmapFactory
import java.io.File

data class UniversalPipelineOutput(
    val detection: DetectionResult,
    val photo: PipelineStageOutput?,
    val signature: PipelineStageOutput?,
) : AutoCloseable {
    override fun close() {
        photo?.close()
        signature?.close()
    }
}

object UniversalPipelineBatch {
    fun process(
        source: Mat,
        dpi: Int = 300,
        context: android.content.Context? = null,
    ): UniversalPipelineOutput {
        require(!source.empty()) { "Source image is empty" }
        val detection = UniversalDetectionEngine.detect(source)
        val photo = if (detection.photo != null) {
            processCandidate(source, detection, DetectionKind.PHOTO, dpi, context)
        } else null
        val signature = if (detection.signature != null) {
            processCandidate(source, detection, DetectionKind.SIGNATURE, dpi, context)
        } else null
        return UniversalPipelineOutput(detection, photo, signature)
    }

    fun processFile(
        input: File,
        dpi: Int = 300,
        context: android.content.Context? = null,
    ): UniversalPipelineOutput {
        require(input.exists()) { "Input file does not exist" }
        val source = Imgcodecs.imread(input.absolutePath)
        require(!source.empty()) { "Unable to decode input image" }
        return try {
            process(source, dpi, context)
        } finally {
            source.release()
        }
    }

    private fun processCandidate(
        source: Mat,
        detection: DetectionResult,
        kind: DetectionKind,
        dpi: Int,
        context: android.content.Context?,
    ): PipelineStageOutput {
        val candidate = if (kind == DetectionKind.PHOTO) detection.photo!! else detection.signature!!
        val learned = context?.let { LearningStore.best(it, kind) }
        val application = LearnedProfileApplier.apply(candidate, learned)
        val adjusted = candidate.copy(bounds = application.bounds)
        val normalized = OutputNormalizer.normalize(source, adjusted, kind, dpi)
        val appearance = AppearanceProcessor.apply(normalized.image, application.appearance, kind)
        normalized.image.release()
        val quality = ImageQualityGate.evaluate(appearance, kind)
        return PipelineStageOutput(
            detection = detection.copy(
                photo = if (kind == DetectionKind.PHOTO) adjusted else detection.photo,
                signature = if (kind == DetectionKind.SIGNATURE) adjusted else detection.signature,
            ),
            kind = kind,
            image = appearance,
            quality = quality,
            learnedBlend = application.blend,
            learned = learned,
        )
    }
}