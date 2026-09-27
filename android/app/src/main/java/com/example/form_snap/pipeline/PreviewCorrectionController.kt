package org.techwithkaushik.formSnap.pipeline

import android.graphics.RectF

class PreviewCorrectionController(
    private val detectionCandidate: DetectionCandidate? = null,
    initialState: PreviewCorrectionState,
) {
    var state: PreviewCorrectionState = initialState
        private set

    fun setBounds(bounds: RectF) {
        state = state.withBounds(
            clamp(bounds),
        )
    }

    fun setAppearance(adjustments: AppearanceAdjustments) {
        state = state.withAppearance(adjustments)
    }

    fun accept() {
        state = state.accept()
    }

    fun reject() {
        state = state.reject()
    }

    fun reset() {
        val candidate = detectionCandidate ?: return
        state = PreviewCorrectionStateFactory.fromCandidate(
            candidate,
            state.sourceWidth,
            state.sourceHeight,
        )
    }

    fun feedback(): CorrectionFeedback {
        val candidate = detectionCandidate ?: DetectionCandidate(
            kind = state.kind,
            bounds = state.automaticBounds,
            confidence = 1f,
            source = "automatic-preview",
        )
        return state.correction(candidate)
    }

    private fun clamp(bounds: RectF): RectF {
        val maxRight = state.sourceWidth.toFloat().coerceAtLeast(1f)
        val maxBottom = state.sourceHeight.toFloat().coerceAtLeast(1f)
        val left = bounds.left.coerceIn(0f, maxRight - 1f)
        val top = bounds.top.coerceIn(0f, maxBottom - 1f)
        val right = bounds.right.coerceIn(left + 1f, maxRight)
        val bottom = bounds.bottom.coerceIn(top + 1f, maxBottom)
        return RectF(left, top, right, bottom)
    }
}
