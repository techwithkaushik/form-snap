package org.techwithkaushik.formsnap.database

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.SecureRandom
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.min

actual class LearningMemoryManager(
    private val database: LearningDatabase,
    private val appVersion: String,
) {
    companion object {
        private const val CURRENT_SCHEMA_VERSION = 1
        private const val ARCHIVE_VERSION = 2
        private const val ENTRY_NAME = "learning-memory.json"
        private const val MAGIC = "FSL2"
        private const val SALT_SIZE_BYTES = 16
        private const val NONCE_SIZE_BYTES = 12
        private const val KEY_SIZE_BITS = 256
        private const val PBKDF2_ITERATIONS = 150_000
        private const val GCM_TAG_BITS = 128
        private const val MAX_JSON_BYTES = 25L * 1024L * 1024L
        private const val MAX_ARCHIVE_BYTES = 30L * 1024L * 1024L
        private const val MAX_ROWS = 100_000

        private val secureRandom = SecureRandom()

        private val json = Json {
            encodeDefaults = true
            ignoreUnknownKeys = false
            explicitNulls = true
        }
    }

    actual suspend fun exportToFsl(
        password: CharArray,
        sink: suspend (ByteArray) -> Unit,
    ) = withContext(Dispatchers.IO) {
        require(password.isNotEmpty()) {
            "Archive password must not be empty."
        }

        val zipPayload = createZipPayload(readArchive())
        require(zipPayload.size <= MAX_ARCHIVE_BYTES) {
            "Learning archive is too large."
        }

        val salt = ByteArray(SALT_SIZE_BYTES)
        val nonce = ByteArray(NONCE_SIZE_BYTES)
        secureRandom.nextBytes(salt)
        secureRandom.nextBytes(nonce)

        val encrypted = encrypt(
            plaintext = zipPayload,
            password = password,
            salt = salt,
            nonce = nonce,
        )

        val output = ByteArrayOutputStream(
            MAGIC.length + 3 + salt.size + nonce.size + encrypted.size,
        )
        DataOutputStream(output).use { stream ->
            stream.write(MAGIC.toByteArray(Charsets.US_ASCII))
            stream.writeByte(ARCHIVE_VERSION)
            stream.writeByte(salt.size)
            stream.writeByte(nonce.size)
            stream.write(salt)
            stream.write(nonce)
            stream.write(encrypted)
        }

        sink(output.toByteArray())
    }

    actual suspend fun importFromFsl(
        password: CharArray,
        source: suspend () -> ByteArray,
    ) = withContext(Dispatchers.IO) {
        require(password.isNotEmpty()) {
            "Archive password must not be empty."
        }

        val container = source()
        require(container.isNotEmpty()) {
            "Learning archive is empty."
        }
        require(container.size <= MAX_ARCHIVE_BYTES + 1024) {
            "Learning archive is too large."
        }

        val parsed = parseContainer(container)
        val zipPayload = decrypt(
            ciphertext = parsed.encryptedPayload,
            password = password,
            salt = parsed.salt,
            nonce = parsed.nonce,
        )
        require(zipPayload.size <= MAX_ARCHIVE_BYTES) {
            "Decrypted learning archive is too large."
        }

        val archive = parseZipPayload(zipPayload)
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
        }
    }

    actual suspend fun exportToFslBytes(
        password: CharArray,
    ): ByteArray {
        var result = ByteArray(0)
        exportToFsl(password) { bytes ->
            result = bytes
        }
        return result
    }

    actual suspend fun importFromFslBytes(
        password: CharArray,
        bytes: ByteArray,
    ) {
        require(bytes.isNotEmpty()) {
            "Learning archive is empty."
        }
        importFromFsl(password) { bytes }
    }

    private fun readArchive(): LearningMemoryArchiveDto {
        val feedbackRows = database.learningExportQueries
            .selectAllUserCorrectionLogs()
            .executeAsList()
        val policyRows = database.learningExportQueries
            .selectAllTunedParameters()
            .executeAsList()

        require(feedbackRows.size <= MAX_ROWS) {
            "Too many correction rows."
        }
        require(policyRows.size <= MAX_ROWS) {
            "Too many tuned-parameter rows."
        }

        return LearningMemoryArchiveDto(
            schemaVersion = CURRENT_SCHEMA_VERSION,
            appVersion = appVersion.ifBlank { "unknown" },
            userCorrectionLogs = feedbackRows.map { row ->
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
            tunedParameters = policyRows.map { row ->
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

    private fun createZipPayload(
        archive: LearningMemoryArchiveDto,
    ): ByteArray {
        val jsonBytes = json.encodeToString(
            LearningMemoryArchiveDto.serializer(),
            archive,
        ).encodeToByteArray()

        require(jsonBytes.size.toLong() <= MAX_JSON_BYTES) {
            "Learning archive JSON is too large."
        }

        val output = ByteArrayOutputStream(
            min(jsonBytes.size + 128, MAX_ARCHIVE_BYTES.toInt()),
        )
        ZipOutputStream(output).use { zip ->
            zip.setLevel(Deflater.BEST_COMPRESSION)
            zip.putNextEntry(ZipEntry(ENTRY_NAME))
            zip.write(jsonBytes)
            zip.closeEntry()
        }
        return output.toByteArray()
    }

    private fun parseZipPayload(
        zipPayload: ByteArray,
    ): LearningMemoryArchiveDto {
        val jsonBytes = ZipInputStream(
            ByteArrayInputStream(zipPayload),
        ).use { zip ->
            var entryCount = 0
            var payload: ByteArray? = null

            while (true) {
                val entry = zip.nextEntry ?: break
                entryCount += 1
                require(entryCount == 1) {
                    "Learning archive must contain exactly one entry."
                }
                require(!entry.isDirectory && entry.name == ENTRY_NAME) {
                    "Invalid learning archive entry."
                }

                val bytes = ByteArrayOutputStream()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0L

                while (true) {
                    val read = zip.read(buffer)
                    if (read < 0) break
                    total += read
                    require(total <= MAX_JSON_BYTES) {
                        "Learning archive JSON is too large."
                    }
                    bytes.write(buffer, 0, read)
                }

                payload = bytes.toByteArray()
                zip.closeEntry()
            }

            requireNotNull(payload) {
                "Learning archive payload is missing."
            }
        }

        return runCatching {
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
            "Too many correction rows."
        }
        require(archive.tunedParameters.size <= MAX_ROWS) {
            "Too many tuned-parameter rows."
        }

        archive.userCorrectionLogs.forEach { row ->
            require(row.id > 0L)
            require(row.sampleKey.isNotBlank())
            require(row.kind.isNotBlank())
            require(row.sourceWidth in 1..100_000)
            require(row.sourceHeight in 1..100_000)
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
            require(row.actionIndex in 0..32)
            require(row.visits >= 0L)
            require(row.totalReward.isFinite())
            require(row.lastReward.isFinite())
            require(row.updatedAt >= 0L)
        }
    }

    private fun deriveKey(
        password: CharArray,
        salt: ByteArray,
    ): ByteArray {
        val spec = PBEKeySpec(
            password,
            salt,
            PBKDF2_ITERATIONS,
            KEY_SIZE_BITS,
        )
        return try {
            SecretKeyFactory
                .getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec)
                .encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun encrypt(
        plaintext: ByteArray,
        password: CharArray,
        salt: ByteArray,
        nonce: ByteArray,
    ): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val key = deriveKey(password, salt)
        return try {
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(GCM_TAG_BITS, nonce),
            )
            cipher.doFinal(plaintext)
        } finally {
            key.fill(0)
        }
    }

    private fun decrypt(
        ciphertext: ByteArray,
        password: CharArray,
        salt: ByteArray,
        nonce: ByteArray,
    ): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val key = deriveKey(password, salt)
        return try {
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(GCM_TAG_BITS, nonce),
            )
            runCatching {
                cipher.doFinal(ciphertext)
            }.getOrElse { error ->
                throw IllegalArgumentException(
                    "Learning archive password is incorrect or archive is corrupted.",
                    error,
                )
            }
        } finally {
            key.fill(0)
        }
    }

    private fun parseContainer(
        bytes: ByteArray,
    ): ParsedContainer =
        DataInputStream(
            ByteArrayInputStream(bytes),
        ).use { input ->
            val magic = ByteArray(MAGIC.length)
            input.readFully(magic)
            require(magic.decodeToString() == MAGIC) {
                "Invalid learning archive signature."
            }

            val version = input.readUnsignedByte()
            require(version == ARCHIVE_VERSION) {
                "Unsupported learning archive format version: $version"
            }

            val saltSize = input.readUnsignedByte()
            val nonceSize = input.readUnsignedByte()

            require(saltSize == SALT_SIZE_BYTES) {
                "Invalid learning archive salt size."
            }
            require(nonceSize == NONCE_SIZE_BYTES) {
                "Invalid learning archive nonce size."
            }

            val salt = ByteArray(saltSize)
            val nonce = ByteArray(nonceSize)
            input.readFully(salt)
            input.readFully(nonce)

            val payloadOffset =
                MAGIC.length + 3 + salt.size + nonce.size
            val encryptedSize = bytes.size - payloadOffset

            require(encryptedSize > GCM_TAG_BITS / 8) {
                "Learning archive ciphertext is missing."
            }

            val ciphertext = ByteArray(encryptedSize)
            input.readFully(ciphertext)

            ParsedContainer(
                salt = salt,
                nonce = nonce,
                encryptedPayload = ciphertext,
            )
        }

    private data class ParsedContainer(
        val salt: ByteArray,
        val nonce: ByteArray,
        val encryptedPayload: ByteArray,
    )
}
