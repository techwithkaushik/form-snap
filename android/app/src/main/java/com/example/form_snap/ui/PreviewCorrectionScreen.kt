package org.techwithkaushik.formSnap.ui

import android.graphics.Bitmap
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import org.techwithkaushik.formSnap.pipeline.AppearanceAdjustments
import org.techwithkaushik.formSnap.pipeline.DetectionKind
import org.techwithkaushik.formSnap.pipeline.PreviewCorrectionState
import kotlin.math.abs

private enum class DragMode {
    MOVE,
    RESIZE_LEFT,
    RESIZE_TOP,
    RESIZE_RIGHT,
    RESIZE_BOTTOM,
    RESIZE_TOP_LEFT,
    RESIZE_TOP_RIGHT,
    RESIZE_BOTTOM_LEFT,
    RESIZE_BOTTOM_RIGHT,
}

@Composable
fun PreviewCorrectionScreen(
    state: PreviewCorrectionState,
    source: Bitmap?,
    resultPreview: Bitmap?,
    onBoundsChange: (RectF) -> Unit,
    onAppearanceChange: (AppearanceAdjustments) -> Unit,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text(if (state.kind == DetectionKind.PHOTO) "Photo correction" else "Signature correction") }
        source?.let { bitmap ->
            item {
                CropEditor(
                    bitmap = bitmap,
                    bounds = state.currentBounds,
                    sourceWidth = state.sourceWidth,
                    sourceHeight = state.sourceHeight,
                    onBoundsChange = onBoundsChange,
                )
            }
        }
        resultPreview?.let { bitmap ->
            item {
                Text("Result preview")
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth().aspectRatio(bitmap.width.toFloat() / bitmap.height.toFloat()),
                    contentScale = ContentScale.Fit,
                )
            }
        }
        item {
            Text("Brightness ${state.appearance.brightness.asDisplay()}")
            Slider(value = state.appearance.brightness, onValueChange = { onAppearanceChange(state.appearance.copy(brightness = it)) }, valueRange = -0.5f..0.5f)
        }
        item {
            Text("Contrast ${state.appearance.contrast.asDisplay()}")
            Slider(value = state.appearance.contrast, onValueChange = { onAppearanceChange(state.appearance.copy(contrast = it)) }, valueRange = 0.7f..1.5f)
        }
        if (state.kind == DetectionKind.PHOTO) {
            item {
                Text("Saturation ${state.appearance.saturation.asDisplay()}")
                Slider(value = state.appearance.saturation, onValueChange = { onAppearanceChange(state.appearance.copy(saturation = it)) }, valueRange = 0.5f..1.5f)
            }
        } else {
            item {
                Text("Ink threshold ${state.appearance.inkThreshold}")
                Slider(value = state.appearance.inkThreshold.toFloat(), onValueChange = { onAppearanceChange(state.appearance.copy(inkThreshold = it.toInt())) }, valueRange = 80f..220f, steps = 13)
            }
        }
        item {
            Text("Sharpness ${state.appearance.sharpness.asDisplay()}")
            Slider(value = state.appearance.sharpness, onValueChange = { onAppearanceChange(state.appearance.copy(sharpness = it)) }, valueRange = 0f..1f)
        }
        item {
            Text("Denoise ${state.appearance.denoise.asDisplay()}")
            Slider(value = state.appearance.denoise, onValueChange = { onAppearanceChange(state.appearance.copy(denoise = it)) }, valueRange = 0f..1f)
        }
        item {
            Text("Background cleanup ${state.appearance.backgroundCleanup.asDisplay()}")
            Slider(value = state.appearance.backgroundCleanup, onValueChange = { onAppearanceChange(state.appearance.copy(backgroundCleanup = it)) }, valueRange = 0f..1f)
        }
        item { Text("Crop: ${state.currentBounds.left.toInt()}, ${state.currentBounds.top.toInt()} → ${state.currentBounds.right.toInt()}, ${state.currentBounds.bottom.toInt()}") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onReset) { Text("Reset") }
                OutlinedButton(onClick = onReject) { Text("Reject") }
                Button(onClick = onAccept) { Text("Accept") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun CropEditor(
    bitmap: Bitmap,
    bounds: RectF,
    sourceWidth: Int,
    sourceHeight: Int,
    onBoundsChange: (RectF) -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(bitmap.width.toFloat() / bitmap.height.toFloat())
            .background(Color.Black),
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds,
        )
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(sourceWidth, sourceHeight, bitmap.width, bitmap.height) {
                    var dragMode: DragMode? = null
                    var startBounds = RectF(bounds)
                    var startSourceX = 0f
                    var startSourceY = 0f

                    detectDragGestures(
                        onDragStart = { offset ->
                            val sx = sourceWidth.toFloat() / size.width.coerceAtLeast(1).toFloat()
                            val sy = sourceHeight.toFloat() / size.height.coerceAtLeast(1).toFloat()
                            val x = offset.x * sx
                            val y = offset.y * sy
                            val edge = (minOf(bounds.width(), bounds.height()) * 0.22f).coerceIn(35f, 160f)

                            val nearLeft = abs(x - bounds.left) <= edge
                            val nearRight = abs(x - bounds.right) <= edge
                            val nearTop = abs(y - bounds.top) <= edge
                            val nearBottom = abs(y - bounds.bottom) <= edge

                            dragMode = when {
                                nearLeft && nearTop -> DragMode.RESIZE_TOP_LEFT
                                nearRight && nearTop -> DragMode.RESIZE_TOP_RIGHT
                                nearLeft && nearBottom -> DragMode.RESIZE_BOTTOM_LEFT
                                nearRight && nearBottom -> DragMode.RESIZE_BOTTOM_RIGHT
                                nearLeft -> DragMode.RESIZE_LEFT
                                nearRight -> DragMode.RESIZE_RIGHT
                                nearTop -> DragMode.RESIZE_TOP
                                nearBottom -> DragMode.RESIZE_BOTTOM
                                bounds.contains(x, y) -> DragMode.MOVE
                                else -> null
                            }
                            startBounds = RectF(bounds)
                            startSourceX = x
                            startSourceY = y
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            val mode = dragMode ?: return@detectDragGestures
                            val sx = sourceWidth.toFloat() / size.width.coerceAtLeast(1).toFloat()
                            val sy = sourceHeight.toFloat() / size.height.coerceAtLeast(1).toFloat()
                            val x = change.position.x * sx
                            val y = change.position.y * sy
                            val dx = x - startSourceX
                            val dy = y - startSourceY
                            val next = RectF(startBounds)
                            val minW = (sourceWidth * 0.05f).coerceAtLeast(24f)
                            val minH = (sourceHeight * 0.05f).coerceAtLeast(24f)

                            when (mode) {
                                DragMode.MOVE -> {
                                    val maxLeft = (sourceWidth.toFloat() - next.width()).coerceAtLeast(0f)
                                    val maxTop = (sourceHeight.toFloat() - next.height()).coerceAtLeast(0f)
                                    next.offsetTo(
                                        (startBounds.left + dx).coerceIn(0f, maxLeft),
                                        (startBounds.top + dy).coerceIn(0f, maxTop),
                                    )
                                }
                                DragMode.RESIZE_LEFT ->
                                    next.left = (startBounds.left + dx).coerceIn(0f, startBounds.right - minW)
                                DragMode.RESIZE_RIGHT ->
                                    next.right = (startBounds.right + dx).coerceIn(startBounds.left + minW, sourceWidth.toFloat())
                                DragMode.RESIZE_TOP ->
                                    next.top = (startBounds.top + dy).coerceIn(0f, startBounds.bottom - minH)
                                DragMode.RESIZE_BOTTOM ->
                                    next.bottom = (startBounds.bottom + dy).coerceIn(startBounds.top + minH, sourceHeight.toFloat())
                                DragMode.RESIZE_TOP_LEFT -> {
                                    next.left = (startBounds.left + dx).coerceIn(0f, startBounds.right - minW)
                                    next.top = (startBounds.top + dy).coerceIn(0f, startBounds.bottom - minH)
                                }
                                DragMode.RESIZE_TOP_RIGHT -> {
                                    next.right = (startBounds.right + dx).coerceIn(startBounds.left + minW, sourceWidth.toFloat())
                                    next.top = (startBounds.top + dy).coerceIn(0f, startBounds.bottom - minH)
                                }
                                DragMode.RESIZE_BOTTOM_LEFT -> {
                                    next.left = (startBounds.left + dx).coerceIn(0f, startBounds.right - minW)
                                    next.bottom = (startBounds.bottom + dy).coerceIn(startBounds.top + minH, sourceHeight.toFloat())
                                }
                                DragMode.RESIZE_BOTTOM_RIGHT -> {
                                    next.right = (startBounds.right + dx).coerceIn(startBounds.left + minW, sourceWidth.toFloat())
                                    next.bottom = (startBounds.bottom + dy).coerceIn(startBounds.top + minH, sourceHeight.toFloat())
                                }
                            }
                            onBoundsChange(next)
                        },
                        onDragEnd = { dragMode = null },
                        onDragCancel = { dragMode = null },
                    )
                },
        ) {
            val sx = size.width / sourceWidth.toFloat()
            val sy = size.height / sourceHeight.toFloat()
            val left = bounds.left * sx
            val top = bounds.top * sy
            val width = bounds.width() * sx
            val height = bounds.height() * sy

            drawRect(
                color = Color.White,
                topLeft = Offset(left, top),
                size = Size(width, height),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4f),
            )
            val handle = 18f
            listOf(
                Offset(left, top),
                Offset(left + width, top),
                Offset(left, top + height),
                Offset(left + width, top + height),
            ).forEach { drawCircle(color = Color.White, radius = handle, center = it) }
        }
    }
}
private fun Float.asDisplay(): String = "%.2f".format(this)
