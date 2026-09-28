package org.techwithkaushik.formSnap.pipeline

import android.graphics.RectF

class PreviewCorrectionController(
    private val detectionCandidate: DetectionCandidate,
    initialState: PreviewCorrectionState,
) {
    var state: PreviewCorrectionState = initialState
        private set

    fun setBounds(bounds: RectF) {
        state = state.withBounds(clamp(bounds))
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
        state = PreviewCorrectionStateFactory.fromCandidate(
            detectionCandidate,
            state.sourceWidth,
            state.sourceHeight,
        )
    }

    fun feedback(): CorrectionFeedback = state.correction(detectionCandidate)

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
