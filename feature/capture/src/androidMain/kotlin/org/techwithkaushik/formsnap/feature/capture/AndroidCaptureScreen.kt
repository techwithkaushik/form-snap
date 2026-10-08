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
    onImageCaptured: (CapturedImage) -> Unit,
    onImportImage: (CapturedImage) -> Unit = onImageCaptured,
    onError: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val presenter = remember(initialMode) { CapturePresenter(initialMode) }
    val camera = remember(presenter, context) { AndroidCameraCapture(context, presenter) }
    val session = remember(context) { CaptureSession.create(context) }
    var liveDetections by remember { mutableStateOf<List<LiveDetection>>(emptyList()) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { presenter.onPermissionResult(it) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) presenter.cancelImport()
        else presenter.onImageSelected(uri.toString())
    }

    DisposableEffect(Unit) {
        camera.setLiveDetectionListener { detections -> liveDetections = detections }
        onDispose {
            camera.shutdown()
            session.close()
        }
    }

    LaunchedEffect(Unit) {
        presenter.events.collect { event ->
            when (event) {
                CaptureEvent.CameraPermissionRequired ->
                    permissionLauncher.launch(Manifest.permission.CAMERA)
                is CaptureEvent.ImageCaptured -> onImageCaptured(event.image)
                is CaptureEvent.ImportSelected -> onImportImage(event.image)
                is CaptureEvent.Error -> onError(event.message)
            }
        }
    }

    CaptureScreen(
        modifier = modifier,
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
                    if (camera.hasCameraPermission()) {
                        presenter.onPermissionResult(true)
                        camera.bind(preview, owner)
                    }
                },
            )
        },
        controller = presenter,
        onRequestCameraPermission = {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        },
        onCapture = {
            if (!camera.hasCameraPermission()) {
                permissionLauncher.launch(Manifest.permission.CAMERA)
            } else {
                presenter.onPermissionResult(true)
                camera.capture(session)
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
        onImageCaptured = onImageCaptured,
        onImportImage = onImportImage,
        onError = onError,
    )
}
