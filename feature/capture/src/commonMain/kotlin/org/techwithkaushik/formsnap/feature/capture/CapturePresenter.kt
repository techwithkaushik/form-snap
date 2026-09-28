package org.techwithkaushik.formsnap.feature.capture

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class CapturePresenter(initialMode: CaptureMode = CaptureMode.WHOLE_FORM) {
    private val _state = MutableStateFlow(CaptureUiState(mode = initialMode))
    val state: StateFlow<CaptureUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<CaptureEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<CaptureEvent> = _events.asSharedFlow()

    fun setMode(value: CaptureMode) = _state.update { it.copy(mode = value, lastError = null) }

    fun setLens(value: CameraLens) = _state.update { it.copy(lens = value, lastError = null) }

    fun setGrid(value: CaptureGridState) = _state.update { it.copy(grid = value) }

    fun setFlashEnabled(value: Boolean) = _state.update { it.copy(flashEnabled = value) }

    fun onPermissionResult(granted: Boolean) {
        val message = if (granted) null else "Camera permission is required to capture an image."
        _state.update {
            it.copy(
                permissionGranted = granted,
                lastError = message,
            )
        }
        if (message != null) _events.tryEmit(CaptureEvent.Error(message))
    }

    fun onCameraInitialized(available: Boolean, ready: Boolean) {
        _state.update { it.copy(cameraAvailable = available, cameraReady = ready) }
    }

    fun beginCapture(): Boolean {
        val current = _state.value
        if (!current.permissionGranted) {
            _events.tryEmit(CaptureEvent.CameraPermissionRequired)
            return false
        }
        if (!current.cameraAvailable || !current.cameraReady) {
            emitError("Camera is not ready.")
            return false
        }
        if (current.capturing) return false
        _state.update { it.copy(capturing = true, lastError = null) }
        return true
    }

    fun beginImport(): Boolean {
        if (_state.value.importing) return false
        _state.update { it.copy(importing = true, lastError = null) }
        return true
    }

    fun onCaptured(uri: String, displayName: String?) {
        _state.update { it.copy(capturing = false, lastError = null) }
        _events.tryEmit(
            CaptureEvent.ImageCaptured(
                CapturedImage(uri, CaptureSource.CAMERA, _state.value.mode, displayName),
            ),
        )
    }

    fun onImageSelected(uri: String, displayName: String? = null) {
        _state.update { it.copy(importing = false, lastError = null) }
        _events.tryEmit(
            CaptureEvent.ImportSelected(
                CapturedImage(uri, CaptureSource.IMPORT, _state.value.mode, displayName),
            ),
        )
    }

    fun cancelImport() = _state.update { it.copy(importing = false) }

    fun onCaptureFailure(message: String) {
        _state.update { it.copy(capturing = false, lastError = message) }
        emitError(message)
    }

    fun clearError() = _state.update { it.copy(lastError = null) }

    private fun emitError(message: String) {
        _events.tryEmit(CaptureEvent.Error(message))
    }
}
