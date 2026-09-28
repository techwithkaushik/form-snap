package org.techwithkaushik.formsnap.feature.capture

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

actual class CacheJanitor(
    context: Context,
) {
    private val appContext = context.applicationContext

    actual suspend fun clearTemporaryAssets() = withContext(Dispatchers.IO) {
        val root = appContext.cacheDir
        val managedDirectories = setOf(
            "formsnap_capture",
            "formsnap_temp",
            "formsnap_pipeline",
            "formsnap_outputs",
            "captures",
        )

        root.listFiles()?.forEach { file ->
            if (file.name in managedDirectories || isTemporaryAsset(file)) {
                deleteSafely(file)
            }
        }
    }

    private fun isTemporaryAsset(file: File): Boolean {
        val name = file.name.lowercase(Locale.US)
        return name == "temp_form.jpg" ||
            name == "temp_form.jpeg" ||
            name == "temp_form.png" ||
            name.startsWith("temp_form_") ||
            name.startsWith("capture_") ||
            name.startsWith("frame_") ||
            name.startsWith("intermediate_") ||
            name.startsWith("matrix_") ||
            name.endsWith(".tmp") ||
            name.endsWith(".matrix")
    }

    private fun deleteSafely(file: File) {
        runCatching {
            if (file.isDirectory) file.deleteRecursively() else file.delete()
        }
    }
}
