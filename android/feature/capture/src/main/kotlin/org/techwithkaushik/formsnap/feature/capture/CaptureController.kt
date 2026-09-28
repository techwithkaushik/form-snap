package org.techwithkaushik.formsnap.feature.capture

import android.net.Uri
import kotlinx.coroutines.flow.StateFlow

interface CaptureController {
    val state: StateFlow<CaptureUiState>
    fun setMode(mode: CaptureMode)
    fun setLens(lens: CameraLens)
    fun setGrid(state: CaptureGridState)
    fun setFlashEnabled(enabled: Boolean)
    fun capture()
    fun importImage()
    fun clearError()
    fun onPermissionResult(granted: Boolean)
    fun onImageSelected(uri: Uri)
    fun onCameraInitialized(available: Boolean, ready: Boolean)
    fun onCaptureFailure(message: String)
}
