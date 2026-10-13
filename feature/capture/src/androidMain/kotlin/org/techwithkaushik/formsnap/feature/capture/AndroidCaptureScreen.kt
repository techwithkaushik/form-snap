package org.techwithkaushik.formsnap.feature.capture

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
fun AndroidCaptureScreen(
    modifier: Modifier = Modifier,
    initialMode: CaptureMode = CaptureMode.WHOLE_FORM,
    onImageCaptured: (CapturedImage, List<LiveDetection>) -> Unit,
    onImportImage: (CapturedImage) -> Unit = { image -> onImageCaptured(image, emptyList()) },
    onError: (String) -> Unit = {},
    autoSaveEnabled: Boolean = true,
    captureProcessing: Boolean = false,
    onAutoSaveEnabledChange: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val presenter = remember(initialMode) { CapturePresenter(initialMode) }
    val camera = remember(presenter, context) { AndroidCameraCapture(context, presenter) }
    val session = remember(context) { CaptureSession.create(context) }
    var liveDetections by remember { mutableStateOf<List<LiveDetection>>(emptyList()) }
    var liveDiagnostics by remember { mutableStateOf("AI: waiting for camera frames…") }
    var pendingSelection by remember { mutableStateOf<List<LiveDetection>>(emptyList()) }
    var autoCaptureTriggered by remember { mutableStateOf(false) }
    var captureCooldown by remember { mutableStateOf(false) }
    val confidencePrefs = remember(context) { context.getSharedPreferences("formsnap.live.detection", android.content.Context.MODE_PRIVATE) }
    var confidenceThreshold by remember { mutableStateOf(confidencePrefs.getFloat("confidence_threshold", 0.35f).coerceIn(0.05f, 0.95f)) }
    var permissionGranted by remember { mutableStateOf(camera.hasCameraPermission()) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        permissionGranted = it
        presenter.onPermissionResult(it)
    }

    // Ask for camera permission when the live capture page is opened, not on Home.
    LaunchedEffect(Unit) {
        if (!camera.hasCameraPermission()) permissionLauncher.launch(Manifest.permission.CAMERA)
        else {
            permissionGranted = true
            presenter.onPermissionResult(true)
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) presenter.cancelImport()
        else presenter.onImageSelected(uri.toString())
    }

    DisposableEffect(Unit) {
        camera.setLiveDetectionListener { detections -> liveDetections = detections }
        camera.setConfidenceThreshold(confidenceThreshold)
        camera.setLiveDiagnosticsListener { message -> liveDiagnostics = message }
        onDispose {
            camera.shutdown()
            session.close()
        }
    }

    androidx.compose.runtime.LaunchedEffect(confidenceThreshold) {
        camera.setConfidenceThreshold(confidenceThreshold)
        confidencePrefs.edit().putFloat("confidence_threshold", confidenceThreshold).apply()
    }

    // After processing each still, keep the preview open and wait 4.5 seconds
    // before re-arming capture. This prevents overlapping captures while the
    // crop/save pipeline is still working.
    LaunchedEffect(captureProcessing) {
        if (captureProcessing) {
            captureCooldown = true
        } else if (captureCooldown) {
            kotlinx.coroutines.delay(4_500)
            autoCaptureTriggered = false
            captureCooldown = false
        }
    }

    // Require a detection to remain present for 1.5 seconds before taking
    // the full-resolution still. Key only on presence, not every changing box,
    // so each incoming camera frame does not restart the stabilization timer.
    val hasConfidentDetection = liveDetections.any { it.confidence >= confidenceThreshold }
    LaunchedEffect(hasConfidentDetection, autoSaveEnabled, autoCaptureTriggered, captureProcessing, captureCooldown) {
        if (autoSaveEnabled && !autoCaptureTriggered && !captureProcessing && !captureCooldown && hasConfidentDetection) {
            kotlinx.coroutines.delay(1_500)
            if (autoSaveEnabled && !autoCaptureTriggered && !captureProcessing && !captureCooldown &&
                liveDetections.any { it.confidence >= confidenceThreshold }
            ) {
                autoCaptureTriggered = true
                pendingSelection = emptyList()
                camera.capture(session)
            }
        }
    }

    LaunchedEffect(Unit) {
        presenter.events.collect { event ->
            when (event) {
                CaptureEvent.CameraPermissionRequired ->
                    permissionLauncher.launch(Manifest.permission.CAMERA)
                is CaptureEvent.ImageCaptured -> {
                    val selected = pendingSelection
                    pendingSelection = emptyList()
                    onImageCaptured(event.image, selected)
                }
                is CaptureEvent.ImportSelected -> onImportImage(event.image)
                is CaptureEvent.Error -> {
                    // A failed automatic shutter must not permanently disarm live capture.
                    autoCaptureTriggered = false
                    onError(event.message)
                }
            }
        }
    }

    androidx.compose.foundation.layout.Box(modifier = modifier.fillMaxSize()) {
    CaptureScreen(
        modifier = Modifier.fillMaxSize(),
        cameraPreview = {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = {
                    PreviewView(it).apply {
                        implementationMode = PreviewView.ImplementationMode.PERFORMANCE
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                    }
                },
                update = { preview ->
                    if (permissionGranted && camera.hasCameraPermission()) {
                        presenter.onPermissionResult(true)
                        camera.bind(preview, owner)
                    }
                },
            )
        },
        controller = presenter,
        onCapture = { selected ->
            if (captureProcessing || captureCooldown) {
                onError("Capture is processing. Live capture will be ready in a few seconds.")
            } else if (!autoSaveEnabled && selected.isEmpty()) {
                onError("Manual mode: tap one or more detection boxes, then press the shutter.")
            } else {
                pendingSelection = selected
                if (!permissionGranted || !camera.hasCameraPermission()) {
                    permissionLauncher.launch(Manifest.permission.CAMERA)
                } else {
                    presenter.onPermissionResult(true)
                    camera.capture(session)
                }
            }
        },
        onImport = {
            if (presenter.beginImport()) {
                importLauncher.launch(arrayOf("image/jpeg", "image/png", "image/webp"))
            }
        },
        onToggleLens = {
            presenter.setLens(
                if (presenter.state.value.lens == CameraLens.BACK) {
                    CameraLens.FRONT
                } else {
                    CameraLens.BACK
                },
            )
            camera.updateLens()
        },
        onToggleFlash = {
            val enabled = !presenter.state.value.flashEnabled
            presenter.setFlashEnabled(enabled)
            camera.updateFlash(enabled)
        },
        liveDetections = liveDetections,
        liveDiagnostics = liveDiagnostics,
        confidenceThreshold = confidenceThreshold,
        onConfidenceThresholdChange = { confidenceThreshold = it },
        autoSaveEnabled = autoSaveEnabled,
        onAutoSaveEnabledChange = onAutoSaveEnabledChange,
        onError = onError,
    )
    }
}
