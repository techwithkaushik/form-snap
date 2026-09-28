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
                query.executeAsOneOrNull()?.toThresholdProfile()
            }

    fun profile(kind: String): ThresholdProfile? =
        feedbackQueries.meanDrift(kind)
            .executeAsOneOrNull()
            ?.toThresholdProfile()

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
            reward = -1.0,
        )
    }

    fun policy(
        kind: String,
        context: LearningContext,
    ): List<PolicyStat> =
        policyQueries.selectByContext(kind, context.key())
            .executeAsList()
            .map {
                PolicyStat(
                    kind = it.kind,
                    contextKey = it.context_key,
                    actionIndex = it.action_index.toInt(),
                    visits = it.visits,
                    totalReward = it.total_reward,
                    lastReward = it.last_reward,
                    updatedAt = it.updated_at,
                )
            }

    fun recommendedAction(
        kind: String,
        context: LearningContext,
    ): Int? {
        val stats = policy(kind, context)
        return stats.maxByOrNull { stat ->
            averageReward(stat)
        }?.actionIndex
    }

    fun recommendedBias(kind: String): Double {
        val profile = profile(kind) ?: return DEFAULT_BIAS
        return profile.bias.coerceIn(MIN_BIAS, MAX_BIAS)
    }

    fun recommendedThreshold(
        kind: String,
        baseBias: Double = DEFAULT_BIAS,
    ): Double {
        val profile = profile(kind)
        val learned = profile?.bias ?: baseBias
        return learned.coerceIn(MIN_BIAS, MAX_BIAS)
    }

    private fun updatePolicy(
        kind: String,
        context: LearningContext,
        reward: Double,
    ) {
        val actionIndex = if (reward >= 0.0) ACCEPT_ACTION else REJECT_ACTION
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
        require(sample.sourceWidth > 0)
        require(sample.sourceHeight > 0)
        require(sample.estimatedWidth > 0.0)
        require(sample.estimatedHeight > 0.0)

        val dx = clampDrift(sample.correctedX - sample.estimatedX)
        val dy = clampDrift(sample.correctedY - sample.estimatedY)
        val dw = clampDrift(sample.correctedWidth - sample.estimatedWidth)
        val dh = clampDrift(sample.correctedHeight - sample.estimatedHeight)

        val estimatedRight = sample.estimatedX + sample.estimatedWidth
        val estimatedBottom = sample.estimatedY + sample.estimatedHeight

        val correctedX = (sample.estimatedX + dx)
            .coerceIn(0.0, max(0.0, sample.sourceWidth.toDouble() - 1.0))
        val correctedY = (sample.estimatedY + dy)
            .coerceIn(0.0, max(0.0, sample.sourceHeight.toDouble() - 1.0))

        val correctedWidth = max(
            1.0,
            min(
                sample.estimatedWidth + dw,
                sample.sourceWidth.toDouble() - correctedX,
            ),
        )
        val correctedHeight = max(
            1.0,
            min(
                sample.estimatedHeight + dh,
                sample.sourceHeight.toDouble() - correctedY,
            ),
        )

        return sample.copy(
            correctedX = correctedX,
            correctedY = correctedY,
            correctedWidth = correctedWidth,
            correctedHeight = correctedHeight,
            thresholdBias = sample.thresholdBias.coerceIn(MIN_BIAS, MAX_BIAS),
            blockSize = normalizedBlockSize(sample.blockSize),
        )
    }

    private fun clampDrift(value: Double): Double =
        value.coerceIn(-MAX_DRIFT_PER_STEP, MAX_DRIFT_PER_STEP)

    private fun normalizedBlockSize(value: Int): Int {
        val bounded = value.coerceIn(3, 99)
        return if (bounded % 2 == 0) bounded + 1 else bounded
    }

    private fun rewardFor(sample: DetectionSample): Double {
        val dx = abs(sample.correctedX - sample.estimatedX)
        val dy = abs(sample.correctedY - sample.estimatedY)
        val dw = abs(sample.correctedWidth - sample.estimatedWidth)
        val dh = abs(sample.correctedHeight - sample.estimatedHeight)
        val normalized = (dx + dy + dw + dh) / (4.0 * MAX_DRIFT_PER_STEP)
        return 1.0 - normalized.coerceIn(0.0, 2.0)
    }

    private fun averageReward(stat: PolicyStat): Double =
        if (stat.visits <= 0L) 0.0
        else stat.totalReward / stat.visits.toDouble()

    private fun nowEpochMillis(): Long = expectEpochMillis()

    private fun <T> T.asLong(): Long = when (this) {
        is Long -> this
        else -> error("Unsupported time value")
    }

    companion object {
        const val MIN_BIAS = 1.0
        const val MAX_BIAS = 15.0
        const val MAX_DRIFT_PER_STEP = 3.0
        const val REJECT_ACTION = 0
        const val ACCEPT_ACTION = 1
        const val DEFAULT_BIAS = 8.0
    }
}

private fun <T> T.toThresholdProfileUnsafe(): ThresholdProfile =
    error("Generated database row mapping is not available for this type")

private fun Long.toThresholdProfile(): ThresholdProfile =
    error("Invalid threshold profile source")

private fun nowEpochMillis(): Long =
    error("Platform time implementation missing")

expect fun expectEpochMillis(): Long
