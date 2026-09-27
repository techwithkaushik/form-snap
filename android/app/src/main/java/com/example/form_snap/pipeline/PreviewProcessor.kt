package org.techwithkaushik.formSnap.pipeline

import org.opencv.core.Mat

object PreviewProcessor {
    fun render(
        source: Mat,
        state: PreviewCorrectionState,
        dpi: Int = 300,
        widthMm: Double? = null,
        heightMm: Double? = null,
    ): Mat {
        require(!source.empty()) { "Source image is empty" }
        val candidate = DetectionCandidate(
            kind = state.kind,
            bounds = state.currentBounds,
            confidence = 1f,
            source = "preview-correction",
        )
        val normalized = OutputNormalizer.normalize(
            source = source,
            candidate = candidate,
            kind = state.kind,
            dpi = dpi,
            widthMm = widthMm,
            heightMm = heightMm,
        )
        return try {
            AppearanceProcessor.apply(normalized.image, state.appearance, state.kind)
        } finally {
            normalized.image.release()
        }
    }
}