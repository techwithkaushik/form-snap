package org.techwithkaushik.formSnap.pipeline

import org.opencv.core.Mat

/** Owns the normalized output Mat until close() is called. */
data class PipelineStageOutput(
    val detection: DetectionResult,
    val kind: DetectionKind,
    val image: Mat?,
    val quality: QualityResult?,
) : AutoCloseable {
    override fun close() {
        image?.release()
    }
}
