package org.techwithkaushik.formSnap.pipeline

import android.graphics.RectF

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
        copy(currentBounds = bounds, dirty = true)

    fun withAppearance(adjustments: AppearanceAdjustments): PreviewCorrectionState =
        copy(appearance = adjustments, dirty = true)

    fun accept(): PreviewCorrectionState =
        copy(action = CorrectionAction.ACCEPT)

    fun reject(): PreviewCorrectionState =
        copy(action = CorrectionAction.REJECT)

    fun correction(automatic: DetectionCandidate): CorrectionFeedback = CorrectionFeedback(
        kind = kind,
        automatic = automatic,
        correctedBounds = currentBounds,
        appearance = appearance,
        accepted = action == CorrectionAction.ACCEPT,
    )
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
