package org.techwithkaushik.formSnap

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.clipToBounds
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Source-coordinate crop editor. The crop rectangle is always stored in the
 * original image's coordinate space, even though the preview bitmap is sampled.
 */
class CropCorrectionActivity : ComponentActivity() {
    private var editorBitmap: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(EXTRA_SOURCE_PATH)
        if (path.isNullOrBlank()) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        val originalWidth = bounds.outWidth
        val originalHeight = bounds.outHeight
        val sample = calculateSampleSize(originalWidth, originalHeight, 1600)
        val bitmap = BitmapFactory.decodeFile(
            path,
            BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 },
        )
        if (bitmap == null) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        editorBitmap = bitmap
        val initial = RectF(
            intent.getFloatExtra(EXTRA_LEFT, 0f),
            intent.getFloatExtra(EXTRA_TOP, 0f),
            intent.getFloatExtra(EXTRA_RIGHT, originalWidth.toFloat()),
            intent.getFloatExtra(EXTRA_BOTTOM, originalHeight.toFloat()),
        ).apply { intersect(0f, 0f, originalWidth.toFloat(), originalHeight.toFloat()) }
        setContent {
            MaterialTheme {
                CropEditorContent(
                    bitmap = bitmap,
                    sourceWidth = originalWidth,
                    sourceHeight = originalHeight,
                    initialBounds = initial,
                    onCancel = { finish() },
                    onConfirm = { crop ->
                        setResult(RESULT_OK, intent.apply {
                            putExtra(EXTRA_LEFT, crop.left)
                            putExtra(EXTRA_TOP, crop.top)
                            putExtra(EXTRA_RIGHT, crop.right)
                            putExtra(EXTRA_BOTTOM, crop.bottom)
                        })
                        finish()
                    },
                )
            }
        }
    }

    override fun onDestroy() {
        // Release the sampled preview bitmap when this editor is dismissed.
        editorBitmap?.recycle()
        editorBitmap = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_SOURCE_PATH = "formsnap.crop.source_path"
        const val EXTRA_LEFT = "formsnap.crop.left"
        const val EXTRA_TOP = "formsnap.crop.top"
        const val EXTRA_RIGHT = "formsnap.crop.right"
        const val EXTRA_BOTTOM = "formsnap.crop.bottom"
        const val EXTRA_KIND = "formsnap.crop.kind"

        private fun calculateSampleSize(width: Int, height: Int, maxDimension: Int): Int {
            var sample = 1
            while (max(width / (sample * 2), height / (sample * 2)) >= maxDimension) {
                sample *= 2
            }
            return sample
        }
    }
}

