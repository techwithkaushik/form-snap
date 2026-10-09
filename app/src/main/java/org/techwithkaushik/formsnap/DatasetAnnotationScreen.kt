@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package org.techwithkaushik.formSnap

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private enum class LabelClass(val id: Int, val title: String, val color: Color) {
    PHOTO(0, "PHOTO", Color(0xFF00C853)),
    SIGNATURE(1, "SIGNATURE", Color(0xFFFF6D00))
}
private data class LabelBox(val type: LabelClass, val l: Float, val t: Float, val r: Float, val b: Float)

@Composable
internal fun DatasetAnnotationScreen(images: List<File>, onExit: () -> Unit, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var index by remember(images) { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf(LabelClass.PHOTO) }
    var annotationsByImage by remember(images) { mutableStateOf<Map<String, List<LabelBox>>>(emptyMap()) }
    var activeBox by remember { mutableStateOf<LabelBox?>(null) }
    var busy by remember { mutableStateOf(false) }
    var savedPaths by remember(images) { mutableStateOf<Set<String>>(emptySet()) }
    var dirtyPaths by remember(images) { mutableStateOf<Set<String>>(emptySet()) }
    var status by remember { mutableStateOf("PHOTO चुनें और फोटो के चारों ओर drag करें") }
    val image = images.getOrNull(index)
    val boxes = image?.let { annotationsByImage[it.absolutePath].orEmpty() }.orEmpty()
    val saved = savedPaths.size
    val bitmap = remember(image?.absolutePath) { image?.let { decodeSampledBitmap(it, 1800) } }
    DisposableEffect(bitmap) {
        onDispose { bitmap?.recycle() }
    }
    val zipLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) scope.launch {
            busy = true
            try {
                withContext(Dispatchers.IO) { writeDatasetZip(context, uri) }
                onMessage("dataset.zip export हो गया")
            } catch (e: Exception) { onMessage("Export failed: " + (e.message ?: "unknown error")) }
            finally { busy = false }
        }
    }

    fun saveAndNext() {
        val current = image ?: return
        val currentBoxes = boxes
        if (currentBoxes.none { it.type == LabelClass.PHOTO }) {
            status = "इस form पर कम-से-कम एक PHOTO box mark करें"; return
        }
        if (currentBoxes.none { it.type == LabelClass.SIGNATURE }) {
            status = "इस form पर कम-से-कम एक SIGNATURE box mark करें"; return
        }
        scope.launch {
            busy = true
            try {
                withContext(Dispatchers.IO) {
                    val root = File(context.filesDir, "dataset-yolo")
                    val imageDir = File(root, "train/images").apply { mkdirs() }
                    val labelDir = File(root, "train/labels").apply { mkdirs() }
                    val base = "form_" + Integer.toHexString(current.absolutePath.hashCode())
                    val ext = current.extension.lowercase(Locale.ROOT).let { if (it in listOf("jpg", "jpeg", "png", "webp")) it else "jpg" }
                    current.copyTo(File(imageDir, base + "." + ext), true)
                    val text = currentBoxes.joinToString("\n") { b ->
                        String.format(Locale.US, "%d %.6f %.6f %.6f %.6f", b.type.id,
                            (b.l + b.r) / 2f, (b.t + b.b) / 2f, b.r - b.l, b.b - b.t)
                    } + "\n"
                    File(labelDir, base + ".txt").writeText(text)
                }
                annotationsByImage = annotationsByImage + (current.absolutePath to currentBoxes)
                savedPaths = savedPaths + current.absolutePath
                dirtyPaths = dirtyPaths - current.absolutePath
                if (index < images.lastIndex) {
                    index++
                    activeBox = null
                    status = "अगला form: PHOTO और SIGNATURE mark करें"
                } else status = "सभी forms annotate हो गए। ZIP export करें।"
            } catch (e: Exception) { status = "Save failed: " + (e.message ?: "unknown error") }
            finally { busy = false }
        }
    }

    BackHandler(onBack = onExit)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Dataset Builder", fontWeight = FontWeight.Bold) },
                navigationIcon = { TextButton(onClick = onExit) { Text("Exit") } },
                actions = { Text((index + 1).toString() + "/" + images.size, modifier = Modifier.padding(end = 12.dp)) }
            )
        },
        bottomBar = {
            Surface(shadowElevation = 8.dp) {
                Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        LabelClass.values().forEach { cls ->
                            val count = boxes.count { it.type == cls }
                            FilterChip(selected == cls, { selected = cls }, label = { Text("${cls.id} • ${cls.title} ($count)") }, modifier = Modifier.weight(1f))
                        }
                        OutlinedButton(
                            onClick = {
                                if (boxes.isNotEmpty()) {
                                    image?.let {
                                        annotationsByImage = annotationsByImage + (it.absolutePath to boxes.dropLast(1))
                                        if (it.absolutePath in savedPaths) dirtyPaths = dirtyPaths + it.absolutePath
                                    }
                                    activeBox = null
                                    status = "आखिरी box हटाया गया"
                                }
                            },
                            enabled = boxes.isNotEmpty() && !busy,
                        ) { Text("Undo") }
                        OutlinedButton(
                            onClick = {
                                image?.let {
                                    annotationsByImage = annotationsByImage + (it.absolutePath to emptyList())
                                    if (it.absolutePath in savedPaths) dirtyPaths = dirtyPaths + it.absolutePath
                                }
                                activeBox = null
                                status = "सभी boxes हटे। दोबारा mark करके Save करें; बदले हुए dataset को export से पहले save करना जरूरी है।"
                            },
                            enabled = boxes.isNotEmpty() && !busy,
                        ) { Text("Clear") }
                    }
                    Text("एक form पर कई PHOTO/SIGNATURE boxes बना सकते हैं। Save करने के लिए दोनों classes में कम-से-कम एक box जरूरी है।", style = MaterialTheme.typography.bodySmall)
                    Text("Annotated: $saved/${images.size}", style = MaterialTheme.typography.bodySmall)
                    Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { if (index > 0) { index--; activeBox = null; status = "पिछले form के saved boxes जाँचें" } }, enabled = index > 0 && !busy, modifier = Modifier.weight(1f)) { Text("Previous") }
                        Button(onClick = { saveAndNext() }, enabled = image != null && !busy, modifier = Modifier.weight(1f)) { Text(if (index < images.lastIndex) "Save & Next" else "Save Form") }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { zipLauncher.launch("formsnap-dataset.zip") }, enabled = saved > 0 && dirtyPaths.isEmpty() && !busy, modifier = Modifier.weight(1f)) { Text(if (dirtyPaths.isEmpty()) "Export dataset.zip" else "Save edits first") }
                        TextButton(onClick = onExit, enabled = !busy) { Text("Finish") }
                    }
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).background(Color(0xFF101216)).padding(4.dp), contentAlignment = Alignment.Center) {
            if (bitmap == null) Text("Image could not be opened", color = Color.White)
            else BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val w = constraints.maxWidth.toFloat()
                val h = constraints.maxHeight.toFloat()
                val scale = minOf(w / bitmap.width, h / bitmap.height)
                val dw = bitmap.width * scale
                val dh = bitmap.height * scale
                val ox = (w - dw) / 2f
                val oy = (h - dh) / 2f
                Image(bitmap.asImageBitmap(), "Imported form", Modifier.size(
                    with(androidx.compose.ui.platform.LocalDensity.current) { dw.toDp() },
                    with(androidx.compose.ui.platform.LocalDensity.current) { dh.toDp() }), contentScale = ContentScale.FillBounds)
                Canvas(Modifier.fillMaxSize().pointerInput(index, selected, dw, dh, ox, oy) {
                    var start: Offset? = null
                    detectDragGestures(
                        onDragStart = { start = it; activeBox = null },
                        onDragEnd = {
                            activeBox?.let { b ->
                                if (b.r - b.l > .005f && b.b - b.t > .005f) {
                                    image?.let { current ->
                                        annotationsByImage = annotationsByImage + (current.absolutePath to (boxes + b))
                                        if (current.absolutePath in savedPaths) dirtyPaths = dirtyPaths + current.absolutePath
                                    }
                                }
                            }
                            activeBox = null
                            start = null
                        },
                        onDragCancel = { activeBox = null; start = null }
                    ) { change, _ ->
                        val a = start ?: change.position.also { start = it }
                        val x1 = ((minOf(a.x, change.position.x) - ox) / dw).coerceIn(0f, 1f)
                        val y1 = ((minOf(a.y, change.position.y) - oy) / dh).coerceIn(0f, 1f)
                        val x2 = ((maxOf(a.x, change.position.x) - ox) / dw).coerceIn(0f, 1f)
                        val y2 = ((maxOf(a.y, change.position.y) - oy) / dh).coerceIn(0f, 1f)
                        if (x2 > x1 && y2 > y1) activeBox = LabelBox(selected, x1, y1, x2, y2)
                    }
                }) {
                    (boxes + listOfNotNull(activeBox)).forEach { b ->
                        drawRect(b.type.color, Offset(ox + b.l * dw, oy + b.t * dh),
                            Size((b.r - b.l) * dw, (b.b - b.t) * dh),
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx()))
                    }
                }
            }
        }
    }
}


