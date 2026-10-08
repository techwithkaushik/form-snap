package org.techwithkaushik.formsnap.feature.capture

enum class CaptureMode { WHOLE_FORM, PHOTO, SIGNATURE }
enum class CaptureSource { CAMERA, IMPORT }
enum class CameraLens { BACK, FRONT }

data class LiveDetection(
    val label: String,
    val confidence: Float,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val id: Long = 0L,
    val selectionIndex: Int? = null,
    val locked: Boolean = false,
)

data class CaptureGridState(
    val enabled: Boolean = true,
    val horizontalDivisions: Int = 3,
    val verticalDivisions: Int = 3,
)

data class CapturedImage(
    val uri: String,
    val source: CaptureSource,
    val mode: CaptureMode,
    val displayName: String? = null,
)

data class CaptureRequest(
    val mode: CaptureMode,
    val source: CaptureSource,
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
    val permissionGranted: Boolean = false,
    val capturing: Boolean = false,
    val importing: Boolean = false,
    val flashEnabled: Boolean = false,
    val lastError: String? = null,
    val liveAiAvailable: Boolean = false,
)