@androidx.compose.runtime.Composable
private fun CropEditorContent(
    bitmap: Bitmap,
    sourceWidth: Int,
    sourceHeight: Int,
    initialBounds: RectF,
    onCancel: () -> Unit,
    onConfirm: (RectF) -> Unit,
) {
    var crop by remember {
        mutableStateOf(RectF(initialBounds).apply {
            left = left.coerceIn(0f, sourceWidth - 1f)
            top = top.coerceIn(0f, sourceHeight - 1f)
            right = right.coerceIn(left + 1f, sourceWidth.toFloat())
            bottom = bottom.coerceIn(top + 1f, sourceHeight.toFloat())
        })
    }
    var dragMode by remember { mutableStateOf(DragMode.NONE) }

    Column(
        modifier = Modifier.fillMaxSize().background(Color(0xFF101418)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Adjust crop", color = Color.White, style = MaterialTheme.typography.titleLarge)
        Text(
            "Drag inside the box to move it. Drag an edge or corner to resize. Changes use original-image coordinates.",
            color = Color(0xFFCBD5E1),
            fontSize = 13.sp,
        )
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
            Canvas(
                Modifier.fillMaxSize().pointerInput(bitmap, sourceWidth, sourceHeight) {
                    fun geometry(): Geometry {
                        val fit = min(size.width / bitmap.width, size.height / bitmap.height)
                        val width = bitmap.width * fit
                        val height = bitmap.height * fit
                        val left = (size.width - width) / 2f
                        val top = (size.height - height) / 2f
                        return Geometry(
                            left, top, fit,
                            bitmap.width.toFloat() / sourceWidth * fit,
                            bitmap.height.toFloat() / sourceHeight * fit,
                            width, height,
                        )
                    }
                    fun toSource(point: Offset, g: Geometry): Offset = Offset(
                        ((point.x - g.left) / g.sourceScaleX).coerceIn(0f, sourceWidth.toFloat()),
                        ((point.y - g.top) / g.sourceScaleY).coerceIn(0f, sourceHeight.toFloat()),
                    )
                    detectDragGestures(
                        onDragStart = { point ->
                            val g = geometry()
                            val p = toSource(point, g)
                            val tolerance = 28.dp.toPx()
                            val sx = g.sourceScaleX
                            val sy = g.sourceScaleY
                            val dxL = abs(p.x - crop.left) * sx
                            val dxR = abs(p.x - crop.right) * sx
                            val dyT = abs(p.y - crop.top) * sy
                            val dyB = abs(p.y - crop.bottom) * sy
                            val nearL = dxL <= tolerance
                            val nearR = dxR <= tolerance
                            val nearT = dyT <= tolerance
                            val nearB = dyB <= tolerance
                            dragMode = when {
                                nearL && nearT -> DragMode.TOP_LEFT
                                nearR && nearT -> DragMode.TOP_RIGHT
                                nearL && nearB -> DragMode.BOTTOM_LEFT
                                nearR && nearB -> DragMode.BOTTOM_RIGHT
                                nearL && p.y in crop.top..crop.bottom -> DragMode.LEFT
                                nearR && p.y in crop.top..crop.bottom -> DragMode.RIGHT
                                nearT && p.x in crop.left..crop.right -> DragMode.TOP
                                nearB && p.x in crop.left..crop.right -> DragMode.BOTTOM
                                p.x in crop.left..crop.right && p.y in crop.top..crop.bottom -> DragMode.MOVE
                                else -> DragMode.NONE
                            }
                        },
                        onDragEnd = { dragMode = DragMode.NONE },
                        onDragCancel = { dragMode = DragMode.NONE },
                    ) { change, dragAmount ->
                        change.consume()
                        val g = geometry()
                        val dx = dragAmount.x / g.sourceScaleX
                        val dy = dragAmount.y / g.sourceScaleY
                        val minWidth = max(sourceWidth * 0.02f, 2f)
                        val minHeight = max(sourceHeight * 0.02f, 2f)
                        val next = RectF(crop)
                        when (dragMode) {
                            DragMode.MOVE -> {
                                val w = next.width()
                                val h = next.height()
                                next.left = (next.left + dx).coerceIn(0f, sourceWidth - w)
                                next.top = (next.top + dy).coerceIn(0f, sourceHeight - h)
                                next.right = next.left + w
                                next.bottom = next.top + h
                            }
                            DragMode.LEFT, DragMode.TOP_LEFT, DragMode.BOTTOM_LEFT ->
                                next.left = (next.left + dx).coerceIn(0f, next.right - minWidth)
                            DragMode.RIGHT, DragMode.TOP_RIGHT, DragMode.BOTTOM_RIGHT ->
                                next.right = (next.right + dx).coerceIn(next.left + minWidth, sourceWidth.toFloat())
                            else -> Unit
                        }
                        when (dragMode) {
                            DragMode.TOP, DragMode.TOP_LEFT, DragMode.TOP_RIGHT ->
                                next.top = (next.top + dy).coerceIn(0f, next.bottom - minHeight)
                            DragMode.BOTTOM, DragMode.BOTTOM_LEFT, DragMode.BOTTOM_RIGHT ->
                                next.bottom = (next.bottom + dy).coerceIn(next.top + minHeight, sourceHeight.toFloat())
                            else -> Unit
                        }
                        if (dragMode != DragMode.NONE) crop = next
                    }
                },
            ) {
                val g = calculateGeometry(
                    size.width, size.height, bitmap.width, bitmap.height, sourceWidth, sourceHeight,
                )
                drawImage(
                    bitmap.asImageBitmap(),
                    dstOffset = IntOffset(g.left.roundToInt(), g.top.roundToInt()),
                    dstSize = IntSize(g.width.roundToInt().coerceAtLeast(1), g.height.roundToInt().coerceAtLeast(1)),
                )
                val box = androidx.compose.ui.geometry.Rect(
                    g.left + crop.left * g.sourceScaleX,
                    g.top + crop.top * g.sourceScaleY,
                    g.left + crop.right * g.sourceScaleX,
                    g.top + crop.bottom * g.sourceScaleY,
                )
                val dim = Color.Black.copy(alpha = 0.48f)
                drawRect(dim, Offset(g.left, g.top), Size(g.width, box.top - g.top))
                drawRect(dim, Offset(g.left, box.bottom), Size(g.width, g.top + g.height - box.bottom))
                drawRect(dim, Offset(g.left, box.top), Size(box.left - g.left, box.height))
                drawRect(dim, Offset(box.right, box.top), Size(g.left + g.width - box.right, box.height))
                drawRect(Color(0xFF35D07F), topLeft = box.topLeft, size = box.size, style = Stroke(width = 3.dp.toPx()))
                val handle = 7.dp.toPx()
                listOf(
                    Offset(box.left, box.top), Offset(box.center.x, box.top), Offset(box.right, box.top),
                    Offset(box.left, box.center.y), Offset(box.right, box.center.y),
                    Offset(box.left, box.bottom), Offset(box.center.x, box.bottom), Offset(box.right, box.bottom),
                ).forEach { center ->
                    drawCircle(Color.White, radius = handle, center = center)
                    drawCircle(Color(0xFF168A55), radius = handle, center = center, style = Stroke(width = 2.dp.toPx()))
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Cancel") }
            OutlinedButton(
                onClick = { crop = RectF(initialBounds); dragMode = DragMode.NONE },
                modifier = Modifier.weight(1f),
            ) { Text("Reset") }
            Button(onClick = { onConfirm(RectF(crop)) }, modifier = Modifier.weight(1.2f)) {
                Text("Apply crop")
            }
        }
    }
}

private data class Geometry(
    val left: Float,
    val top: Float,
    val fitScale: Float,
    val sourceScaleX: Float,
    val sourceScaleY: Float,
    val width: Float,
    val height: Float,
)

private enum class DragMode {
    NONE, MOVE, LEFT, RIGHT, TOP, BOTTOM,
    TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT,
}


private fun calculateGeometry(
    canvasWidth: Float,
    canvasHeight: Float,
    bitmapWidth: Int,
    bitmapHeight: Int,
    sourceWidth: Int,
    sourceHeight: Int,
): Geometry {
    val fit = min(canvasWidth / bitmapWidth, canvasHeight / bitmapHeight)
    val width = bitmapWidth * fit
    val height = bitmapHeight * fit
    return Geometry(
        left = (canvasWidth - width) / 2f,
        top = (canvasHeight - height) / 2f,
        fitScale = fit,
        sourceScaleX = bitmapWidth.toFloat() / sourceWidth * fit,
        sourceScaleY = bitmapHeight.toFloat() / sourceHeight * fit,
        width = width,
        height = height,
    )
}
