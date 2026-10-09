package org.techwithkaushik.formsnap.feature.capture

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun AiExampleLabelDialog(
    store: AiLearningStore,
    onSaved: (AiLearningStore.Example) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var uri by remember { mutableStateOf<Uri?>(null) }
    var bitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var classId by remember { mutableIntStateOf(AiLearningStore.PHOTO) }
    var start by remember { mutableStateOf<Offset?>(null) }
    var end by remember { mutableStateOf<Offset?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var canvasWidth by remember { mutableIntStateOf(1) }
    var canvasHeight by remember { mutableIntStateOf(1) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { selected ->
        if (selected != null) {
            uri = selected
            start = null
            end = null
            scope.launch {
                bitmap = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(selected)?.use { BitmapFactory.decodeStream(it) }
                }
                if (bitmap == null) error = "Image could not be opened."
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Label training example") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Choose a full form image, select its class, then drag a box around the object.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(classId == AiLearningStore.PHOTO, { classId = AiLearningStore.PHOTO }, label = { Text("Photo") })
                    FilterChip(classId == AiLearningStore.SIGNATURE, { classId = AiLearningStore.SIGNATURE }, label = { Text("Signature") })
                }
                Button(onClick = { picker.launch(arrayOf("image/jpeg", "image/png", "image/webp")) }) {
                    Text(if (uri == null) "Choose form image" else "Choose another image")
                }
                val img = bitmap
                if (img != null) {
                    Box(Modifier.fillMaxWidth().height(260.dp).onSizeChanged { canvasWidth = it.width; canvasHeight = it.height }.background(Color.DarkGray)
                        .pointerInput(img) {
                            detectDragGestures(
                                onDragStart = { start = it; end = it },
                                onDrag = { change, _ -> end = change.position },
                            )
                        }) {
                        Canvas(Modifier.fillMaxSize()) {
                            val scale = minOf(size.width / img.width, size.height / img.height)
                            val w = img.width * scale
                            val h = img.height * scale
                            val ox = (size.width - w) / 2f
                            val oy = (size.height - h) / 2f
                            drawImage(img.asImageBitmap(), dstOffset = IntOffset(ox.toInt(), oy.toInt()), dstSize = IntSize(w.toInt(), h.toInt()))
                            val a = start; val b = end
                            if (a != null && b != null) {
                                val l = minOf(a.x, b.x).coerceIn(ox, ox + w)
                                val t = minOf(a.y, b.y).coerceIn(oy, oy + h)
                                val r = maxOf(a.x, b.x).coerceIn(ox, ox + w)
                                val bt = maxOf(a.y, b.y).coerceIn(oy, oy + h)
                                drawRect(Color.Yellow, Offset(l, t), Size(r-l, bt-t), style = Stroke(3f))
                            }
                        }
                    }
                }
                error?.let { Text(it, color = Color.Red) }
            }
        },
        confirmButton = {
            Button(enabled = uri != null && bitmap != null && start != null && end != null && !busy, onClick = {
                val selected = uri; val img = bitmap; val a = start; val b = end
                if (selected != null && img != null && a != null && b != null) {
                    busy = true
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching {
                                // Convert screen coordinates using the same centered-fit mapping.
                                val maxW = canvasWidth.toFloat(); val maxH = canvasHeight.toFloat()
                                val scale = minOf(maxW / img.width, maxH / img.height)
                                val w = img.width * scale; val h = img.height * scale
                                val ox = (maxW - w) / 2f; val oy = (maxH - h) / 2f
                                val l = ((minOf(a.x, b.x) - ox) / w).coerceIn(0f, 1f)
                                val t = ((minOf(a.y, b.y) - oy) / h).coerceIn(0f, 1f)
                                val r = ((maxOf(a.x, b.x) - ox) / w).coerceIn(0f, 1f)
                                val bt = ((maxOf(a.y, b.y) - oy) / h).coerceIn(0f, 1f)
                                store.saveReviewedCrop(selected.toString(), classId, l, t, r, bt, "manual")
                            }
                        }
                        busy = false
                        result.fold(onSuccess = onSaved, onFailure = { error = it.message ?: "Save failed." })
                    }
                }
            }) { Text(if (busy) "Saving…" else "Save label") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