/**
 * Decode large form images at a bounded resolution. Uniform downsampling preserves
 * normalized YOLO coordinates while avoiding full-resolution bitmap allocations on
 * memory-constrained Android devices.
 */
private fun decodeSampledBitmap(file: File, maxDimension: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sample = 1
    while (maxOf(bounds.outWidth / sample, bounds.outHeight / sample) > maxDimension) {
        sample *= 2
    }
    val options = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.RGB_565
    }
    return BitmapFactory.decodeFile(file.absolutePath, options)
}

private fun writeDatasetZip(context: Context, uri: Uri) {
    val root = File(context.filesDir, "dataset-yolo")
    val images = File(root, "train/images").listFiles()?.filter { it.isFile }?.sortedBy { it.name }.orEmpty()
    require(images.isNotEmpty()) { "No saved annotations" }
    context.contentResolver.openOutputStream(uri)?.use { stream ->
        ZipOutputStream(stream).use { zip ->
            images.forEachIndexed { index, image ->
                val split = when {
                    images.size >= 10 && index % 10 == 8 -> "valid"
                    images.size >= 10 && index % 10 == 9 -> "test"
                    else -> "train"
                }
                val label = File(root, "train/labels/" + image.name.substringBeforeLast('.') + ".txt")
                require(label.isFile) { "Missing label for " + image.name }
                // Re-encode only the exported copy. Keep the user's source image intact.
                // Normalized YOLO coordinates remain valid after proportional resizing.
                val decoded = decodeSampledBitmap(image, 1600)
                    ?: error("Could not decode dataset image: ${image.name}")
                val maxDimension = 1600
                val resizeScale = minOf(
                    1f,
                    maxDimension.toFloat() / maxOf(decoded.width, decoded.height),
                )
                val exportBitmap = if (resizeScale < 1f) {
                    Bitmap.createScaledBitmap(
                        decoded,
                        (decoded.width * resizeScale).toInt().coerceAtLeast(1),
                        (decoded.height * resizeScale).toInt().coerceAtLeast(1),
                        true,
                    )
                } else decoded
                val compressed = ByteArrayOutputStream()
                try {
                    check(exportBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, compressed)) {
                        "Could not compress dataset image: ${image.name}"
                    }
                    val jpgName = image.name.substringBeforeLast('.') + ".jpg"
                    zip.putNextEntry(ZipEntry(split + "/images/" + jpgName))
                    compressed.writeTo(zip)
                    zip.closeEntry()
                } finally {
                    if (exportBitmap !== decoded) exportBitmap.recycle()
                    decoded.recycle()
                }
                zip.putNextEntry(ZipEntry(split + "/labels/" + label.name))
                label.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            val yaml = """path: .
train: train/images
val: valid/images
test: test/images
names:
  0: PHOTO
  1: SIGNATURE
"""
            zip.putNextEntry(ZipEntry("data.yaml")); zip.write(yaml.toByteArray()); zip.closeEntry()
            val note = """Classes: 0=PHOTO, 1=SIGNATURE.
The ZIP is split by selection order. Review the split before training; near-duplicate pages and pages from the same source form should stay in one split. If fewer than 10 images are annotated, validation/test folders may be empty.
"""
            zip.putNextEntry(ZipEntry("README.txt")); zip.write(note.toByteArray()); zip.closeEntry()
        }
    } ?: error("Could not open ZIP output")
}
