package org.techwithkaushik.formsnap.feature.capture

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class CaptureLifecycle(
    context: Context,
) : AutoCloseable {
    private val janitor = CacheJanitor(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun onSaveCompleted() {
        scope.launch {
            janitor.disposeForSave()
        }
    }

    fun onCaptureCancelled() {
        scope.launch {
            janitor.disposeForCancel()
        }
    }

    override fun close() {
        janitor.disposeNow()
        scope.cancel()
    }

    private fun CacheJanitor.disposeNow() {
        val root = contextCacheRoot()
        listOf(
            java.io.File(root, "formsnap_capture"),
            java.io.File(root, "formsnap_temp"),
            java.io.File(root, "formsnap_pipeline"),
            java.io.File(root, "formsnap_outputs"),
            java.io.File(root, "captures"),
        ).forEach { it.deleteRecursively() }
    }

    private fun contextCacheRoot(): java.io.File = janitorCacheRoot()
    private fun janitorCacheRoot(): java.io.File {
        val field = CacheJanitor::class.java.getDeclaredField("appContext")
        field.isAccessible = true
        val ctx = field.get(janitor) as Context
        return ctx.cacheDir
    }
}
