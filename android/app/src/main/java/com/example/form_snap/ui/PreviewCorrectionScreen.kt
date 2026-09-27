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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
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

private enum class DragMode { MOVE, RESIZE }

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
    Column(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(if (state.kind == DetectionKind.PHOTO) "Photo correction" else "Signature correction")

        preview?.let { bitmap ->
            CropEditor(
                bitmap = bitmap,
                bounds = state.currentBounds,
                sourceWidth = state.sourceWidth,
                sourceHeight = state.sourceHeight,
                onBoundsChange = onBoundsChange,
            )
        }

        Text("Brightness ${state.appearance.brightness.asDisplay()}")
        Slider(
            value = state.appearance.brightness,
            onValueChange = { onAppearanceChange(state.appearance.copy(brightness = it)) },
            valueRange = -0.5f..0.5f,
        )
        Text("Contrast ${state.appearance.contrast.asDisplay()}")
        Slider(
            value = state.appearance.contrast,
            onValueChange = { onAppearanceChange(state.appearance.copy(contrast = it)) },
            valueRange = 0.7f..1.5f,
        )
        if (state.kind == DetectionKind.PHOTO) {
            Text("Saturation ${state.appearance.saturation.asDisplay()}")
            Slider(
                value = state.appearance.saturation,
                onValueChange = { onAppearanceChange(state.appearance.copy(saturation = it)) },
                valueRange = 0.5f..1.5f,
            )
        } else {
            Text("Ink threshold ${state.appearance.inkThreshold}")
            Slider(
                value = state.appearance.inkThreshold.toFloat(),
                onValueChange = { onAppearanceChange(state.appearance.copy(inkThreshold = it.toInt())) },
                valueRange = 80f..220f,
                steps = 13,
            )
        }
        Text("Sharpness ${state.appearance.sharpness.asDisplay()}")
        Slider(
            value = state.appearance.sharpness,
            onValueChange = { onAppearanceChange(state.appearance.copy(sharpness = it)) },
            valueRange = 0f..1f,
        )
        Text("Denoise ${state.appearance.denoise.asDisplay()}")
        Slider(
            value = state.appearance.denoise,
            onValueChange = { onAppearanceChange(state.appearance.copy(denoise = it)) },
            valueRange = 0f..1f,
        )
        Text("Background cleanup ${state.appearance.backgroundCleanup.asDisplay()}")
        Slider(
            value = state.appearance.backgroundCleanup,
            onValueChange = {
                onAppearanceChange(state.appearance.copy(backgroundCleanup = it))
            },
            valueRange = 0f..1f,
        )
        Text(
            "Crop: " +
                "${state.currentBounds.left.toInt()}, ${state.currentBounds.top.toInt()} → " +
                "${state.currentBounds.right.toInt()}, ${state.currentBounds.bottom.toInt()}",
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = state.dirty, onClick = onReset, label = { Text("Reset") })
            OutlinedButton(onClick = onReject) { Text("Reject") }
            Button(onClick = onAccept) { Text("Accept") }
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
    var boxWidthPx = 0
    var boxHeightPx = 0

    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(bitmap.width.toFloat() / bitmap.height.toFloat())
            .background(Color.Black)
            .clipToBounds()
            .onSizeChanged {
                boxWidthPx = it.width
                boxHeightPx = it.height
            },
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.fillMaxWidth(),
            contentScale = ContentScale.FillBounds,
        )
        Canvas(
            Modifier
                .matchParentSize()
                .pointerInput(bounds, boxWidthPx, boxHeightPx) {
                    detectDragGestures(
                        onDrag = { change, dragAmount ->
                            change.consume()
                            if (boxWidthPx <= 0 || boxHeightPx <= 0) return@detectDragGestures
                            val dx = dragAmount.x * sourceWidth / boxWidthPx
                            val dy = dragAmount.y * sourceHeight / boxHeightPx
                            val edge = 36f

                            val touchX = change.position.x * sourceWidth / boxWidthPx
                            val touchY = change.position.y * sourceHeight / boxHeightPx
                            val nearRight = kotlin.math.abs(touchX - bounds.right) < edge
                            val nearBottom = kotlin.math.abs(touchY - bounds.bottom) < edge
                            val nearLeft = kotlin.math.abs(touchX - bounds.left) < edge
                            val nearTop = kotlin.math.abs(touchY - bounds.top) < edge

                            val next = RectF(bounds)
                            when {
                                nearRight && nearBottom -> {
                                    next.right += dx
                                    next.bottom += dy
                                }
                                nearLeft && nearTop -> {
                                    next.left += dx
                                    next.top += dy
                                }
                                nearRight -> next.right += dx
                                nearLeft -> next.left += dx
                                nearBottom -> next.bottom += dy
                                nearTop -> next.top += dy
                                else -> {
                                    next.left += dx
                                    next.right += dx
                                    next.top += dy
                                    next.bottom += dy
                                }
                            }
                            val minSize = 8f
                            if (next.width() >= minSize && next.height() >= minSize) onBoundsChange(next)
                        },
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
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f),
            )
        }
    }
}

private fun Float.asDisplay(): String = "%.2f".format(this)
