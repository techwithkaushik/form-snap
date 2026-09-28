package org.techwithkaushik.formsnap.feature.capture

import android.graphics.RectF
import android.net.Uri

enum class CaptureMode { WHOLE_FORM, PHOTO, SIGNATURE }
enum class CaptureSource { CAMERA, IMPORT }
enum class CameraLens { BACK, FRONT }

data class CaptureGridState(
    val enabled: Boolean = true,
    val horizontalDivisions: Int = 3,
    val verticalDivisions: Int = 3,
) {
    init {
        require(horizontalDivisions >= 1)
        require(verticalDivisions >= 1)
    }
}

data class CaptureRequest(
    val mode: CaptureMode,
    val source: CaptureSource,
)

data class CapturedImage(
    val uri: Uri,
    val source: CaptureSource,
    val mode: CaptureMode,
    val displayName: String? = null,
)

data class CaptureFrame(
    val bounds: RectF,
    val rotationDegrees: Int = 0,
)

sealed interface CaptureEvent {
    data class ImageCaptured(val image: CapturedImage) : CaptureEvent
    data class ImportSelected(val image: CapturedImage) : CaptureEvent
    data object CameraPermissionRequired : CaptureEvent
    data class Error(val message: String) : CaptureEvent
}

data class CaptureUiState(
    val mode: CaptureMode = CaptureMode.WHOLE_FORM,
    val lens: CameraLens = CameraLens.BACK,
    val grid: CaptureGridState = CaptureGridState(),
    val cameraAvailable: Boolean = false,
    val cameraReady: Boolean = false,
    val capturing: Boolean = false,
    val importing: Boolean = false,
    val permissionGranted: Boolean = false,
    val flashEnabled: Boolean = false,
    val lastError: String? = null,
)
