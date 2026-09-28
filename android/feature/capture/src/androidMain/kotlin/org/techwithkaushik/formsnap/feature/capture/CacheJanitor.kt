package org.techwithkaushik.formsnap.feature.capture

import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class CacheJanitor(
    context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val appContext = context.applicationContext

    suspend fun disposeForSave() = dispose()
    suspend fun disposeForCancel() = dispose()

    fun disposeNow() {
        val cacheRoot = appContext.cacheDir
        cleanup(cacheRoot)
    }

    private suspend fun dispose() = withContext(dispatcher) {
        cleanup(appContext.cacheDir)
    }

    private fun cleanup(cacheRoot: File) {
        listOf(
            File(cacheRoot, "formsnap_capture"),
            File(cacheRoot, "formsnap_temp"),
            File(cacheRoot, "formsnap_pipeline"),
            File(cacheRoot, "formsnap_outputs"),
            File(cacheRoot, "captures"),
        ).forEach(::deleteRecursivelySafely)

        cacheRoot.listFiles()?.forEach { file ->
            if (isTemporaryFrame(file)) deleteRecursivelySafely(file)
        }
    }

    private fun isTemporaryFrame(file: File): Boolean {
        val normalized = file.name.lowercase(Locale.US)
        return normalized == "temp_form.jpg" ||
            normalized == "temp_form.jpeg" ||
            normalized.startsWith("temp_form_") ||
            normalized.startsWith("capture_") ||
            normalized.startsWith("frame_") ||
            normalized.endsWith(".tmp")
    }

    private fun deleteRecursivelySafely(file: File) {
        if (!file.exists()) return
        runCatching {
            if (file.isDirectory) file.deleteRecursively() else file.delete()
        }
    }
}
