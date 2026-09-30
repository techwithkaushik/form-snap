package org.techwithkaushik.formSnap.pipeline

import android.graphics.RectF

data class AppearanceAdjustments(
    val brightness: Float = 0f,
    val contrast: Float = 1f,
    val saturation: Float = 1f,
    val sharpness: Float = 0f,
    val denoise: Float = 0f,
    val inkThreshold: Int = 150,
    val backgroundCleanup: Float = 0f,
)

data class PreviewCorrectionState(
    val kind: DetectionKind,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val automaticBounds: RectF,
    val currentBounds: RectF,
    val appearance: AppearanceAdjustments = AppearanceAdjustments(),
    val action: CorrectionAction = CorrectionAction.ADJUST,
    val dirty: Boolean = false,
) {
    fun withBounds(bounds: RectF): PreviewCorrectionState =
        copy(currentBounds = bounds, action = CorrectionAction.ADJUST, dirty = true)

    fun withAppearance(adjustments: AppearanceAdjustments): PreviewCorrectionState =
        copy(appearance = adjustments, action = CorrectionAction.ADJUST, dirty = true)

    fun accept(): PreviewCorrectionState =
        copy(action = CorrectionAction.ACCEPT)

    fun reject(): PreviewCorrectionState =
        copy(action = CorrectionAction.REJECT)

}

object PreviewCorrectionStateFactory {
    fun fromCandidate(
        candidate: DetectionCandidate,
        sourceWidth: Int,
        sourceHeight: Int,
    ): PreviewCorrectionState = PreviewCorrectionState(
        kind = candidate.kind,
        sourceWidth = sourceWidth,
        sourceHeight = sourceHeight,
        automaticBounds = RectF(candidate.bounds),
        currentBounds = RectF(candidate.bounds),
    )
}