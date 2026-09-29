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
        val values = listOf(
            adjustments.brightness,
            adjustments.contrast,
            adjustments.saturation,
            adjustments.sharpness,
            adjustments.denoise,
            adjustments.backgroundCleanup,
        )
        if (values.any { !it.isFinite() }) return
        state = state.withAppearance(AppearanceTuning.clamp(adjustments))
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
        // Ignore invalid drag/gesture coordinates instead of letting NaN or
        // Infinity enter preview state and later reach correction learning.
        if (!bounds.left.isFinite() || !bounds.top.isFinite() ||
            !bounds.right.isFinite() || !bounds.bottom.isFinite()
        ) {
            return RectF(state.currentBounds)
        }

        val maxRight = state.sourceWidth.toFloat().coerceAtLeast(1f)
        val maxBottom = state.sourceHeight.toFloat().coerceAtLeast(1f)
        val left = bounds.left.coerceIn(0f, maxRight - 1f)
        val top = bounds.top.coerceIn(0f, maxBottom - 1f)
        val right = bounds.right.coerceIn(left + 1f, maxRight)
        val bottom = bounds.bottom.coerceIn(top + 1f, maxBottom)
        return RectF(left, top, right, bottom)
    }
}
