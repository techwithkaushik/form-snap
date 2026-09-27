package org.techwithkaushik.formSnap.pipeline

import android.graphics.RectF

enum class AdjustmentKind {
    MOVE,
    RESIZE,
    REPLACE,
}

data class Adjustment(
    val kind: AdjustmentKind,
    val bounds: RectF? = null,
)

data class CorrectionDecision(
    val action: CorrectionAction,
    val photoBounds: RectF? = null,
    val signatureBounds: RectF? = null,
)

object CorrectionEngine {
    fun apply(
        detection: DetectionResult,
        decision: CorrectionDecision,
    ): DetectionResult {
        val photo = when (decision.action) {
            CorrectionAction.REJECT -> null
            else -> detection.photo?.let {
                decision.photoBounds?.let { bounds ->
                    it.copy(bounds = clamp(bounds, detection.sourceWidth, detection.sourceHeight))
                } ?: it
            }
        }

        val signature = when (decision.action) {
            CorrectionAction.REJECT -> null
            else -> detection.signature?.let {
                decision.signatureBounds?.let { bounds ->
                    it.copy(bounds = clamp(bounds, detection.sourceWidth, detection.sourceHeight))
                } ?: it
            }
        }

        return detection.copy(photo = photo, signature = signature)
    }

    fun adjustCandidate(
        candidate: DetectionCandidate,
        bounds: RectF,
        sourceWidth: Int,
        sourceHeight: Int,
    ): DetectionCandidate = candidate.copy(
        bounds = clamp(bounds, sourceWidth, sourceHeight),
        confidence = minOf(candidate.confidence, 1.0f),
        source = "user-adjusted",
    )

    private fun clamp(bounds: RectF, width: Int, height: Int): RectF {
        val left = bounds.left.coerceIn(0f, width.toFloat() - 1f)
        val top = bounds.top.coerceIn(0f, height.toFloat() - 1f)
        val right = bounds.right.coerceIn(left + 1f, width.toFloat())
        val bottom = bounds.bottom.coerceIn(top + 1f, height.toFloat())
        return RectF(left, top, right, bottom)
    }
}
