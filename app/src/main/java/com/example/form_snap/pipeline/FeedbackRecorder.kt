package org.techwithkaushik.formSnap.pipeline

data class CorrectionFeedback(
    val kind: DetectionKind,
    val automatic: DetectionCandidate,
    val correctedBounds: android.graphics.RectF,
    val appearance: AppearanceAdjustments,
    val accepted: Boolean = true,
)

object FeedbackRecorder {
    fun record(context: android.content.Context, feedback: CorrectionFeedback) {
        if (!feedback.accepted) return
        val learned = CorrectionLearning.fromCorrection(
            automatic = feedback.automatic,
            correctedBounds = feedback.correctedBounds,
            appearance = feedback.appearance,
        )
        LearningStore.record(context, learned)
    }
}
