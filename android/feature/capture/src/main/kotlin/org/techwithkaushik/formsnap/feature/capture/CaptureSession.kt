package org.techwithkaushik.formsnap.feature.capture

import android.content.Context
import java.io.File
import java.util.UUID

class CaptureSession private constructor(
    val directory: File,
) : AutoCloseable {
    private var closed = false

    fun nextCaptureFile(extension: String = "jpg"): File {
        check(!closed) { "Capture session is closed." }
        val safeExtension = extension.trim().removePrefix(".").ifBlank { "jpg" }
        return File(
            directory,
            "capture_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}.$safeExtension",
        )
    }

    override fun close() {
        if (closed) return
        closed = true
        directory.deleteRecursively()
    }

    companion object {
        fun create(context: Context): CaptureSession {
            val root = File(context.cacheDir, "formsnap_capture").apply { mkdirs() }
            val sessionDir = File(
                root,
                "session_${System.currentTimeMillis()}",
            ).apply { mkdirs() }
            return CaptureSession(sessionDir)
        }

        fun cleanup(context: Context) {
            File(context.cacheDir, "formsnap_capture").deleteRecursively()
        }
    }
}
