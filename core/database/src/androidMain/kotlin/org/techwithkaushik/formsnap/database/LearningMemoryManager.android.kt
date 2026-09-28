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
                    sample_key = row.sample_key,
                    kind = row.kind,
                    source_width = row.source_width.toLong(),
                    source_height = row.source_height.toLong(),
                    estimated_x = row.estimated_x,
                    estimated_y = row.estimated_y,
                    estimated_width = row.estimated_width,
                    estimated_height = row.estimated_height,
                    corrected_x = row.corrected_x,
                    corrected_y = row.corrected_y,
                    corrected_width = row.corrected_width,
                    corrected_height = row.corrected_height,
                    delta_x = row.corrected_x - row.estimated_x,
                    delta_y = row.corrected_y - row.estimated_y,
                    delta_width = row.corrected_width - row.estimated_width,
                    delta_height = row.corrected_height - row.estimated_height,
                    threshold_bias = row.threshold_bias,
                    block_size = row.block_size.toLong(),
                    accepted = if (row.accepted) 1L else 0L,
                    brightness_bucket = row.brightness_bucket.toLong(),
                    edge_density_bucket = row.edge_density_bucket.toLong(),
                    aspect_bucket = row.aspect_bucket.toLong(),
                    created_at = row.created_at,
                )
            }

            archive.tunedParameters.forEach { row ->
                database.learningDatabaseQueries.upsertParameter(
                    kind = row.kind,
                    context_key = row.context_key,
                    action_index = row.action_index.toLong(),
                    visits = row.visits,
                    total_reward = row.total_reward,
                    last_reward = row.last_reward,
                    updated_at = row.updated_at,
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
                    sample_key = row.sample_key,
                    kind = row.kind,
                    source_width = row.source_width.toInt(),
                    source_height = row.source_height.toInt(),
                    estimated_x = row.estimated_x,
                    estimated_y = row.estimated_y,
                    estimated_width = row.estimated_width,
                    estimated_height = row.estimated_height,
                    corrected_x = row.corrected_x,
                    corrected_y = row.corrected_y,
                    corrected_width = row.corrected_width,
                    corrected_height = row.corrected_height,
                    threshold_bias = row.threshold_bias,
                    block_size = row.block_size.toInt(),
                    accepted = row.accepted != 0L,
                    brightness_bucket = row.brightness_bucket.toInt(),
                    edge_density_bucket = row.edge_density_bucket.toInt(),
                    aspect_bucket = row.aspect_bucket.toInt(),
                    created_at = row.created_at,
                )
            },
            tunedParameters = parameters.map { row ->
                TunedParameterDto(
                    kind = row.kind,
                    context_key = row.context_key,
                    action_index = row.action_index.toInt(),
                    visits = row.visits,
                    total_reward = row.total_reward,
                    last_reward = row.last_reward,
                    updated_at = row.updated_at,
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
            require(row.sample_key.isNotBlank())
            require(row.kind.isNotBlank())
            require(row.source_width > 0)
            require(row.source_height > 0)
            require(row.estimated_x.isFinite() && row.estimated_y.isFinite())
            require(row.estimated_width.isFinite() && row.estimated_width > 0.0)
            require(row.estimated_height.isFinite() && row.estimated_height > 0.0)
            require(row.corrected_x.isFinite() && row.corrected_y.isFinite())
            require(row.corrected_width.isFinite() && row.corrected_width > 0.0)
            require(row.corrected_height.isFinite() && row.corrected_height > 0.0)
            require(row.threshold_bias.isFinite())
            require(row.block_size in 3..999 && row.block_size % 2 == 1)
            require(row.brightness_bucket in 0..31)
            require(row.edge_density_bucket in 0..31)
            require(row.aspect_bucket in 0..31)
            require(row.created_at >= 0L)
        }

        archive.tunedParameters.forEach { row ->
            require(row.kind.isNotBlank())
            require(row.context_key.isNotBlank())
            require(row.action_index in 0..MAX_ACTION_INDEX)
            require(row.visits >= 0L)
            require(row.total_reward.isFinite())
            require(row.last_reward.isFinite())
            require(row.updated_at >= 0L)
        }
    }
}
