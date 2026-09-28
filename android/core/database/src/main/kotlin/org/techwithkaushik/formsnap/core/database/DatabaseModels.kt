package org.techwithkaushik.formsnap.core.database

data class DriftProfile(
    val kind: String,
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
    val samples: Long
)

data class FeedbackInput(
    val sampleKey: String,
    val kind: String,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val estimatedLeft: Double,
    val estimatedTop: Double,
    val estimatedRight: Double,
    val estimatedBottom: Double,
    val correctedLeft: Double,
    val correctedTop: Double,
    val correctedRight: Double,
    val correctedBottom: Double,
    val adaptiveBias: Double,
    val blockSize: Int,
    val localC: Double,
    val accepted: Boolean,
    val actionIndex: Int,
    val contextBrightness: Double,
    val contextEdgeDensity: Double,
    val contextAspect: Double,
    val createdAt: Long
)

data class PolicyStat(
    val kind: String,
    val contextKey: String,
    val actionIndex: Int,
    val visits: Long,
    val totalReward: Double,
    val lastReward: Double,
    val updatedAt: Long
)
