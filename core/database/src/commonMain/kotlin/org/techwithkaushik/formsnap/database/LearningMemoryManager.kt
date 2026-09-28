package org.techwithkaushik.formsnap.database

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class UserCorrectionLogDto(
    val id: Long,
    val timestamp: Long,
    val contentType: String,
    val detectedX: Long,
    val detectedY: Long,
    val correctedX: Long,
    val correctedY: Long,
    val isRejected: Int,
)

@Serializable
data class TunedParameterDto(
    val parameterKey: String,
    val parameterValue: Double,
)

@Serializable
data class FslExportBundle(
    val version: Int,
    val parameters: List<TunedParameterDto>,
    val logs: List<UserCorrectionLogDto>,
)

class LearningMemoryManager(
    private val database: LearningDatabase,
) {
    private val queries = database.learningDatabaseQueries

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
        explicitNulls = true
    }

    fun exportJson(): ByteArray {
        val bundle = FslExportBundle(
            version = 1,
            parameters = queries
                .selectAllParameters()
                .executeAsList()
                .map { row ->
                    TunedParameterDto(
                        parameterKey = row.parameterKey,
                        parameterValue = row.parameterValue,
                    )
                },
            logs = queries
                .selectAllLogs()
                .executeAsList()
                .map { row ->
                    UserCorrectionLogDto(
                        id = row.id,
                        timestamp = row.timestamp,
                        contentType = row.contentType,
                        detectedX = row.detectedX,
                        detectedY = row.detectedY,
                        correctedX = row.correctedX,
                        correctedY = row.correctedY,
                        isRejected = row.isRejected.toInt(),
                    )
                },
        )

        return json.encodeToString(
            FslExportBundle.serializer(),
            bundle,
        ).encodeToByteArray()
    }

    fun importJson(bytes: ByteArray) {
        require(bytes.isNotEmpty()) {
            "Learning memory payload is empty."
        }

        val bundle = json.decodeFromString(
            FslExportBundle.serializer(),
            bytes.decodeToString(),
        )

        require(bundle.version == 1) {
            "Unsupported learning memory version: ${bundle.version}"
        }

        database.transaction {
            bundle.logs.forEach { row ->
                queries.insertCorrection(
                    timestamp = row.timestamp,
                    contentType = row.contentType,
                    detectedX = row.detectedX,
                    detectedY = row.detectedY,
                    correctedX = row.correctedX,
                    correctedY = row.correctedY,
                    isRejected = row.isRejected.toLong(),
                )
            }

            bundle.parameters.forEach { row ->
                queries.updateParameter(
                    parameterKey = row.parameterKey,
                    parameterValue = row.parameterValue,
                )
            }
        }
    }

    fun exportFsl(): ByteArray {
        val jsonBytes = exportJson()
        val output = ByteArrayOutputAccumulator()

        output.writeUtf8(FSL_HEADER)
        output.writeLengthPrefixed(jsonBytes)

        return output.toByteArray()
    }

    fun importFsl(bytes: ByteArray) {
        require(bytes.size >= FSL_HEADER.encodeToByteArray().size + LENGTH_PREFIX_SIZE) {
            "FSL bundle is too small."
        }

        val reader = ByteArrayReader(bytes)

        require(reader.readUtf8(FSL_HEADER.encodeToByteArray().size) == FSL_HEADER) {
            "Unsupported FSL bundle."
        }

        val jsonBytes = reader.readLengthPrefixed()

        require(reader.remaining() == 0) {
            "Trailing bytes found in FSL bundle."
        }

        importJson(jsonBytes)
    }

    private companion object {
        const val FSL_HEADER = "FSL1\n"
        const val LENGTH_PREFIX_SIZE = 4
    }
}

private class ByteArrayOutputAccumulator {
    private var buffer = ByteArray(INITIAL_CAPACITY)
    private var size = 0

    fun writeUtf8(value: String) {
        write(value.encodeToByteArray())
    }

    fun writeLengthPrefixed(value: ByteArray) {
        val length = value.size

        require(length <= MAX_PAYLOAD_LENGTH) {
            "Payload is too large."
        }

        writeByte((length ushr 24) and 0xFF)
        writeByte((length ushr 16) and 0xFF)
        writeByte((length ushr 8) and 0xFF)
        writeByte(length and 0xFF)
        write(value)
    }

    private fun writeByte(value: Int) {
        ensureCapacity(1)
        buffer[size++] = (value and 0xFF).toByte()
    }

    private fun write(value: ByteArray) {
        ensureCapacity(value.size)

        value.copyInto(
            destination = buffer,
            destinationOffset = size,
        )

        size += value.size
    }

    private fun ensureCapacity(additional: Int) {
        require(additional >= 0 && size <= Int.MAX_VALUE - additional) {
            "FSL bundle is too large."
        }

        val required = size + additional

        if (required <= buffer.size) {
            return
        }

        var capacity = buffer.size

        while (capacity < required) {
            val doubled = capacity * 2

            capacity = if (doubled > capacity) {
                doubled
            } else {
                required
            }
        }

        buffer = buffer.copyOf(capacity)
    }

    fun toByteArray(): ByteArray =
        buffer.copyOf(size)

    private companion object {
        const val INITIAL_CAPACITY = 1024
        const val MAX_PAYLOAD_LENGTH = Int.MAX_VALUE
    }
}

private class ByteArrayReader(
    private val bytes: ByteArray,
) {
    private var position = 0

    fun readUtf8(length: Int): String =
        readExact(length).decodeToString()

    fun readLengthPrefixed(): ByteArray {
        val a = readUnsignedByte()
        val b = readUnsignedByte()
        val c = readUnsignedByte()
        val d = readUnsignedByte()

        val length = (a shl 24) or (b shl 16) or (c shl 8) or d

        require(length >= 0) {
            "Invalid FSL payload length."
        }

        return readExact(length)
    }

    fun remaining(): Int =
        bytes.size - position

    private fun readUnsignedByte(): Int {
        require(position < bytes.size) {
            "Unexpected end of FSL bundle."
        }

        return bytes[position++].toInt() and 0xFF
    }

    private fun readExact(length: Int): ByteArray {
        require(length >= 0 && position <= bytes.size - length) {
            "Unexpected end of FSL bundle."
        }

        val end = position + length
        val result = bytes.copyOfRange(position, end)

        position = end

        return result
    }
}
