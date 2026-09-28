package org.techwithkaushik.formsnap.feature.capture

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class CaptureViewModel(
    initialMode: CaptureMode = CaptureMode.WHOLE_FORM,
) : ViewModel(), CaptureController {
    private val _state = MutableStateFlow(CaptureUiState(mode = initialMode))
    override val state = _state.asStateFlow()

    private val _events = MutableSharedFlow<CaptureEvent>(
        extraBufferCapacity = 8,
    )
    val events: SharedFlow<CaptureEvent> = _events.asSharedFlow()

    override fun setMode(mode: CaptureMode) = _state.update { it.copy(mode = mode, lastError = null) }

    override fun setLens(lens: CameraLens) = _state.update { it.copy(lens = lens, lastError = null) }

    override fun setGrid(state: CaptureGridState) = _state.update { it.copy(grid = state) }

    override fun setFlashEnabled(enabled: Boolean) =
        _state.update { it.copy(flashEnabled = enabled) }

    override fun capture() {
        val current = _state.value
        if (!current.permissionGranted) {
            _events.tryEmit(CaptureEvent.CameraPermissionRequired)
            return
        }
        if (!current.cameraAvailable || !current.cameraReady) {
            emitError("Camera is not ready.")
            return
        }
        _state.update { it.copy(capturing = true, lastError = null) }
    }

    override fun importImage() {
        if (!_state.value.importing) {
            _state.update { it.copy(importing = true, lastError = null) }
        }
    }

    override fun clearError() = _state.update { it.copy(lastError = null) }

    override fun onPermissionResult(granted: Boolean) {
        _state.update {
            it.copy(
                permissionGranted = granted,
                lastError = if (granted) null else "Camera permission is required to capture an image.",
            )
        }
        if (!granted) emitError("Camera permission is required to capture an image.")
    }

    override fun onCameraInitialized(available: Boolean, ready: Boolean) {
        _state.update { it.copy(cameraAvailable = available, cameraReady = ready) }
    }

    override fun onCaptureFailure(message: String) {
        _state.update { it.copy(capturing = false, lastError = message) }
        emitError(message)
    }

    override fun onImageSelected(uri: Uri) {
        _state.update { it.copy(importing = false, lastError = null) }
        _events.tryEmit(
            CaptureEvent.ImportSelected(
                CapturedImage(uri, CaptureSource.IMPORT, _state.value.mode),
            ),
        )
    }

    fun onCaptured(uri: Uri, displayName: String? = null) {
        _state.update { it.copy(capturing = false, lastError = null) }
        _events.tryEmit(
            CaptureEvent.ImageCaptured(
                CapturedImage(uri, CaptureSource.CAMERA, _state.value.mode, displayName),
            ),
        )
    }

    fun requestImportConsumed() = _state.update { it.copy(importing = false) }

    private fun emitError(message: String) {
        _events.tryEmit(CaptureEvent.Error(message))
    }

    class Factory(
        private val initialMode: CaptureMode = CaptureMode.WHOLE_FORM,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(CaptureViewModel::class.java))
            return CaptureViewModel(initialMode) as T
        }
    }
}
