package org.techwithkaushik.formsnap.core.database

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class LearningRepository(
    private val database: FormSnapDatabase,
) {
    private val feedbackQueries = database.detectionFeedbackQueries
    private val policyQueries = database.policyStatQueries

    fun observeProfile(kind: String): Flow<ThresholdProfile?> =
        feedbackQueries.observeMeanDrift(kind)
            .asFlow()
            .map { query ->
                query.executeAsOneOrNull()?.let { row ->
                    ThresholdProfile(
                        kind = row.kind,
                        bias = safeBias(row.mean_threshold_bias ?: DEFAULT_BIAS),
                        sampleCount = row.sample_count ?: 0L,
                        meanDx = row.mean_delta_x ?: 0.0,
                        meanDy = row.mean_delta_y ?: 0.0,
                        meanDw = row.mean_delta_width ?: 0.0,
                        meanDh = row.mean_delta_height ?: 0.0,
                    )
                }
            }

    fun profile(kind: String): ThresholdProfile? =
        feedbackQueries.meanDrift(kind)
            .executeAsOneOrNull()
            ?.let { row ->
                ThresholdProfile(
                    kind = row.kind,
                    bias = safeBias(row.mean_delta_bias ?: DEFAULT_BIAS),
                    sampleCount = row.sample_count ?: 0L,
                    meanDx = row.mean_delta_x ?: 0.0,
                    meanDy = row.mean_delta_y ?: 0.0,
                    meanDw = row.mean_delta_width ?: 0.0,
                    meanDh = row.mean_delta_height ?: 0.0,
                )
            }

    fun recordAcceptedCorrection(
        sample: DetectionSample,
        context: LearningContext,
    ) {
        val bounded = sanitize(sample)
        feedbackQueries.insertFeedback(
            sampleKey = bounded.sampleKey,
            kind = bounded.kind,
            sourceWidth = bounded.sourceWidth.toLong(),
            sourceHeight = bounded.sourceHeight.toLong(),
            estimatedX = bounded.estimatedX,
            estimatedY = bounded.estimatedY,
            estimatedWidth = bounded.estimatedWidth,
            estimatedHeight = bounded.estimatedHeight,
            correctedX = bounded.correctedX,
            correctedY = bounded.correctedY,
            correctedWidth = bounded.correctedWidth,
            correctedHeight = bounded.correctedHeight,
            thresholdBias = bounded.thresholdBias,
            blockSize = bounded.blockSize.toLong(),
            accepted = 1L,
            brightnessBucket = context.brightnessBucket.toLong(),
            edgeDensityBucket = context.edgeDensityBucket.toLong(),
            aspectBucket = context.aspectBucket.toLong(),
            createdAt = bounded.createdAt,
        )
        updatePolicy(
            kind = bounded.kind,
            context = context,
            actionIndex = ACCEPT_ACTION,
            reward = rewardFor(bounded),
        )
    }

    fun recordRejection(
        sample: DetectionSample,
        context: LearningContext,
    ) {
        val bounded = sanitize(sample)
        feedbackQueries.insertFeedback(
            sampleKey = bounded.sampleKey,
            kind = bounded.kind,
            sourceWidth = bounded.sourceWidth.toLong(),
            sourceHeight = bounded.sourceHeight.toLong(),
            estimatedX = bounded.estimatedX,
            estimatedY = bounded.estimatedY,
            estimatedWidth = bounded.estimatedWidth,
            estimatedHeight = bounded.estimatedHeight,
            correctedX = bounded.estimatedX,
            correctedY = bounded.estimatedY,
            correctedWidth = bounded.estimatedWidth,
            correctedHeight = bounded.estimatedHeight,
            thresholdBias = bounded.thresholdBias,
            blockSize = bounded.blockSize.toLong(),
            accepted = 0L,
            brightnessBucket = context.brightnessBucket.toLong(),
            edgeDensityBucket = context.edgeDensityBucket.toLong(),
            aspectBucket = context.aspectBucket.toLong(),
            createdAt = bounded.createdAt,
        )
        updatePolicy(
            kind = bounded.kind,
            context = context,
            actionIndex = REJECT_ACTION,
            reward = -1.0,
        )
    }

    fun policy(
        kind: String,
        context: LearningContext,
    ): List<PolicyStat> =
        policyQueries.selectByContext(kind, context.key())
            .executeAsList()
            .map { row ->
                PolicyStat(
                    kind = row.kind,
                    contextKey = row.context_key,
                    actionIndex = row.action_index.toInt(),
                    visits = row.visits,
                    totalReward = row.total_reward,
                    lastReward = row.last_reward,
                    updatedAt = row.updated_at,
                )
            }

    fun recommendedAction(
        kind: String,
        context: LearningContext,
    ): Int? =
        policy(kind, context)
            .filter { it.visits > 0L }
            .maxByOrNull { it.totalReward / it.visits.toDouble() }
            ?.actionIndex

    fun recommendedBias(kind: String): Double =
        (profile(kind)?.bias ?: DEFAULT_BIAS)
            .coerceIn(MIN_BIAS, MAX_BIAS)

    private fun updatePolicy(
        kind: String,
        context: LearningContext,
        actionIndex: Int,
        reward: Double,
    ) {
        val key = context.key()
        val existing = policyQueries.selectByAction(
            kind = kind,
            contextKey = key,
            actionIndex = actionIndex.toLong(),
        ).executeAsOneOrNull()

        val visits = (existing?.visits ?: 0L) + 1L
        val totalReward = (existing?.total_reward ?: 0.0) + reward

        policyQueries.upsertPolicy(
            kind = kind,
            contextKey = key,
            actionIndex = actionIndex.toLong(),
            visits = visits,
            totalReward = totalReward,
            lastReward = reward,
            updatedAt = nowEpochMillis(),
        )
    }

    private fun sanitize(sample: DetectionSample): DetectionSample {
        require(sample.sampleKey.isNotBlank())
        require(sample.kind.isNotBlank())
        require(sample.sourceWidth > 0)
        require(sample.sourceHeight > 0)
        require(sample.estimatedWidth > 0.0)
        require(sample.estimatedHeight > 0.0)
        require(sample.estimatedX >= 0.0)
        require(sample.estimatedY >= 0.0)

        val dx = safeDelta(sample.correctedX - sample.estimatedX)
        val dy = safeDelta(sample.correctedY - sample.estimatedY)
        val dw = safeDelta(sample.correctedWidth - sample.estimatedWidth)
        val dh = safeDelta(sample.correctedHeight - sample.estimatedHeight)

        val correctedX = (sample.estimatedX + dx)
            .coerceIn(0.0, max(0.0, sample.sourceWidth.toDouble() - 1.0))
        val correctedY = (sample.estimatedY + dy)
            .coerceIn(0.0, max(0.0, sample.sourceHeight.toDouble() - 1.0))
        val correctedWidth = min(
            max(1.0, sample.estimatedWidth + dw),
            sample.sourceWidth.toDouble() - correctedX,
        ).coerceAtLeast(1.0)
        val correctedHeight = min(
            max(1.0, sample.estimatedHeight + dh),
            sample.sourceHeight.toDouble() - correctedY,
        ).coerceAtLeast(1.0)

        return sample.copy(
            correctedX = correctedX,
            correctedY = correctedY,
            correctedWidth = correctedWidth,
            correctedHeight = correctedHeight,
            thresholdBias = safeBias(sample.thresholdBias),
            blockSize = normalizeBlock(sample.blockSize),
        )
    }

    private fun safeDelta(delta: Double): Double =
        if (delta.isFinite()) delta.coerceIn(-MAX_DRIFT_PER_STEP, MAX_DRIFT_PER_STEP)
        else 0.0

    private fun safeBias(value: Double): Double =
        if (value.isFinite()) value.coerceIn(MIN_BIAS, MAX_BIAS) else DEFAULT_BIAS

    private fun normalizeBlock(value: Int): Int {
        val bounded = value.coerceIn(3, 99)
        return if (bounded % 2 == 0) bounded + 1 else bounded
    }

    private fun rewardFor(sample: DetectionSample): Double {
        val dx = abs(sample.correctedX - sample.estimatedX)
        val dy = abs(sample.correctedY - sample.estimatedY)
        val dw = abs(sample.correctedWidth - sample.estimatedWidth)
        val dh = abs(sample.correctedHeight - sample.estimatedHeight)
        val magnitude = (dx + dy + dw + dh) / (4.0 * MAX_DRIFT_PER_STEP)
        return (1.0 - magnitude).coerceIn(-1.0, 1.0)
    }

    private fun nowEpochMillis(): Long = expectEpochMillis()

    companion object {
        const val MIN_BIAS = 1.0
        const val MAX_BIAS = 15.0
        const val DEFAULT_BIAS = 8.0
        const val MAX_DRIFT_PER_STEP = 3.0
        const val REJECT_ACTION = 0
        const val ACCEPT_ACTION = 1
    }
}

expect fun expectEpochMillis(): Long
