package org.techwithkaushik.formSnap.pipeline

import org.opencv.core.Mat

/**
 * Deterministic offline extraction pipeline.
 *
 * Crop geometry comes from OpenCV candidate detection only. The legacy profile
 * learner is deliberately not allowed to move crop bounds or tune pixels:
 * appearance/crop changes must be measurable and reproducible before any future
 * trained model is allowed into this path.
 */
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

        val output = OutputNormalizer.normalize(source, candidate, kind, dpi)
        val quality = try {
            ImageQualityGate.evaluate(output.image, kind)
        } catch (failure: Throwable) {
            output.image.release()
            throw failure
        }

        return PipelineStageOutput(
            detection = detection,
            kind = kind,
            image = output.image,
            quality = quality,
            learnedBlend = 0f,
            learned = null,
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
