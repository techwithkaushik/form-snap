package org.techwithkaushik.formsnap.database

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

actual class LearningMemoryManager(
    private val context: Context,
) {
    companion object {
        private const val CURRENT_SCHEMA_VERSION = 1
        private const val ENTRY_NAME = "learning-memory.json"
        private const val MAX_JSON_BYTES = 25L * 1024L * 1024L
        private const val MAX_ROWS = 100_000L

        private val json = Json {
            encodeDefaults = true
            ignoreUnknownKeys = false
            explicitNulls = true
        }
    }

    private val appContext = context.applicationContext

    private val database by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        LearningDatabase(
            DatabaseDriverFactory(appContext).createDriver(),
        )
    }

    suspend fun exportToFsl(output: OutputStream) =
        withContext(Dispatchers.IO) {
            val archive = readArchive()
            val payload = json.encodeToString(
                LearningMemoryArchiveDto.serializer(),
                archive,
            ).encodeToByteArray()

            require(payload.size.toLong() <= MAX_JSON_BYTES) {
                "Learning archive payload exceeds the maximum allowed size."
            }

            ZipOutputStream(output.buffered()).use { zip ->
                zip.setLevel(Deflater.BEST_COMPRESSION)
                zip.putNextEntry(ZipEntry(ENTRY_NAME))
                zip.write(payload)
                zip.closeEntry()
            }
        }

    suspend fun importFromFsl(input: InputStream) =
        withContext(Dispatchers.IO) {
            val archive = readArchive(input)
            validateArchive(archive)

            database.transaction {
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
                        brightnessBucket = 0L,
                        edgeDensityBucket = 0L,
                        aspectBucket = 0L,
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
            }
        }

    suspend fun exportToFslBytes(): ByteArray =
        withContext(Dispatchers.IO) {
            ByteArrayOutputStream().use { output ->
                exportToFsl(output)
                output.toByteArray()
            }
        }

    suspend fun importFromFslBytes(bytes: ByteArray) {
        require(bytes.isNotEmpty()) {
            "Learning archive is empty."
        }

        bytes.inputStream().use(::importFromFsl)
    }

    private fun readArchive(): LearningMemoryArchiveDto {
        val correctionRows = database.detectionFeedbackQueries
            .selectAllUserCorrectionLogs()
            .executeAsList()

        val tunedRows = database.detectionFeedbackQueries
            .selectAllTunedParameters()
            .executeAsList()

        require(correctionRows.size.toLong() <= MAX_ROWS) {
            "Too many correction rows."
        }

        require(tunedRows.size.toLong() <= MAX_ROWS) {
            "Too many tuned-parameter rows."
        }

        val packageInfo = appContext.packageManager.getPackageInfo(
            appContext.packageName,
            0,
        )

        return LearningMemoryArchiveDto(
            schemaVersion = CURRENT_SCHEMA_VERSION,
            appVersion = packageInfo.versionName.orEmpty().ifBlank { "unknown" },
            userCorrectionLogs = correctionRows.map { row ->
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
                    createdAt = row.created_at,
                )
            },
            tunedParameters = tunedRows.map { row ->
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

    private fun readArchive(
        input: InputStream,
    ): LearningMemoryArchiveDto {
        val payload = ZipInputStream(input.buffered()).use { zip ->
            var entryCount = 0
            var payloadBytes: ByteArray? = null

            while (true) {
                val entry = zip.nextEntry ?: break
                entryCount += 1

                require(entryCount == 1) {
                    "Learning archive must contain exactly one entry."
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

                payloadBytes = output.toByteArray()
                zip.closeEntry()
            }

            requireNotNull(payloadBytes) {
                "Learning archive payload is missing."
            }
        }

        return runCatching {
            json.decodeFromString(
                LearningMemoryArchiveDto.serializer(),
                payload.decodeToString(),
            )
        }.getOrElse { error ->
            throw IllegalArgumentException(
                "Learning archive JSON is invalid.",
                error,
            )
        }
    }

    private fun validateArchive(
        archive: LearningMemoryArchiveDto,
    ) {
        require(archive.schemaVersion == CURRENT_SCHEMA_VERSION) {
            "Unsupported learning archive schema: " + archive.schemaVersion
        }

        require(archive.appVersion.isNotBlank()) {
            "Learning archive application version is missing."
        }

        require(archive.userCorrectionLogs.size.toLong() <= MAX_ROWS) {
            "Too many correction rows."
        }

        require(archive.tunedParameters.size.toLong() <= MAX_ROWS) {
            "Too many tuned-parameter rows."
        }

        archive.userCorrectionLogs.forEach { row ->
            require(row.id > 0L)
            require(row.sampleKey.isNotBlank())
            require(row.kind.isNotBlank())
            require(row.sourceWidth > 0 && row.sourceHeight > 0)

            require(row.estimatedX.isFinite())
            require(row.estimatedY.isFinite())
            require(row.estimatedWidth.isFinite() && row.estimatedWidth > 0.0)
            require(row.estimatedHeight.isFinite() && row.estimatedHeight > 0.0)

            require(row.correctedX.isFinite())
            require(row.correctedY.isFinite())
            require(row.correctedWidth.isFinite() && row.correctedWidth > 0.0)
            require(row.correctedHeight.isFinite() && row.correctedHeight > 0.0)

            require(row.thresholdBias.isFinite())
            require(row.blockSize in 3..999 && row.blockSize % 2 == 1)
            require(row.createdAt >= 0L)
        }

        archive.tunedParameters.forEach { row ->
            require(row.kind.isNotBlank())
            require(row.contextKey.isNotBlank())
            require(row.actionIndex >= 0)
            require(row.visits >= 0L)
            require(row.totalReward.isFinite())
            require(row.lastReward.isFinite())
            require(row.updatedAt >= 0L)
        }
    }
}
