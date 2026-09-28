package org.techwithkaushik.formsnap.core.database

import android.content.Context
import app.cash.sqldelight.android.AndroidSqliteDriver

class FeedbackRepository(context: Context) {
    private val database = FormSnapDatabase(
        AndroidSqliteDriver(
            FormSnapDatabase.Schema,
            context,
            "formsnap-feedback.db"
        )
    )

    fun record(input: FeedbackInput) {
        database.detectionFeedbackQueries.insertFeedback(
            sampleKey = input.sampleKey,
            kind = input.kind,
            sourceWidth = input.sourceWidth.toLong(),
            sourceHeight = input.sourceHeight.toLong(),
            estimatedLeft = input.estimatedLeft,
            estimatedTop = input.estimatedTop,
            estimatedRight = input.estimatedRight,
            estimatedBottom = input.estimatedBottom,
            correctedLeft = input.correctedLeft,
            correctedTop = input.correctedTop,
            correctedRight = input.correctedRight,
            correctedBottom = input.correctedBottom,
            adaptiveBias = input.adaptiveBias,
            blockSize = input.blockSize.toLong(),
            localC = input.localC,
            accepted = if (input.accepted) 1L else 0L,
            createdAt = input.createdAt
        )
    }

    fun drift(kind: String): DriftProfile? =
        database.detectionFeedbackQueries.meanDriftByKind(kind)
            .executeAsOneOrNull()
            ?.let {
                DriftProfile(
                    kind = it.kind,
                    left = it.mean_delta_left ?: 0.0,
                    top = it.mean_delta_top ?: 0.0,
                    right = it.mean_delta_right ?: 0.0,
                    bottom = it.mean_delta_bottom ?: 0.0,
                    samples = it.sample_count ?: 0L
                )
            }
}
