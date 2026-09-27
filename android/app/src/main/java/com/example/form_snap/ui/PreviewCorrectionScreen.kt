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
    var viewWidth = 0f
    var viewHeight = 0f
    Box(
        Modifier.fillMaxWidth().aspectRatio(bitmap.width.toFloat() / bitmap.height.toFloat()).background(Color.Black).onSizeChanged {
            viewWidth = it.width.toFloat()
            viewHeight = it.height.toFloat()
        },
    ) {
        Image(bitmap = bitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        Canvas(
            Modifier.fillMaxSize().pointerInput(bounds, viewWidth, viewHeight, sourceWidth, sourceHeight) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    if (viewWidth <= 1f || viewHeight <= 1f) return@detectDragGestures
                    val sx = sourceWidth.toFloat() / viewWidth
                    val sy = sourceHeight.toFloat() / viewHeight
                    val touchX = change.position.x * sx
                    val touchY = change.position.y * sy
                    val dx = dragAmount.x * sx
                    val dy = dragAmount.y * sy
                    val edge = (minOf(sourceWidth, sourceHeight) * 0.07f).coerceIn(24f, 90f)
                    val mode = when {
                        abs(touchX - bounds.left) <= edge && abs(touchY - bounds.top) <= edge -> DragMode.RESIZE_TOP_LEFT
                        abs(touchX - bounds.right) <= edge && abs(touchY - bounds.top) <= edge -> DragMode.RESIZE_TOP_RIGHT
                        abs(touchX - bounds.left) <= edge && abs(touchY - bounds.bottom) <= edge -> DragMode.RESIZE_BOTTOM_LEFT
                        abs(touchX - bounds.right) <= edge && abs(touchY - bounds.bottom) <= edge -> DragMode.RESIZE_BOTTOM_RIGHT
                        abs(touchX - bounds.left) <= edge -> DragMode.RESIZE_LEFT
                        abs(touchX - bounds.right) <= edge -> DragMode.RESIZE_RIGHT
                        abs(touchY - bounds.top) <= edge -> DragMode.RESIZE_TOP
                        abs(touchY - bounds.bottom) <= edge -> DragMode.RESIZE_BOTTOM
                        bounds.contains(touchX, touchY) -> DragMode.MOVE
                        else -> null
                    } ?: return@detectDragGestures
                    val next = RectF(bounds)
                    val minW = (sourceWidth * 0.05f).coerceAtLeast(20f)
                    val minH = (sourceHeight * 0.05f).coerceAtLeast(20f)
                    when (mode) {
                        DragMode.MOVE -> next.offsetTo((next.left + dx).coerceIn(0f, (sourceWidth - next.width()).coerceAtLeast(0f)), (next.top + dy).coerceIn(0f, (sourceHeight - next.height()).coerceAtLeast(0f)))
                        DragMode.RESIZE_LEFT -> next.left = (next.left + dx).coerceIn(0f, next.right - minW)
                        DragMode.RESIZE_RIGHT -> next.right = (next.right + dx).coerceIn(next.left + minW, sourceWidth.toFloat())
                        DragMode.RESIZE_TOP -> next.top = (next.top + dy).coerceIn(0f, next.bottom - minH)
                        DragMode.RESIZE_BOTTOM -> next.bottom = (next.bottom + dy).coerceIn(next.top + minH, sourceHeight.toFloat())
                        DragMode.RESIZE_TOP_LEFT -> { next.left = (next.left + dx).coerceIn(0f, next.right - minW); next.top = (next.top + dy).coerceIn(0f, next.bottom - minH) }
                        DragMode.RESIZE_TOP_RIGHT -> { next.right = (next.right + dx).coerceIn(next.left + minW, sourceWidth.toFloat()); next.top = (next.top + dy).coerceIn(0f, next.bottom - minH) }
                        DragMode.RESIZE_BOTTOM_LEFT -> { next.left = (next.left + dx).coerceIn(0f, next.right - minW); next.bottom = (next.bottom + dy).coerceIn(next.top + minH, sourceHeight.toFloat()) }
                        DragMode.RESIZE_BOTTOM_RIGHT -> { next.right = (next.right + dx).coerceIn(next.left + minW, sourceWidth.toFloat()); next.bottom = (next.bottom + dy).coerceIn(next.top + minH, sourceHeight.toFloat()) }
                    }
                    onBoundsChange(next)
                }
            },
        ) {
            if (viewWidth > 1f && viewHeight > 1f) {
                val sx = viewWidth / sourceWidth.toFloat()
                val sy = viewHeight / sourceHeight.toFloat()
                drawRect(Color.White, Offset(bounds.left * sx, bounds.top * sy), Size(bounds.width() * sx, bounds.height() * sy), androidx.compose.ui.graphics.drawscope.Stroke(3f))
            }
        }
    }
}

private fun Float.asDisplay(): String = "%.2f".format(this)
