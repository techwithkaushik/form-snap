package org.techwithkaushik.formsnap.feature.capture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.viewinterop.AndroidView

@Composable
fun CaptureScreen(
    modifier: Modifier = Modifier,
    initialMode: CaptureMode = CaptureMode.WHOLE_FORM,
    onImageCaptured: (CapturedImage) -> Unit,
    onImportImage: (CapturedImage) -> Unit = onImageCaptured,
    onError: (String) -> Unit = {},
    controller: CaptureViewModel = viewModel(
        factory = CaptureViewModel.Factory(initialMode),
    ),
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state by controller.state.collectAsState()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { controller.onPermissionResult(it) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        controller.requestImportConsumed()
        if (uri != null) controller.onImageSelected(uri)
    }

    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    val cameraController = remember(context, controller) {
        CameraCaptureController(context, controller)
    }

    LaunchedEffect(controller) {
        controller.events.collect { event ->
            when (event) {
                CaptureEvent.CameraPermissionRequired ->
                    permissionLauncher.launch(Manifest.permission.CAMERA)
                is CaptureEvent.ImageCaptured -> onImageCaptured(event.image)
                is CaptureEvent.ImportSelected -> onImportImage(event.image)
                is CaptureEvent.Error -> onError(event.message)
            }
        }
    }

    LaunchedEffect(previewView, state.permissionGranted) {
        val view = previewView ?: return@LaunchedEffect
        if (state.permissionGranted) {
            cameraController.bind(view, lifecycleOwner)
        }
    }

    LaunchedEffect(state.lens) {
        val view = previewView ?: return@LaunchedEffect
        if (state.permissionGranted) {
            cameraController.updateLens(view, lifecycleOwner)
        }
    }

    LaunchedEffect(state.flashEnabled) {
        cameraController.updateFlash(state.flashEnabled)
    }

    DisposableEffect(cameraController) {
        onDispose { cameraController.shutdown() }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PreviewView(ctx).apply {
                    implementationMode = PreviewView.ImplementationMode.PERFORMANCE
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    previewView = this
                }
            },
        )

        CaptureOverlay(
            modifier = Modifier.fillMaxSize(),
            state = state.grid,
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CaptureModePicker(state.mode, controller::setMode)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RoundIconButton(
                    onClick = {
                        controller.setLens(
                            if (state.lens == CameraLens.BACK) {
                                CameraLens.FRONT
                            } else CameraLens.BACK,
                        )
                    },
                    icon = {
                        Icon(
                            Icons.Default.Cameraswitch,
                            "Switch camera",
                            tint = Color.White,
                        )
                    },
                )

                Surface(
                    modifier = Modifier.size(78.dp),
                    shape = CircleShape,
                    color = Color.White,
                    shadowElevation = 8.dp,
                    onClick = {
                        val granted = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.CAMERA,
                        ) == PackageManager.PERMISSION_GRANTED
                        controller.onPermissionResult(granted)
                        if (granted) {
                            cameraController.takePicture(
                                context.cacheDir.resolve("captures"),
                            )
                        } else {
                            controller.capture()
                        }
                    },
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Box(
                            modifier = Modifier
                                .size(62.dp)
                                .clip(CircleShape)
                                .background(Color.Black),
                        )
                    }
                }

                RoundIconButton(
                    onClick = {
                        controller.setFlashEnabled(!state.flashEnabled)
                    },
                    icon = {
                        Icon(
                            if (state.flashEnabled) Icons.Default.FlashOn
                            else Icons.Default.FlashOff,
                            "Toggle flash",
                            tint = Color.White,
                        )
                    },
                )

                RoundIconButton(
                    onClick = {
                        controller.importImage()
                        importLauncher.launch(arrayOf("image/jpeg", "image/png", "image/webp"))
                    },
                    icon = {
                        Icon(
                            Icons.Default.PhotoLibrary,
                            "Import image",
                            tint = Color.White,
                        )
                    },
                )
            }

            if (state.capturing) {
                Text(
                    "Capturing…",
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }

        state.lastError?.let { error ->
            AssistChip(
                onClick = controller::clearError,
                label = { Text(error) },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 22.dp),
            )
        }
    }
}

@Composable
private fun RoundIconButton(
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.48f)),
    ) {
        icon()
    }
}

@Composable
private fun CaptureModePicker(
    mode: CaptureMode,
    onModeSelected: (CaptureMode) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CaptureMode.entries.forEach { item ->
            FilterChip(
                selected = item == mode,
                onClick = { onModeSelected(item) },
                label = {
                    Text(
                        when (item) {
                            CaptureMode.WHOLE_FORM -> "Form"
                            CaptureMode.PHOTO -> "Photo"
                            CaptureMode.SIGNATURE -> "Sign"
                        },
                    )
                },
            )
        }
    }
}

@Composable
fun CaptureOverlay(
    modifier: Modifier = Modifier,
    state: CaptureGridState = CaptureGridState(),
) {
    if (!state.enabled) return

    Canvas(modifier = modifier) {
        val strokeWidth = (size.minDimension * 0.0022f).coerceIn(1.5f, 4f)
        val lineColor = Color.White.copy(alpha = 0.52f)

        for (i in 1..state.verticalDivisions) {
            val x = size.width * i / (state.verticalDivisions + 1f)
            drawLine(
                color = lineColor,
                start = androidx.compose.ui.geometry.Offset(x, 0f),
                end = androidx.compose.ui.geometry.Offset(x, size.height),
                strokeWidth = strokeWidth,
            )
        }

        for (i in 1..state.horizontalDivisions) {
            val y = size.height * i / (state.horizontalDivisions + 1f)
            drawLine(
                color = lineColor,
                start = androidx.compose.ui.geometry.Offset(0f, y),
                end = androidx.compose.ui.geometry.Offset(size.width, y),
                strokeWidth = strokeWidth,
            )
        }
    }
}
