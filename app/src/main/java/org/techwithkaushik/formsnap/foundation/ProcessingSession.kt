package org.techwithkaushik.formSnap.foundation

import android.content.Context
import java.io.File

class ProcessingSession private constructor(
    val directory: File,
) : AutoCloseable {
    private var closed = false

    fun file(name: String): File {
        check(!closed) { "Processing session is closed" }
        require(name.isNotBlank()) { "File name must not be blank" }
        return File(directory, name)
    }

    fun closeAndDelete() {
        if (closed) return
        closed = true
        ProcessingPaths.cleanupSession(directory)
    }

    override fun close() = closeAndDelete()

    companion object {
        fun create(context: Context): ProcessingSession =
            ProcessingSession(ProcessingPaths.session(context))
    }
}
