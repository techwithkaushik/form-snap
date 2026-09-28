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
        val ext = extension.trim().removePrefix(".").ifBlank { "jpg" }
        return File(
            directory,
            "capture_" + System.currentTimeMillis() + "_" +
                UUID.randomUUID().toString().take(8) + "." + ext,
        )
    }

    override fun close() {
        if (!closed) {
            closed = true
            directory.deleteRecursively()
        }
    }

    companion object {
        fun create(context: Context): CaptureSession {
            val root = File(context.cacheDir, "formsnap_capture").apply { mkdirs() }
            val session = File(
                root,
                "session_" + System.currentTimeMillis() + "_" +
                    UUID.randomUUID().toString().take(8),
            ).apply { mkdirs() }
            return CaptureSession(session)
        }

        fun cleanup(context: Context) {
            File(context.cacheDir, "formsnap_capture").deleteRecursively()
        }
    }
}
