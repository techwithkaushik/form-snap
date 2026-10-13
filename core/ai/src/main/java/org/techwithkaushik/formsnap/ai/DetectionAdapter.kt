package org.techwithkaushik.formsnap.ai

import android.graphics.Bitmap

/** Common live-camera contract for supported object-detection adapters. */
interface DetectionAdapter : AutoCloseable {
    val lastInferenceDiagnostics: String
    fun detectAsync(
        bitmap: Bitmap,
        onResult: (List<DetectedObject>) -> Unit,
        onError: (Throwable) -> Unit = {},
    )
}
