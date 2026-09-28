package org.techwithkaushik.formsnap.database

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class LearningRepository(
    private val database: LearningDatabase,
) {
    private val feedbackQueries = database.detectionFeedbackQueries

    fun observeProfile(kind: String): Flow<ThresholdProfile?> =
        feedbackQueries.observeMeanDrift(kind)
            .asFlow()
            .map { query ->
                query.executeAsOneOrNull()?.let { row ->
                    ThresholdProfile(
                        kind = row.kind,
                        bias = learnedBias(
                            row.mean_threshold_bias,
                            row.sample_count ?: 0L,
                        ),
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
                    bias = learnedBias(
                        row.mean_threshold_bias,
                        row.sample_count ?: 0L,
                    ),
                    sampleCount = row.sample_count ?: 0L,
                    meanDx = row.mean_delta_x ?: 0.0,
                    meanDy = row.mean_delta_y ?: 0.0,
                    meanDw = row.mean_delta_width ?: 0.0,
                    meanDh = row.mean_delta_height ?: 0.0,
                )
            }

    fun record(
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
            accepted = if (bounded.accepted) 1L else 0L,
            brightnessBucket = bounded.brightnessBucket.toLong(),
            edgeDensityBucket = bounded.edgeDensityBucket.toLong(),
            aspectBucket = bounded.aspectBucket.toLong(),
            createdAt = bounded.createdAt,
        )
        updatePolicy(
            kind = bounded.kind,
            context = context,
            actionIndex = if (bounded.accepted) ACCEPT_ACTION else REJECT_ACTION,
            reward = rewardFor(bounded),
        )
    }

    fun recordAcceptedCorrection(
        sample: DetectionSample,
        context: LearningContext,
    ) {
        record(
            sample = sample.copy(accepted = true),
            context = context,
        )
    }

    fun recordRejection(
        sample: DetectionSample,
        context: LearningContext,
    ) {
        record(
            sample = sample.copy(accepted = false),
            context = context,
        )
    }

    fun policy(
        kind: String,
        context: LearningContext,
    ): List<PolicyStat> =
        database.detectionFeedbackQueries
            .selectByContext(
                kind = kind,
                contextKey = context.key(),
            )
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
            .maxByOrNull {
                it.totalReward / it.visits.toDouble()
            }
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
        val existing = database.detectionFeedbackQueries
            .selectByAction(
                kind = kind,
                contextKey = context.key(),
                actionIndex = actionIndex.toLong(),
            )
            .executeAsOneOrNull()

        val visits = (existing?.visits ?: 0L) + 1L
        val totalReward = (existing?.total_reward ?: 0.0) + reward

        database.detectionFeedbackQueries.upsertPolicy(
            kind = kind,
            contextKey = context.key(),
            actionIndex = actionIndex.toLong(),
            visits = visits,
            totalReward = totalReward,
            lastReward = reward,
            updatedAt = expectEpochMillis(),
        )
    }

    private fun sanitize(sample: DetectionSample): DetectionSample {
        require(sample.sampleKey.isNotBlank()) {
            "Sample key must not be blank."
        }
        require(sample.kind.isNotBlank()) {
            "Detection kind must not be blank."
        }
        require(sample.sourceWidth > 0 && sample.sourceHeight > 0) {
            "Source dimensions must be positive."
        }

        val sourceWidth = sample.sourceWidth.toDouble()
        val sourceHeight = sample.sourceHeight.toDouble()

        val estimatedX = finiteOrZero(sample.estimatedX)
            .coerceIn(0.0, sourceWidth - 1.0)
        val estimatedY = finiteOrZero(sample.estimatedY)
            .coerceIn(0.0, sourceHeight - 1.0)
        val estimatedWidth = finitePositive(sample.estimatedWidth)
            .coerceAtMost(sourceWidth - estimatedX)
            .coerceAtLeast(1.0)
        val estimatedHeight = finitePositive(sample.estimatedHeight)
            .coerceAtMost(sourceHeight - estimatedY)
            .coerceAtLeast(1.0)

        val dx = safeDelta(sample.correctedX - estimatedX)
        val dy = safeDelta(sample.correctedY - estimatedY)
        val dw = safeDelta(sample.correctedWidth - estimatedWidth)
        val dh = safeDelta(sample.correctedHeight - estimatedHeight)

        val correctedX = (estimatedX + dx)
            .coerceIn(0.0, sourceWidth - 1.0)
        val correctedY = (estimatedY + dy)
            .coerceIn(0.0, sourceHeight - 1.0)
        val correctedWidth = min(
            max(1.0, estimatedWidth + dw),
            sourceWidth - correctedX,
        ).coerceAtLeast(1.0)
        val correctedHeight = min(
            max(1.0, estimatedHeight + dh),
            sourceHeight - correctedY,
        ).coerceAtLeast(1.0)

        return sample.copy(
            estimatedX = estimatedX,
            estimatedY = estimatedY,
            estimatedWidth = estimatedWidth,
            estimatedHeight = estimatedHeight,
            correctedX = correctedX,
            correctedY = correctedY,
            correctedWidth = correctedWidth,
            correctedHeight = correctedHeight,
            thresholdBias = safeBias(sample.thresholdBias),
            blockSize = normalizeBlock(sample.blockSize),
            brightnessBucket = sample.brightnessBucket.coerceIn(0, 31),
            edgeDensityBucket = sample.edgeDensityBucket.coerceIn(0, 31),
            aspectBucket = sample.aspectBucket.coerceIn(0, 31),
            createdAt = sample.createdAt.coerceAtLeast(0L),
        )
    }

    private fun safeDelta(delta: Double): Double =
        if (delta.isFinite()) {
            delta.coerceIn(
                -MAX_DRIFT_PER_STEP,
                MAX_DRIFT_PER_STEP,
            )
        } else {
            0.0
        }

    private fun safeBias(value: Double): Double =
        if (value.isFinite()) {
            value.coerceIn(MIN_BIAS, MAX_BIAS)
        } else {
            DEFAULT_BIAS
        }

    private fun normalizeBlock(value: Int): Int {
        val bounded = value.coerceIn(3, 99)
        return if (bounded % 2 == 0) bounded + 1 else bounded
    }

    private fun rewardFor(sample: DetectionSample): Double {
        val dx = abs(sample.correctedX - sample.estimatedX)
        val dy = abs(sample.correctedY - sample.estimatedY)
        val dw = abs(sample.correctedWidth - sample.estimatedWidth)
        val dh = abs(sample.correctedHeight - sample.estimatedHeight)
        val magnitude =
            (dx + dy + dw + dh) /
                (4.0 * MAX_DRIFT_PER_STEP)
        return (1.0 - magnitude).coerceIn(-1.0, 1.0)
    }

    private fun finiteOrZero(value: Double): Double =
        if (value.isFinite()) value else 0.0

    private fun finitePositive(value: Double): Double =
        if (value.isFinite() && value > 0.0) value else 1.0

    private fun learnedBias(
        meanStoredBias: Double?,
        samples: Long,
    ): Double {
        if (samples < 4L) return DEFAULT_BIAS
        return safeBias(meanStoredBias ?: DEFAULT_BIAS)
    }

    companion object {
        const val MIN_BIAS = 1.0
        const val MAX_BIAS = 15.0
        const val DEFAULT_BIAS = 8.0
        const val MAX_DRIFT_PER_STEP = 3.0
        const val REJECT_ACTION = 0
        const val ACCEPT_ACTION = 1
    }
}

data class PolicyStat(
    val kind: String,
    val contextKey: String,
    val actionIndex: Int,
    val visits: Long,
    val totalReward: Double,
    val lastReward: Double,
    val updatedAt: Long,
)

expect fun expectEpochMillis(): Long
