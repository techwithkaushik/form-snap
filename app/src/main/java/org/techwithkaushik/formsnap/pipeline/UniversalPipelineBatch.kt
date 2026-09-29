package org.techwithkaushik.formSnap.pipeline

import org.opencv.core.Mat
import org.opencv.imgcodecs.Imgcodecs
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
        var photo: PipelineStageOutput? = null
        var signature: PipelineStageOutput? = null
        try {
            photo = if (detection.photo != null) {
                processCandidate(source, detection, DetectionKind.PHOTO, dpi, context)
            } else null
            signature = if (detection.signature != null) {
                processCandidate(source, detection, DetectionKind.SIGNATURE, dpi, context)
            } else null
            return UniversalPipelineOutput(detection, photo, signature)
        } catch (t: Throwable) {
            photo?.close()
            signature?.close()
            throw t
        }
    }

    fun processFile(
        input: File,
        dpi: Int = 300,
        context: android.content.Context? = null,
    ): UniversalPipelineOutput {
        require(input.exists()) { "Input file does not exist" }
        val source = Imgcodecs.imread(input.absolutePath)
        return try {
            require(!source.empty()) { "Unable to decode input image" }
            process(source, dpi, context)
        } finally {
            // Release even when decoding fails, so failed imports cannot leak a native Mat.
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
        val aspectRatio = candidate.bounds.height() / candidate.bounds.width().coerceAtLeast(1f)
        val learned = context?.let {
            LearningStore.best(
                context = it,
                kind = kind,
                aspectRatio = aspectRatio,
            )
        }
        val application = LearnedProfileApplier.apply(candidate, learned)
        val adjusted = candidate.copy(bounds = application.bounds)
        val normalized = OutputNormalizer.normalize(source, adjusted, kind, dpi)
        val appearance = try {
            AppearanceProcessor.apply(normalized.image, application.appearance, kind)
        } finally {
            normalized.image.release()
        }
        val quality = try {
            ImageQualityGate.evaluate(appearance, kind)
        } catch (t: Throwable) {
            appearance.release()
            throw t
        }
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