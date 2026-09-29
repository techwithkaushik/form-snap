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

        val candidateAspectRatio =
            candidate.bounds.height() / candidate.bounds.width().coerceAtLeast(1f)
        val features = ImageConditionFeatures.measure(source, candidate.bounds)
        val learned = context?.let {
            LearningStore.best(
                context = it,
                kind = kind,
                conditionBrightness = features?.brightness,
                conditionContrast = features?.contrast,
                conditionSaturation = features?.saturation,
                conditionEdgeDensity = features?.edgeDensity,
                aspectRatio = features?.aspectRatio ?: candidateAspectRatio,
            )
        }
        // Geometry corrections require a normalized layout match. Lighting,
        // contrast or crop aspect alone may match an unrelated form, so the
        // condition profile contributes appearance tuning but never moves bounds.
        val topology = LayoutTopologyMatcher.signature(
            sourceWidth = source.cols(),
            sourceHeight = source.rows(),
            photoBounds = detection.photo?.bounds,
            signatureBounds = detection.signature?.bounds,
        )
        val topologyProfile = if (context != null && topology != null) {
            LayoutTopologyStore.best(context, topology, kind)
        } else null
        val topologyAdjustedBounds = if (topologyProfile != null && topology != null) {
            LayoutTopologyMatcher.apply(
                bounds = candidate.bounds,
                profile = topologyProfile,
                actualSignature = topology,
                sourceWidth = source.cols(),
                sourceHeight = source.rows(),
            )
        } else null
        val application = LearnedProfileApplier.apply(candidate, learned, features)
        val adjustedCandidate = candidate.copy(
            bounds = topologyAdjustedBounds ?: candidate.bounds,
        )
        val topologyBlend = if (topologyProfile != null && topology != null && topologyAdjustedBounds != null) {
            LayoutTopologyMatcher.similarity(topologyProfile.signature, topology, kind)
        } else 0f

        val output = OutputNormalizer.normalize(source, adjustedCandidate, kind, dpi)
        val appearanceApplied = try {
            AppearanceProcessor.apply(
                output.image,
                application.appearance,
                kind,
            )
        } finally {
            output.image.release()
        }

        val quality = try {
            ImageQualityGate.evaluate(appearanceApplied, kind)
        } catch (t: Throwable) {
            appearanceApplied.release()
            throw t
        }

        return PipelineStageOutput(
            detection = detection.copy(
                photo = if (kind == DetectionKind.PHOTO) adjustedCandidate else detection.photo,
                signature = if (kind == DetectionKind.SIGNATURE) adjustedCandidate else detection.signature,
            ),
            kind = kind,
            image = appearanceApplied,
            quality = quality,
            learnedBlend = topologyBlend,
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
