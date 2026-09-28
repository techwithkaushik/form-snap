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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val janitor = CacheJanitor(context)

    fun onSaveCompleted() {
        scope.launch {
            janitor.clearTemporaryAssets()
        }
    }

    fun onCaptureCancelled() {
        scope.launch {
            janitor.clearTemporaryAssets()
        }
    }

    override fun close() {
        scope.cancel()
    }
}
