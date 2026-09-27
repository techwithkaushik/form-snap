package org.techwithkaushik.formSnap.pipeline

import org.opencv.core.Mat

object PreviewProcessor {
    fun render(source: Mat, state: PreviewCorrectionState): Mat {
        require(!source.empty()) { "Source image is empty" }
        val candidate = DetectionCandidate(
            kind = state.kind,
            bounds = state.currentBounds,
            confidence = 1f,
            source = "preview-correction",
        )
        val normalized = OutputNormalizer.normalize(source, candidate, state.kind)
        return try {
            AppearanceProcessor.apply(normalized.image, state.appearance, state.kind)
        } finally {
            normalized.image.release()
        }
    }
}