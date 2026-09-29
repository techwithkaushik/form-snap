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
    private val repository: LearningRepository,
) {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
        explicitNulls = true
    }

    fun exportJson(): ByteArray {
        val bundle = FslExportBundle(
            version = 1,
            parameters = repository.allParameters().map {
                TunedParameterDto(it.parameterKey, it.parameterValue)
            },
            logs = repository.allLogsSnapshot().map {
                UserCorrectionLogDto(
                    id = it.id,
                    timestamp = it.timestamp,
                    contentType = it.contentType,
                    detectedX = it.detectedX,
                    detectedY = it.detectedY,
                    correctedX = it.correctedX,
                    correctedY = it.correctedY,
                    isRejected = it.isRejected.toInt(),
                )
            },
        )
        return json.encodeToString(FslExportBundle.serializer(), bundle).encodeToByteArray()
    }

    fun importJson(bytes: ByteArray) {
        require(bytes.isNotEmpty()) { "Learning memory payload is empty." }
        val bundle = json.decodeFromString(FslExportBundle.serializer(), bytes.decodeToString())
        require(bundle.version == 1) { "Unsupported learning memory version: ${bundle.version}" }

        bundle.logs.forEach {
            repository.record(
                it.timestamp,
                it.contentType,
                it.detectedX,
                it.detectedY,
                it.correctedX,
                it.correctedY,
                it.isRejected.toLong(),
            )
        }
        bundle.parameters.forEach {
            repository.updateParameter(it.parameterKey, it.parameterValue)
        }
    }

    fun exportFsl(): ByteArray {
        val payload = exportJson()
        val out = ByteArrayOutputAccumulator()
        out.writeUtf8(FSL_HEADER)
        out.writeLengthPrefixed(payload)
        return out.toByteArray()
    }

    fun importFsl(bytes: ByteArray) {
        require(bytes.size >= FSL_HEADER.encodeToByteArray().size + 4) {
            "FSL bundle is too small."
        }
        val reader = ByteArrayReader(bytes)
        require(reader.readUtf8(FSL_HEADER.encodeToByteArray().size) == FSL_HEADER) {
            "Unsupported FSL bundle."
        }
        val payload = reader.readLengthPrefixed()
        require(reader.remaining() == 0) { "Trailing bytes found in FSL bundle." }
        importJson(payload)
    }

    private companion object {
        const val FSL_HEADER = "FSL1\n"
    }
}

private class ByteArrayOutputAccumulator {
    private var buffer = ByteArray(1024)
    private var size = 0

    fun writeUtf8(value: String) {
        write(value.encodeToByteArray())
    }

    fun writeLengthPrefixed(value: ByteArray) {
        require(value.size <= Int.MAX_VALUE) { "Payload is too large." }
        writeByte(value.size ushr 24)
        writeByte(value.size ushr 16)
        writeByte(value.size ushr 8)
        writeByte(value.size)
        write(value)
    }

    private fun writeByte(value: Int) {
        ensureCapacity(1)
        buffer[size++] = value.toByte()
    }

    private fun write(value: ByteArray) {
        ensureCapacity(value.size)
        value.copyInto(buffer, size)
        size += value.size
    }

    private fun ensureCapacity(additional: Int) {
        require(additional >= 0 && size <= Int.MAX_VALUE - additional) {
            "FSL bundle is too large."
        }
        val required = size + additional
        if (required <= buffer.size) return
        var capacity = buffer.size
        while (capacity < required) {
            capacity = (capacity * 2).coerceAtLeast(required)
        }
        buffer = buffer.copyOf(capacity)
    }

    fun toByteArray(): ByteArray = buffer.copyOf(size)
}

private class ByteArrayReader(
    private val bytes: ByteArray,
) {
    private var position = 0

    fun readUtf8(length: Int): String = readExact(length).decodeToString()

    fun readLengthPrefixed(): ByteArray {
        val length = (readUnsignedByte() shl 24) or
            (readUnsignedByte() shl 16) or
            (readUnsignedByte() shl 8) or
            readUnsignedByte()
        require(length >= 0) { "Invalid FSL payload length." }
        return readExact(length)
    }

    fun remaining(): Int = bytes.size - position

    private fun readUnsignedByte(): Int {
        require(position < bytes.size) { "Unexpected end of FSL bundle." }
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
