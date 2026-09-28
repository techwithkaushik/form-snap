package org.techwithkaushik.formsnap.database

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class UserCorrectionLogBackup(
    val id: Long,
    val timestamp: Long,
    val contentType: String,
    val detectedX: Long,
    val detectedY: Long,
    val correctedX: Long,
    val correctedY: Long,
    val isRejected: Long,
)

@Serializable
data class TunedParameterBackup(
    val kind: String,
    val contextKey: String,
    val actionIndex: Long,
    val visits: Long,
    val totalReward: Double,
    val lastReward: Double,
    val updatedAt: Long,
)

@Serializable
data class LearningMemoryBackup(
    val schemaVersion: Int = 1,
    val userCorrectionLogs: List<UserCorrectionLogBackup> = emptyList(),
    val tunedParameters: List<TunedParameterBackup> = emptyList(),
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
        val archive = LearningMemoryBackup(
            userCorrectionLogs = queries
                .selectAllLogs()
                .executeAsList()
                .map { row ->
                    UserCorrectionLogBackup(
                        id = row.id,
                        timestamp = row.timestamp,
                        contentType = row.contentType,
                        detectedX = row.detectedX,
                        detectedY = row.detectedY,
                        correctedX = row.correctedX,
                        correctedY = row.correctedY,
                        isRejected = row.isRejected.toLong(),
                    )
                },
            tunedParameters = queries
                .selectAllParameters()
                .executeAsList()
                .map { row ->
                    TunedParameterBackup(
                        kind = row.kind,
                        contextKey = row.contextKey,
                        actionIndex = row.actionIndex,
                        visits = row.visits,
                        totalReward = row.totalReward,
                        lastReward = row.lastReward,
                        updatedAt = row.updatedAt,
                    )
                },
        )

        return json.encodeToString(
            LearningMemoryBackup.serializer(),
            archive,
        ).encodeToByteArray()
    }

    fun importJson(bytes: ByteArray) {
        require(bytes.isNotEmpty()) {
            "Learning memory payload is empty."
        }

        val archive = json.decodeFromString(
            LearningMemoryBackup.serializer(),
            bytes.decodeToString(),
        )

        database.transaction {
            archive.userCorrectionLogs.forEach { row ->
                queries.insertLog(
                    timestamp = row.timestamp,
                    contentType = row.contentType,
                    detectedX = row.detectedX,
                    detectedY = row.detectedY,
                    correctedX = row.correctedX,
                    correctedY = row.correctedY,
                    isRejected = row.isRejected,
                )
            }

            archive.tunedParameters.forEach { row ->
                queries.insertParameter(
                    kind = row.kind,
                    contextKey = row.contextKey,
                    actionIndex = row.actionIndex,
                    visits = row.visits,
                    totalReward = row.totalReward,
                    lastReward = row.lastReward,
                    updatedAt = row.updatedAt,
                )
            }
        }
    }

    fun exportFsl(): ByteArray {
        val jsonBytes = exportJson()
        val output = ByteArrayOutputAccumulator()
        output.writeUtf8("FSL1\n")
        output.writeLengthPrefixed(jsonBytes)
        output.writeLengthPrefixed(ByteArray(0))
        return output.toByteArray()
    }

    fun importFsl(bytes: ByteArray) {
        require(bytes.size >= FSL_HEADER.size) {
            "FSL bundle is too small."
        }

        val reader = ByteArrayReader(bytes)
        val magic = reader.readUtf8(FSL_HEADER.size)
        require(magic == FSL_HEADER) {
            "Unsupported FSL bundle."
        }

        val jsonBytes = reader.readLengthPrefixed()
        reader.readLengthPrefixed()

        require(reader.remaining() == 0) {
            "Trailing bytes found in FSL bundle."
        }

        importJson(jsonBytes)
    }

    private companion object {
        const val FSL_HEADER = "FSL1\n"
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
        writeByte(length ushr 24)
        writeByte(length ushr 16)
        writeByte(length ushr 8)
        writeByte(length)
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
            val next = capacity shl 1
            if (next <= capacity) {
                capacity = required
                break
            }
            capacity = next
        }

        buffer = buffer.copyOf(capacity)
    }

    fun toByteArray(): ByteArray = buffer.copyOf(size)

    private companion object {
        const val INITIAL_CAPACITY = 1024
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

        val result = bytes.copyOfRange(
            position,
            position + length,
        )
        position += length
        return result
    }
}
