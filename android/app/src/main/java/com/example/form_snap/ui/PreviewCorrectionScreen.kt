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
    preview: Bitmap?,
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
        preview?.let { bitmap ->
            item { CropEditor(bitmap, state.currentBounds, state.sourceWidth, state.sourceHeight, onBoundsChange) }
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
    val currentBounds by androidx.compose.runtime.rememberUpdatedState(bounds)

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
                .pointerInput(sourceWidth, sourceHeight) {
                    detectDragGestures(
                        onDrag = { change, dragAmount ->
                            change.consume()
                            val b = currentBounds
                            val w = size.width
                            val h = size.height
                            if (w <= 1f || h <= 1f) return@detectDragGestures

                            val sx = sourceWidth.toFloat() / w
                            val sy = sourceHeight.toFloat() / h
                            val x = change.position.x * sx
                            val y = change.position.y * sy
                            val dx = dragAmount.x * sx
                            val dy = dragAmount.y * sy

                            val edge = (minOf(b.width(), b.height()) * 0.18f).coerceIn(24f, 120f)
                            val nearLeft = abs(x - b.left) <= edge
                            val nearRight = abs(x - b.right) <= edge
                            val nearTop = abs(y - b.top) <= edge
                            val nearBottom = abs(y - b.bottom) <= edge
                            val inside = b.contains(x, y)

                            val next = RectF(b)
                            val minW = (sourceWidth * 0.06f).coerceAtLeast(24f)
                            val minH = (sourceHeight * 0.06f).coerceAtLeast(24f)

                            when {
                                nearLeft && nearTop -> {
                                    next.left = (next.left + dx).coerceIn(0f, next.right - minW)
                                    next.top = (next.top + dy).coerceIn(0f, next.bottom - minH)
                                }
                                nearRight && nearTop -> {
                                    next.right = (next.right + dx).coerceIn(next.left + minW, sourceWidth.toFloat())
                                    next.top = (next.top + dy).coerceIn(0f, next.bottom - minH)
                                }
                                nearLeft && nearBottom -> {
                                    next.left = (next.left + dx).coerceIn(0f, next.right - minW)
                                    next.bottom = (next.bottom + dy).coerceIn(next.top + minH, sourceHeight.toFloat())
                                }
                                nearRight && nearBottom -> {
                                    next.right = (next.right + dx).coerceIn(next.left + minW, sourceWidth.toFloat())
                                    next.bottom = (next.bottom + dy).coerceIn(next.top + minH, sourceHeight.toFloat())
                                }
                                nearLeft -> next.left = (next.left + dx).coerceIn(0f, next.right - minW)
                                nearRight -> next.right = (next.right + dx).coerceIn(next.left + minW, sourceWidth.toFloat())
                                nearTop -> next.top = (next.top + dy).coerceIn(0f, next.bottom - minH)
                                nearBottom -> next.bottom = (next.bottom + dy).coerceIn(next.top + minH, sourceHeight.toFloat())
                                inside -> {
                                    val maxLeft = (sourceWidth - next.width()).coerceAtLeast(0f)
                                    val maxTop = (sourceHeight - next.height()).coerceAtLeast(0f)
                                    next.offsetTo(
                                        (next.left + dx).coerceIn(0f, maxLeft),
                                        (next.top + dy).coerceIn(0f, maxTop),
                                    )
                                }
                            }
                            onBoundsChange(next)
                        },
                    )
                },
        ) {
            val sx = size.width / sourceWidth.toFloat()
            val sy = size.height / sourceHeight.toFloat()
            drawRect(
                color = Color.White,
                topLeft = Offset(bounds.left * sx, bounds.top * sy),
                size = Size(bounds.width() * sx, bounds.height() * sy),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f),
            )
            // Larger touch handles make edge/corner correction practical on phones.
            val handle = (minOf(bounds.width() * sx, bounds.height() * sy) * 0.08f).coerceIn(10f, 22f)
            val points = listOf(
                Offset(bounds.left * sx, bounds.top * sy),
                Offset(bounds.right * sx, bounds.top * sy),
                Offset(bounds.left * sx, bounds.bottom * sy),
                Offset(bounds.right * sx, bounds.bottom * sy),
            )
            points.forEach { p ->
                drawCircle(Color.White, handle, p)
            }
        }
    }
}

private fun Float.asDisplay(): String = "%.2f".format(this)
