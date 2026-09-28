package org.techwithkaushik.formsnap.core.database

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class LearningRepository(
    private val database: FormSnapDatabase,
) {
    private val queries = database.detectionFeedbackQueries
    private val policyQueries = database.policyStatQueries

    fun observeDrift(kind: String): Flow<ThresholdProfile?> =
        queries.observeMeanDrift(kind)
            .asFlow()
            .map { query ->
                query.executeAsOneOrNull()?.let {
                    ThresholdProfile(
                        kind = it.kind,
                        bias = safeBias(it.mean_delta_bias ?: 8.0),
                        sampleCount = it.sample_count ?: 0L,
                        meanDx = it.mean_delta_x ?: 0.0,
                        meanDy = it.mean_delta_y ?: 0.0,
                        meanDw = it.mean_delta_width ?: 0.0,
                        meanDh = it.mean_delta_height ?: 0.0,
                    )
                }
            }

    fun profile(kind: String): ThresholdProfile? =
        queries.meanDrift(kind)
            .executeAsOneOrNull()
            ?.let {
                ThresholdProfile(
                    kind = it.kind,
                    bias = safeBias(it.mean_delta_bias ?: 8.0),
                    sampleCount = it.sample_count ?: 0L,
                    meanDx = it.mean_delta_x ?: 0.0,
                    meanDy = it.mean_delta_y ?: 0.0,
                    meanDw = it.mean_delta_width ?: 0.0,
                    meanDh = it.mean_delta_height ?: 0.0,
                )
            }

    fun recordAcceptedCorrection(
        sample: DetectionSample,
        context: LearningContext,
    ) {
        val bounded = boundedCorrection(sample)
        queries.insertFeedback(
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
            reward = correctionReward(bounded),
        )
    }

    fun recordRejection(
        sample: DetectionSample,
        context: LearningContext,
    ) {
        val bounded = boundedCorrection(
            sample.copy(
                correctedX = sample.estimatedX,
                correctedY = sample.estimatedY,
                correctedWidth = sample.estimatedWidth,
                correctedHeight = sample.estimatedHeight,
            ),
        )
        queries.insertFeedback(
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

    fun policy(kind: String, context: LearningContext): List<PolicyStat> =
        policyQueries.selectByContext(kind, context.key()).executeAsList().map {
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

    private fun updatePolicy(
        kind: String,
        context: LearningContext,
        reward: Double,
    ) {
        val actionIndex = if (reward >= 0.0) 1 else 0
        val contextKey = context.key()
        val current = policyQueries.selectByAction(
            kind = kind,
            contextKey = contextKey,
            actionIndex = actionIndex.toLong(),
        ).executeAsOneOrNull()

        val visits = (current?.visits ?: 0L) + 1L
        val totalReward = (current?.total_reward ?: 0.0) + reward

        policyQueries.upsertPolicy(
            kind = kind,
            contextKey = contextKey,
            actionIndex = actionIndex.toLong(),
            visits = visits,
            totalReward = totalReward,
            lastReward = reward,
            updatedAt = nowEpochMillis(),
        )
    }

    private fun boundedCorrection(sample: DetectionSample): DetectionSample {
        val dx = safeDelta(sample.correctedX - sample.estimatedX)
        val dy = safeDelta(sample.correctedY - sample.estimatedY)
        val dw = safeDelta(sample.correctedWidth - sample.estimatedWidth)
        val dh = safeDelta(sample.correctedHeight - sample.estimatedHeight)

        return sample.copy(
            correctedX = sample.estimatedX + dx,
            correctedY = sample.estimatedY + dy,
            correctedWidth = (sample.estimatedWidth + dw).coerceAtLeast(1.0),
            correctedHeight = (sample.estimatedHeight + dh).coerceAtLeast(1.0),
            thresholdBias = safeBias(sample.thresholdBias),
            blockSize = sample.blockSize.coerceIn(3, 99).let {
                if (it % 2 == 0) it + 1 else it
            },
        )
    }

    private fun safeDelta(delta: Double): Double =
        delta.coerceIn(-3.0, 3.0)

    private fun safeBias(value: Double): Double =
        value.coerceIn(1.0, 15.0)

    private fun correctionReward(sample: DetectionSample): Double {
        val dx = absDelta(sample.correctedX - sample.estimatedX)
        val dy = absDelta(sample.correctedY - sample.estimatedY)
        val dw = absDelta(sample.correctedWidth - sample.estimatedWidth)
        val dh = absDelta(sample.correctedHeight - sample.estimatedHeight)
        val magnitude = dx + dy + dw + dh
        return (1.0 - magnitude / 12.0).coerceIn(-1.0, 1.0)
    }

    private fun absDelta(value: Double): Double = kotlin.math.abs(value)

    private fun nowEpochMillis(): Long =
        kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
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
