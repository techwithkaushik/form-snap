package org.techwithkaushik.formsnap.database

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

actual class LearningMemoryManager(
    private val database: LearningDatabase,
    private val appVersion: String,
) {
    private companion object {
        const val CURRENT_SCHEMA_VERSION = 1
        const val ENTRY_NAME = "learning-memory.json"
        const val MAX_JSON_BYTES = 25L * 1024L * 1024L
        const val MAX_BUNDLE_BYTES = 30L * 1024L * 1024L
        const val MAX_ROWS = 100_000
        const val MAX_ACTION_INDEX = 32

        val json = Json {
            encodeDefaults = true
            ignoreUnknownKeys = false
            explicitNulls = true
        }
    }

    actual suspend fun exportToFsl(
        sink: suspend (ByteArray) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val archive = readArchive()
        val jsonBytes = json.encodeToString(
            LearningMemoryArchiveDto.serializer(),
            archive,
        ).encodeToByteArray()

        require(jsonBytes.size.toLong() <= MAX_JSON_BYTES) {
            "Learning archive JSON exceeds the supported size."
        }

        val bundle = ByteArrayOutputStream(
            jsonBytes.size.coerceAtMost(MAX_BUNDLE_BYTES.toInt()),
        )

        ZipOutputStream(bundle).use { zip ->
            zip.setLevel(Deflater.BEST_COMPRESSION)
            zip.putNextEntry(ZipEntry(ENTRY_NAME))
            zip.write(jsonBytes)
            zip.closeEntry()
        }

        val bytes = bundle.toByteArray()
        require(bytes.size <= MAX_BUNDLE_BYTES) {
            "Learning archive bundle exceeds the supported size."
        }

        sink(bytes)
    }

    actual suspend fun importFromFsl(
        source: suspend () -> ByteArray,
    ) = withContext(Dispatchers.IO) {
        val bundle = source()
        require(bundle.isNotEmpty()) {
            "Learning archive is empty."
        }
        require(bundle.size <= MAX_BUNDLE_BYTES) {
            "Learning archive bundle exceeds the supported size."
        }

        val jsonBytes = readZipPayload(bundle)
        val archive = runCatching {
            json.decodeFromString(
                LearningMemoryArchiveDto.serializer(),
                jsonBytes.decodeToString(),
            )
        }.getOrElse { error ->
            throw IllegalArgumentException(
                "Learning archive JSON is invalid.",
                error,
            )
        }

        validateArchive(archive)

        database.transaction {
            archive.userCorrectionLogs.forEach { row ->
                database.learningDatabaseQueries.replaceLog(
                    id = row.id,
                )
                database.learningDatabaseQueries.insertReplacementLog(
                    id = row.id,
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
                    delta_x = row.correctedX - row.estimatedX,
                    delta_y = row.correctedY - row.estimatedY,
                    delta_width = row.correctedWidth - row.estimatedWidth,
                    delta_height = row.correctedHeight - row.estimatedHeight,
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
                database.learningDatabaseQueries.upsertParameter(
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
    }

    actual suspend fun exportToFslBytes(): ByteArray {
        var exported = ByteArray(0)
        exportToFsl { bytes ->
            exported = bytes
        }
        return exported
    }

    actual suspend fun importFromFslBytes(
        bytes: ByteArray,
    ) {
        require(bytes.isNotEmpty()) {
            "Learning archive is empty."
        }
        importFromFsl { bytes }
    }

    private fun readArchive(): LearningMemoryArchiveDto {
        val logs = database.learningDatabaseQueries
            .selectAllLogs()
            .executeAsList()
            .take(MAX_ROWS)

        val parameters = database.learningDatabaseQueries
            .selectAllParameters()
            .executeAsList()
            .take(MAX_ROWS)

        return LearningMemoryArchiveDto(
            schemaVersion = CURRENT_SCHEMA_VERSION,
            appVersion = appVersion.ifBlank { "unknown" },
            userCorrectionLogs = logs.map { row ->
                UserCorrectionLogDto(
                    id = row.id,
                    sampleKey = row.sample_key,
                    kind = row.kind,
                    sourceWidth = row.source_width.toInt(),
                    sourceHeight = row.source_height.toInt(),
                    estimatedX = row.estimated_x,
                    estimatedY = row.estimated_y,
                    estimatedWidth = row.estimated_width,
                    estimatedHeight = row.estimated_height,
                    correctedX = row.corrected_x,
                    correctedY = row.corrected_y,
                    correctedWidth = row.corrected_width,
                    correctedHeight = row.corrected_height,
                    thresholdBias = row.threshold_bias,
                    blockSize = row.block_size.toInt(),
                    accepted = row.accepted != 0L,
                    brightnessBucket = row.brightness_bucket.toInt(),
                    edgeDensityBucket = row.edge_density_bucket.toInt(),
                    aspectBucket = row.aspect_bucket.toInt(),
                    createdAt = row.created_at,
                )
            },
            tunedParameters = parameters.map { row ->
                TunedParameterDto(
                    kind = row.kind,
                    contextKey = row.context_key,
                    actionIndex = row.action_index.toInt(),
                    visits = row.visits,
                    totalReward = row.total_reward,
                    lastReward = row.last_reward,
                    updatedAt = row.updated_at,
                )
            },
        )
    }

    private fun readZipPayload(bundle: ByteArray): ByteArray =
        ZipInputStream(ByteArrayInputStream(bundle)).use { zip ->
            var entries = 0
            var payload: ByteArray? = null

            while (true) {
                val entry = zip.nextEntry ?: break
                entries += 1

                require(entries == 1) {
                    "Learning archive must contain exactly one entry."
                }
                require(!entry.isDirectory && entry.name == ENTRY_NAME) {
                    "Learning archive contains an invalid entry."
                }

                val output = ByteArrayOutputStream()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0L

                while (true) {
                    val count = zip.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= MAX_JSON_BYTES) {
                        "Learning archive JSON exceeds the supported size."
                    }
                    output.write(buffer, 0, count)
                }

                payload = output.toByteArray()
                zip.closeEntry()
            }

            requireNotNull(payload) {
                "Learning archive payload is missing."
            }
        }

    private fun validateArchive(
        archive: LearningMemoryArchiveDto,
    ) {
        require(archive.schemaVersion == CURRENT_SCHEMA_VERSION) {
            "Unsupported learning archive schema: ${archive.schemaVersion}"
        }
        require(archive.appVersion.isNotBlank()) {
            "Learning archive application version is missing."
        }
        require(archive.userCorrectionLogs.size <= MAX_ROWS) {
            "Too many correction log rows."
        }
        require(archive.tunedParameters.size <= MAX_ROWS) {
            "Too many tuned-parameter rows."
        }

        archive.userCorrectionLogs.forEach { row ->
            require(row.id > 0L)
            require(row.sampleKey.isNotBlank())
            require(row.kind.isNotBlank())
            require(row.sourceWidth > 0)
            require(row.sourceHeight > 0)
            require(row.estimatedX.isFinite() && row.estimatedY.isFinite())
            require(row.estimatedWidth.isFinite() && row.estimatedWidth > 0.0)
            require(row.estimatedHeight.isFinite() && row.estimatedHeight > 0.0)
            require(row.correctedX.isFinite() && row.correctedY.isFinite())
            require(row.correctedWidth.isFinite() && row.correctedWidth > 0.0)
            require(row.correctedHeight.isFinite() && row.correctedHeight > 0.0)
            require(row.thresholdBias.isFinite())
            require(row.blockSize in 3..999 && row.blockSize % 2 == 1)
            require(row.brightnessBucket in 0..31)
            require(row.edgeDensityBucket in 0..31)
            require(row.aspectBucket in 0..31)
            require(row.createdAt >= 0L)
        }

        archive.tunedParameters.forEach { row ->
            require(row.kind.isNotBlank())
            require(row.contextKey.isNotBlank())
            require(row.actionIndex in 0..MAX_ACTION_INDEX)
            require(row.visits >= 0L)
            require(row.totalReward.isFinite())
            require(row.lastReward.isFinite())
            require(row.updatedAt >= 0L)
        }
    }
}
