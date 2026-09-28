package org.techwithkaushik.formsnap.core.database

import app.cash.sqldelight.db.SqlDriver
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@Serializable
data class LearningArchive(
    val schemaVersion: Int,
    val appVersion: String,
    val tunedParameters: List<TunedParameterRecord>,
    val userCorrectionLogs: List<UserCorrectionLogRecord>,
)

@Serializable
data class TunedParameterRecord(
    val id: Long? = null,
    val kind: String,
    val contextKey: String,
    val actionIndex: Int,
    val visits: Long,
    val totalReward: Double,
    val lastReward: Double,
    val updatedAt: Long,
)

@Serializable
data class UserCorrectionLogRecord(
    val id: Long? = null,
    val sampleKey: String,
    val kind: String,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val estimatedX: Double,
    val estimatedY: Double,
    val estimatedWidth: Double,
    val estimatedHeight: Double,
    val correctedX: Double,
    val correctedY: Double,
    val correctedWidth: Double,
    val correctedHeight: Double,
    val thresholdBias: Double,
    val blockSize: Int,
    val accepted: Boolean,
    val brightnessBucket: Int,
    val edgeDensityBucket: Int,
    val aspectBucket: Int,
    val createdAt: Long,
)

class LearningMemoryManager(
    private val database: FormSnapDatabase,
    private val driver: SqlDriver,
    private val appVersion: String,
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
        private const val ENTRY_NAME = "learning-memory.json"
        private const val MAX_ARCHIVE_BYTES = 25L * 1024L * 1024L
        private val json = Json {
            encodeDefaults = true
            ignoreUnknownKeys = false
            explicitNulls = true
        }
    }

    fun exportLearning(output: OutputStream) {
        require(output !is java.io.FileOutputStream || output.fd.valid()) {
            "Output stream is not writable."
        }

        val archive = readArchive()
        ZipOutputStream(output.buffered()).use { zip ->
            val payload = json.encodeToString(LearningArchive.serializer(), archive)
                .encodeToByteArray()
            require(payload.size <= MAX_ARCHIVE_BYTES) {
                "Learning archive exceeds the maximum supported size."
            }

            zip.putNextEntry(ZipEntry(ENTRY_NAME))
            zip.write(payload)
            zip.closeEntry()
        }
    }

    fun importLearning(input: InputStream) {
        val payload = readSingleArchiveEntry(input)
        require(payload.size <= MAX_ARCHIVE_BYTES) {
            "Learning archive exceeds the maximum supported size."
        }

        val archive = json.decodeFromString(
            LearningArchive.serializer(),
            payload.decodeToString(),
        )
        require(archive.schemaVersion in 1..CURRENT_SCHEMA_VERSION) {
            "Unsupported learning schema version: ${archive.schemaVersion}"
        }

        driver.execute(null, "BEGIN IMMEDIATE TRANSACTION", 0)
        try {
            restore(archive)
            driver.execute(null, "COMMIT", 0)
        } catch (t: Throwable) {
            runCatching { driver.execute(null, "ROLLBACK", 0) }
            throw t
        }
    }

    private fun readArchive(): LearningArchive {
        val feedback = database.detectionFeedbackQueries
            .selectAllForLearning()
            .executeAsList()

        val policy = database.detectionFeedbackQueries
            .selectAllPolicyStats()
            .executeAsList()

        return LearningArchive(
            schemaVersion = CURRENT_SCHEMA_VERSION,
            appVersion = appVersion,
            tunedParameters = policy.map {
                TunedParameterRecord(
                    kind = it.kind,
                    contextKey = it.context_key,
                    actionIndex = it.action_index,
                    visits = it.visits,
                    totalReward = it.total_reward,
                    lastReward = it.last_reward,
                    updatedAt = it.updated_at,
                )
            },
            userCorrectionLogs = feedback.map {
                UserCorrectionLogRecord(
                    id = it.id,
                    sampleKey = it.sample_key,
                    kind = it.kind,
                    sourceWidth = it.source_width,
                    sourceHeight = it.source_height,
                    estimatedX = it.estimated_x,
                    estimatedY = it.estimated_y,
                    estimatedWidth = it.estimated_width,
                    estimatedHeight = it.estimated_height,
                    correctedX = it.corrected_x,
                    correctedY = it.corrected_y,
                    correctedWidth = it.corrected_width,
                    correctedHeight = it.corrected_height,
                    thresholdBias = it.threshold_bias,
                    blockSize = it.block_size,
                    accepted = it.accepted != 0L,
                    brightnessBucket = it.brightness_bucket,
                    edgeDensityBucket = it.edge_density_bucket,
                    aspectBucket = it.aspect_bucket,
                    createdAt = it.created_at,
                )
            },
        )
    }

    private fun restore(archive: LearningArchive) {
        archive.userCorrectionLogs.forEach { row ->
            database.detectionFeedbackQueries.insertFeedback(
                sample_key = row.sampleKey,
                kind = row.kind,
                source_width = row.sourceWidth.toLong(),
                source_height = row.sourceHeight.toLong(),
                estimated_x = row.estimatedX,
                estimated_y = row.estimatedY,
                estimated_width = row.estimatedWidth,
                estimated_height = row.estimatedHeight,
                corrected_x = row.correctedX,
                corrected_y = row.correctedY,
                corrected_width = row.correctedWidth,
                corrected_height = row.correctedHeight,
                threshold_bias = row.thresholdBias,
                block_size = row.blockSize.toLong(),
                accepted = if (row.accepted) 1L else 0L,
                brightness_bucket = row.brightnessBucket.toLong(),
                edge_density_bucket = row.edgeDensityBucket.toLong(),
                aspect_bucket = row.aspectBucket.toLong(),
                created_at = row.createdAt,
            )
        }

        archive.tunedParameters.forEach { row ->
            database.detectionFeedbackQueries.upsertPolicy(
                kind = row.kind,
                context_key = row.contextKey,
                action_index = row.actionIndex.toLong(),
                visits = row.visits,
                total_reward = row.totalReward,
                last_reward = row.lastReward,
                updated_at = row.updatedAt,
            )
        }
    }

    private fun readSingleArchiveEntry(input: InputStream): ByteArray {
        ZipInputStream(input.buffered()).use { zip ->
            var selected: ByteArray? = null
            var entry: ZipEntry? = zip.nextEntry
            while (entry != null) {
                require(!entry.isDirectory) { "Learning archive contains a directory entry." }
                require(entry.name == ENTRY_NAME) { "Unexpected learning archive entry: ${entry.name}" }
                require(selected == null) { "Learning archive contains duplicate payload entries." }

                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                val output = java.io.ByteArrayOutputStream()
                var total = 0L
                while (true) {
                    val read = zip.read(buffer)
                    if (read < 0) break
                    total += read
                    require(total <= MAX_ARCHIVE_BYTES) {
                        "Learning archive is too large."
                    }
                    output.write(buffer, 0, read)
                }
                selected = output.toByteArray()
                zip.closeEntry()
                entry = zip.nextEntry
            }

            return requireNotNull(selected) {
                "Learning archive does not contain $ENTRY_NAME"
            }
        }
    }
}
