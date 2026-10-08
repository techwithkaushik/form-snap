package org.techwithkaushik.formsnap.feature.capture

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun CaptureScreen(
    cameraPreview: @Composable () -> Unit,
    controller: CapturePresenter,
    onRequestCameraPermission: () -> Unit,
    onCapture: () -> Unit,
    onImport: () -> Unit,
    onToggleLens: () -> Unit,
    onToggleFlash: () -> Unit,
    onImageCaptured: (CapturedImage) -> Unit,
    onImportImage: (CapturedImage) -> Unit = onImageCaptured,
    onError: (String) -> Unit = {},
    liveDetections: List<LiveDetection> = emptyList(),
    modifier: Modifier = Modifier,
) {
    val state by controller.state.collectAsState()

    LaunchedEffect(controller) {
        controller.events.collect { event ->
            when (event) {
                CaptureEvent.CameraPermissionRequired -> onRequestCameraPermission()
                is CaptureEvent.ImageCaptured -> onImageCaptured(event.image)
                is CaptureEvent.ImportSelected -> onImportImage(event.image)
                is CaptureEvent.Error -> onError(event.message)
            }
        }
    }

    Box(
        modifier = modifier.fillMaxSize().background(Color.Black),
    ) {
        cameraPreview()
        CaptureOverlay(Modifier.fillMaxSize(), state.grid)
        LiveDetectionOverlay(Modifier.fillMaxSize(), liveDetections)

        Column(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CaptureMode.entries.forEach { item ->
                    FilterChip(
                        selected = state.mode == item,
                        onClick = { controller.setMode(item) },
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

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.48f),
                    onClick = onToggleLens,
                ) {
                    Text("↔", color = Color.White, modifier = Modifier.padding(14.dp))
                }

                Surface(
                    shape = CircleShape,
                    color = Color.White,
                    shadowElevation = 8.dp,
                    onClick = onCapture,
                    enabled = !state.capturing,
                ) {
                    Box(modifier = Modifier.padding(10.dp), contentAlignment = Alignment.Center) {
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(Color.Black)
                                .padding(28.dp),
                        )
                    }
                }

                Surface(
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.48f),
                    onClick = onToggleFlash,
                ) {
                    Text(
                        if (state.flashEnabled) "⚡" else "◌",
                        color = Color.White,
                        modifier = Modifier.padding(14.dp),
                    )
                }

                Surface(
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.48f),
                    onClick = onImport,
                ) {
                    Text("▣", color = Color.White, modifier = Modifier.padding(14.dp))
                }
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
            Surface(
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 20.dp),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Text(
                    text = error,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
    }
}

@Composable
@Composable
private fun LiveDetectionOverlay(
    modifier: Modifier,
    detections: List<LiveDetection>,
) {
    if (detections.isEmpty()) return
    Canvas(modifier) {
        detections.forEach { detection ->
            val left = detection.left.coerceIn(0f, 1f) * size.width
            val top = detection.top.coerceIn(0f, 1f) * size.height
            val right = detection.right.coerceIn(0f, 1f) * size.width
            val bottom = detection.bottom.coerceIn(0f, 1f) * size.height
            drawRect(
                color = if (detection.label == "Signature") Color(0xFFFFC107) else Color(0xFF00E676),
                topLeft = androidx.compose.ui.geometry.Offset(left, top),
                size = androidx.compose.ui.geometry.Size((right - left).coerceAtLeast(1f), (bottom - top).coerceAtLeast(1f)),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4f),
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
    Canvas(modifier) {
        val strokeWidth = (size.minDimension * 0.0022f).coerceIn(1.5f, 4f)
        val lineColor = Color.White.copy(alpha = 0.52f)
        for (i in 1..state.verticalDivisions) {
            val x = size.width * i / (state.verticalDivisions + 1f)
            drawLine(
                lineColor,
                androidx.compose.ui.geometry.Offset(x, 0f),
                androidx.compose.ui.geometry.Offset(x, size.height),
                strokeWidth,
            )
        }
        for (i in 1..state.horizontalDivisions) {
            val y = size.height * i / (state.horizontalDivisions + 1f)
            drawLine(
                lineColor,
                androidx.compose.ui.geometry.Offset(0f, y),
                androidx.compose.ui.geometry.Offset(size.width, y),
                strokeWidth,
            )
        }
    }
}
