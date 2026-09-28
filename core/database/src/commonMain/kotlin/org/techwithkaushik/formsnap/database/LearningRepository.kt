package org.techwithkaushik.formsnap.database

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class LearningRepository(
    private val database: LearningDatabase,
) {
    private val learningQueries = database.learningDatabaseQueries

    fun profile(kind: String): ThresholdProfile? =
        learningQueries.meanDrift(kind)
            .executeAsOneOrNull()
            ?.let { row ->
                ThresholdProfile(
                    kind = row.kind,
                    bias = learnedBias(
                        row.meanBias,
                        row.sampleCount,
                    ),
                    sampleCount = row.sampleCount,
                    meanDx = row.meanDx ?: 0.0,
                    meanDy = row.meanDy ?: 0.0,
                    meanDw = row.meanDw ?: 0.0,
                    meanDh = row.meanDh ?: 0.0,
                )
            }

    fun record(
        sample: DetectionSample,
        context: LearningContext,
    ) {
        val bounded = sanitize(sample)
        learningQueries.insertLog(
            sample_key = bounded.sampleKey,
            kind = bounded.kind,
            source_width = bounded.sourceWidth.toLong(),
            source_height = bounded.sourceHeight.toLong(),
            estimated_x = bounded.estimatedX,
            estimated_y = bounded.estimatedY,
            estimated_width = bounded.estimatedWidth,
            estimated_height = bounded.estimatedHeight,
            corrected_x = bounded.correctedX,
            corrected_y = bounded.correctedY,
            corrected_width = bounded.correctedWidth,
            corrected_height = bounded.correctedHeight,
            delta_x = bounded.correctedX - bounded.estimatedX,
            delta_y = bounded.correctedY - bounded.estimatedY,
            delta_width = bounded.correctedWidth - bounded.estimatedWidth,
            delta_height = bounded.correctedHeight - bounded.estimatedHeight,
            threshold_bias = bounded.thresholdBias,
            block_size = bounded.blockSize.toLong(),
            accepted = if (bounded.accepted) 1L else 0L,
            brightness_bucket = bounded.brightnessBucket.toLong(),
            edge_density_bucket = bounded.edgeDensityBucket.toLong(),
            aspect_bucket = bounded.aspectBucket.toLong(),
            created_at = bounded.createdAt,
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
        learningQueries
            .selectByContext(
                kind = kind,
                context_key = context.key(),
            )
            .executeAsList()
            .map { row ->
                PolicyStat(
                    kind = row.kind,
                    contextKey = row.contextKey,
                    actionIndex = row.actionIndex.toInt(),
                    visits = row.visits,
                    totalReward = row.totalReward,
                    lastReward = row.lastReward,
                    updatedAt = row.updatedAt,
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
        val existing = learningQueries
            .selectByAction(
                kind = kind,
                context_key = context.key(),
                action_index = actionIndex.toLong(),
            )
            .executeAsOneOrNull()

        val visits = (existing?.visits ?: 0L) + 1L
        val totalReward = (existing?.totalReward ?: 0.0) + reward

        learningQueries.upsertParameter(
            kind = kind,
            context_key = context.key(),
            action_index = actionIndex.toLong(),
            visits = visits,
            total_reward = totalReward,
            last_reward = reward,
            updated_at = expectEpochMillis(),
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
