package org.techwithkaushik.formsnap.core.database

import app.cash.sqldelight.db.SqlDriver
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

actual class LearningMemoryManager(
    private val database: FormSnapDatabase,
    private val driver: SqlDriver,
    private val appVersion: String,
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
        private const val ENTRY_NAME = "learning-memory.json"
        private const val MAX_JSON_BYTES = 25L * 1024L * 1024L
        private const val MAX_ROWS = 100_000

        private val json = Json {
            encodeDefaults = true
            ignoreUnknownKeys = false
            explicitNulls = true
        }
    }

    fun exportToFsl(output: OutputStream) {
        val archive = readArchive()
        val payload = json.encodeToString(
            LearningMemoryArchiveDto.serializer(),
            archive,
        ).encodeToByteArray()

        require(payload.size.toLong() <= MAX_JSON_BYTES) {
            "Learning-memory payload is too large."
        }

        ZipOutputStream(output.buffered()).use { zip ->
            zip.setLevel(Deflater.BEST_COMPRESSION)
            zip.putNextEntry(ZipEntry(ENTRY_NAME))
            zip.write(payload)
            zip.closeEntry()
        }
    }

    fun importFromFsl(input: InputStream) {
        val payload = readArchiveEntry(input)
        val archive = json.decodeFromString(
            LearningMemoryArchiveDto.serializer(),
            payload.decodeToString(),
        )

        require(archive.schemaVersion == CURRENT_SCHEMA_VERSION) {
            "Unsupported learning archive schema: " + archive.schemaVersion
        }
        validateArchive(archive)

        driver.execute(null, "BEGIN IMMEDIATE", 0)
        try {
            archive.userCorrectionLogs.forEach { row ->
                database.detectionFeedbackQueries.replaceFeedback(
                    id = row.id,
                    sampleKey = row.sampleKey,
                    kind = row.kind,
                    sourceWidth = row.sourceWidth.toLong(),
                    sourceHeight = row.sourceHeight.toLong(),
                    estimatedX = row.estimatedX,
                    estimatedY = row.estimatedY,
                    estimatedWidth = row.estimatedWidth,
                    estimatedHeight = row.estimatedHeight,
                    correctedX = row.correctedX,
                    correctedY = row.correctedY,
                    correctedWidth = row.correctedWidth,
                    correctedHeight = row.correctedHeight,
                    thresholdBias = row.thresholdBias,
                    blockSize = row.blockSize.toLong(),
                    accepted = if (row.accepted) 1L else 0L,
                    brightnessBucket = row.brightnessBucket.toLong(),
                    edgeDensityBucket = row.edgeDensityBucket.toLong(),
                    aspectBucket = row.aspectBucket.toLong(),
                    createdAt = row.createdAt,
                )
            }

            archive.tunedParameters.forEach { row ->
                database.detectionFeedbackQueries.upsertPolicy(
                    kind = row.kind,
                    contextKey = row.contextKey,
                    actionIndex = row.actionIndex.toLong(),
                    visits = row.visits,
                    totalReward = row.totalReward,
                    lastReward = row.lastReward,
                    updatedAt = row.updatedAt,
                )
            }

            driver.execute(null, "COMMIT", 0)
        } catch (t: Throwable) {
            runCatching { driver.execute(null, "ROLLBACK", 0) }
            throw t
        }
    }

    private fun readArchive(): LearningMemoryArchiveDto {
        val feedback = database.learningExportQueries
            .selectAllUserCorrectionLogs()
            .executeAsList()

        val policy = database.learningExportQueries
            .selectAllTunedParameters()
            .executeAsList()

        return LearningMemoryArchiveDto(
            schemaVersion = CURRENT_SCHEMA_VERSION,
            appVersion = appVersion,
            tunedParameters = policy.map {
                TunedParameterDto(
                    kind = it.kind,
                    contextKey = it.context_key,
                    actionIndex = it.action_index.toInt(),
                    visits = it.visits,
                    totalReward = it.total_reward,
                    lastReward = it.last_reward,
                    updatedAt = it.updated_at,
                )
            },
            userCorrectionLogs = feedback.map {
                UserCorrectionLogDto(
                    id = it.id,
                    sampleKey = it.sample_key,
                    kind = it.kind,
                    sourceWidth = it.source_width.toInt(),
                    sourceHeight = it.source_height.toInt(),
                    estimatedX = it.estimated_x,
                    estimatedY = it.estimated_y,
                    estimatedWidth = it.estimated_width,
                    estimatedHeight = it.estimated_height,
                    correctedX = it.corrected_x,
                    correctedY = it.corrected_y,
                    correctedWidth = it.corrected_width,
                    correctedHeight = it.corrected_height,
                    thresholdBias = it.threshold_bias,
                    blockSize = it.block_size.toInt(),
                    accepted = it.accepted != 0L,
                    brightnessBucket = it.brightness_bucket.toInt(),
                    edgeDensityBucket = it.edge_density_bucket.toInt(),
                    aspectBucket = it.aspect_bucket.toInt(),
                    createdAt = it.created_at,
                )
            },
        )
    }

    private fun readArchiveEntry(input: InputStream): ByteArray {
        ZipInputStream(input.buffered()).use { zip ->
            var payload: ByteArray? = null
            var entryCount = 0

            var entry = zip.nextEntry
            while (entry != null) {
                entryCount += 1
                require(entryCount == 1) {
                    "Learning archive must contain exactly one payload entry."
                }
                require(!entry.isDirectory && entry.name == ENTRY_NAME) {
                    "Invalid learning archive entry."
                }

                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                val output = ByteArrayOutputStream()
                var total = 0L

                while (true) {
                    val read = zip.read(buffer)
                    if (read < 0) break
                    total += read
                    require(total <= MAX_JSON_BYTES) {
                        "Learning archive is too large."
                    }
                    output.write(buffer, 0, read)
                }

                payload = output.toByteArray()
                zip.closeEntry()
                entry = zip.nextEntry
            }

            return requireNotNull(payload) {
                "Learning archive is missing " + ENTRY_NAME + "."
            }
        }
    }

    private fun validateArchive(archive: LearningMemoryArchiveDto) {
        require(archive.appVersion.isNotBlank()) {
            "Archive app version is missing."
        }
        require(archive.tunedParameters.size <= MAX_ROWS) {
            "Too many tuned-parameter rows."
        }
        require(archive.userCorrectionLogs.size <= MAX_ROWS) {
            "Too many correction rows."
        }

        archive.tunedParameters.forEach { row ->
            require(row.kind.isNotBlank() && row.contextKey.isNotBlank())
            require(row.actionIndex in 0..32)
            require(row.visits >= 0L)
            require(row.totalReward.isFinite() && row.lastReward.isFinite())
            require(row.updatedAt >= 0L)
        }

        archive.userCorrectionLogs.forEach { row ->
            require(row.id > 0L)
            require(row.sampleKey.isNotBlank() && row.kind.isNotBlank())
            require(row.sourceWidth > 0 && row.sourceHeight > 0)
            require(row.estimatedX.isFinite() && row.estimatedY.isFinite())
            require(row.estimatedWidth > 0.0 && row.estimatedHeight > 0.0)
            require(row.correctedX.isFinite() && row.correctedY.isFinite())
            require(row.correctedWidth > 0.0 && row.correctedHeight > 0.0)
            require(row.thresholdBias.isFinite())
            require(row.blockSize in 3..999 && row.blockSize % 2 == 1)
            require(row.createdAt >= 0L)
        }
    }
}
