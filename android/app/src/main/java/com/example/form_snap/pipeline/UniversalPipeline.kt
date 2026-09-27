package org.techwithkaushik.formSnap.pipeline

import org.opencv.core.Mat

object UniversalPipeline {
    fun process(
        source: Mat,
        kind: DetectionKind,
        dpi: Int = 300,
        context: android.content.Context? = null,
    ): PipelineStageOutput {
        require(!source.empty()) { "Source image is empty" }

        val detection = UniversalDetectionEngine.detect(source)
        val candidate = when (kind) {
            DetectionKind.PHOTO -> detection.photo
            DetectionKind.SIGNATURE -> detection.signature
        }

        if (candidate == null) {
            return PipelineStageOutput(
                detection = detection,
                kind = kind,
                image = null,
                quality = null,
                learnedBlend = 0f,
                learned = null,
            )
        }

        val learned = context?.let { LearningStore.best(it, kind) }
        val application = LearnedProfileApplier.apply(candidate, learned)
        val adjustedCandidate = candidate.copy(bounds = application.bounds)

        val output = OutputNormalizer.normalize(source, adjustedCandidate, kind, dpi)
        val appearanceApplied = AppearanceProcessor.apply(
            output.image,
            application.appearance,
            kind,
        )
        output.image.release()

        val quality = ImageQualityGate.evaluate(appearanceApplied, kind)

        return PipelineStageOutput(
            detection = detection.copy(
                photo = if (kind == DetectionKind.PHOTO) adjustedCandidate else detection.photo,
                signature = if (kind == DetectionKind.SIGNATURE) adjustedCandidate else detection.signature,
            ),
            kind = kind,
            image = appearanceApplied,
            quality = quality,
            learnedBlend = application.blend,
            learned = learned,
        )
    }
}

data class PipelineStageOutput(
    val detection: DetectionResult,
    val kind: DetectionKind,
    val image: Mat?,
    val quality: QualityResult?,
    val learnedBlend: Float,
    val learned: LearnedCorrection?,
) : AutoCloseable {
    override fun close() {
        image?.release()
    }
}
