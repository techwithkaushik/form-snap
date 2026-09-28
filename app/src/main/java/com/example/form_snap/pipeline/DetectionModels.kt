package org.techwithkaushik.formSnap.pipeline

import android.graphics.RectF

enum class DetectionKind {
    PHOTO,
    SIGNATURE,
}

data class DetectionCandidate(
    val kind: DetectionKind,
    val bounds: RectF,
    val confidence: Float,
    val source: String,
    val hasPrintedFrame: Boolean = false,
)

data class DetectionResult(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val photo: DetectionCandidate? = null,
    val signature: DetectionCandidate? = null,
    val detectorVersion: String = "baseline-1",
)
