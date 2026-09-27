package org.techwithkaushik.formSnap.pipeline

import org.opencv.core.Mat

enum class CorrectionAction {
    ACCEPT,
    ADJUST,
    REJECT,
}

data class CorrectionInput(
    val photo: DetectionCandidate?,
    val signature: DetectionCandidate?,
    val action: CorrectionAction,
)

data class PipelineOutput(
    val detection: DetectionResult,
    val photo: Mat?,
    val signature: Mat?,
    val photoQuality: QualityResult?,
    val signatureQuality: QualityResult?,
) : AutoCloseable {
    override fun close() {
        photo?.release()
        signature?.release()
    }
}

object UniversalPipeline {
    fun process(source: Mat, dpi: Int = 300): PipelineOutput {
        require(!source.empty()) { "Source image is empty" }

        val detection = UniversalDetectionEngine.detect(source)
        val photoCandidate = detection.photo
        val signatureCandidate = detection.signature

        val photoOutput = photoCandidate?.let {
            OutputNormalizer.normalize(source, it, DetectionKind.PHOTO, dpi)
        }
        val signatureOutput = signatureCandidate?.let {
            OutputNormalizer.normalize(source, it, DetectionKind.SIGNATURE, dpi)
        }

        val photoQuality = photoOutput?.let {
            ImageQualityGate.evaluate(it.image, DetectionKind.PHOTO)
        }
        val signatureQuality = signatureOutput?.let {
            ImageQualityGate.evaluate(it.image, DetectionKind.SIGNATURE)
        }

        return PipelineOutput(
            detection = detection,
            photo = photoOutput?.image,
            signature = signatureOutput?.image,
            photoQuality = photoQuality,
            signatureQuality = signatureQuality,
        )
    }
}
