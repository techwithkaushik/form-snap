package org.techwithkaushik.formSnap.foundation

import android.content.Context
import java.io.File

object ProcessingPaths {
    private const val ROOT = "formsnap_temp"

    fun root(context: Context): File =
        File(context.cacheDir, ROOT).apply { mkdirs() }

    fun session(context: Context): File =
        File(root(context), "session_" + System.currentTimeMillis()).apply { mkdirs() }

    fun cleanup(context: Context) {
        root(context).deleteRecursively()
    }

    fun cleanupSession(session: File?) {
        session?.deleteRecursively()
    }
}
