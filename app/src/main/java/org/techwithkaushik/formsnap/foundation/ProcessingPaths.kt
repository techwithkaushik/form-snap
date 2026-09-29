package org.techwithkaushik.formSnap.foundation

import android.content.Context
import java.io.File

object ProcessingPaths {
    private const val ROOT = "formsnap_temp"

    fun root(context: Context): File =
        File(context.cacheDir, ROOT).apply { mkdirs() }

    fun session(context: Context): File =
        File(root(context), "session_" + System.nanoTime()).apply { mkdirs() }

    fun cleanup(context: Context) {
        root(context).deleteRecursively()
    }

    /**
     * Remove only old temporary entries. Active sessions are preserved if the
     * launcher Activity is recreated while a pipeline screen is still running.
     */
    fun cleanupStale(
        context: Context,
        maxAgeMillis: Long = 24L * 60L * 60L * 1000L,
    ) {
        val root = root(context)
        val cutoff = System.currentTimeMillis() - maxAgeMillis
        root.walkBottomUp().forEach { entry ->
            if (entry == root) return@forEach

            // Never delete a non-empty directory by its own mtime: a live session
            // can contain fresh files even when its parent directory is old.
            if (entry.isFile && entry.lastModified() < cutoff) {
                entry.delete()
            } else if (entry.isDirectory && entry.list()?.isEmpty() == true) {
                entry.delete()
            }
        }
    }

    fun cleanupSession(session: File?) {
        session?.deleteRecursively()
    }
}
