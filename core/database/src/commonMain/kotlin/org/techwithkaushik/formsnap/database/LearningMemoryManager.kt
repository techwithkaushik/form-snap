package org.techwithkaushik.formsnap.database

expect class LearningMemoryManager(
    database: LearningDatabase,
    appVersion: String,
) {
    suspend fun exportToFsl(
        password: CharArray,
        sink: suspend (ByteArray) -> Unit,
    )

    suspend fun importFromFsl(
        password: CharArray,
        source: suspend () -> ByteArray,
    )

    suspend fun exportToFslBytes(
        password: CharArray,
    ): ByteArray

    suspend fun importFromFslBytes(
        password: CharArray,
        bytes: ByteArray,
    )
}
