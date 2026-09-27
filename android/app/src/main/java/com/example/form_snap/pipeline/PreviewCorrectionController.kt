package org.techwithkaushik.formSnap.pipeline

import android.graphics.RectF

class PreviewCorrectionController(
    private val automatic: DetectionCandidate,
    private val sourceWidth: Int,
    private val sourceHeight: Int,
) {
    var state: PreviewCorrectionState =
        PreviewCorrectionStateFactory.fromCandidate(
            automatic,
            sourceWidth,
            sourceHeight,
        )
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
        state = PreviewCorrectionStateFactory.fromCandidate(
            automatic,
            sourceWidth,
            sourceHeight,
        )
    }

    fun feedback(): CorrectionFeedback = state.correction()

    private fun clamp(bounds: RectF): RectF {
        val left = bounds.left.coerceIn(0f, sourceWidth.toFloat() - 1f)
        val top = bounds.top.coerceIn(0f, sourceHeight.toFloat() - 1f)
        val right = bounds.right.coerceIn(left + 1f, sourceWidth.toFloat())
        val bottom = bounds.bottom.coerceIn(top + 1f, sourceHeight.toFloat())
        return RectF(left, top, right, bottom)
    }
}
