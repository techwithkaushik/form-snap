@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package org.techwithkaushik.formSnap

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.techwithkaushik.formsnap.feature.capture.AndroidCaptureScreen
import org.techwithkaushik.formsnap.feature.capture.CaptureMode as CameraCaptureMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

private enum class CaptureMode { WHOLE_FORM }

private data class OutputSettings(
    val maxKb: Int = 50,
    val fileType: OutputImageType = OutputImageType.JPEG,
    val mergeEnabled: Boolean = false,
    val mergeOrientation: MergeOrientation = MergeOrientation.VERTICAL,
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        // Remove stale intermediate files left by a killed or previously crashed run.
        org.techwithkaushik.formSnap.foundation.ProcessingPaths.cleanupStale(this)
        setContent { FormSnapTheme { FormSnapApp() } }
    }

    @Composable
    private fun FormSnapApp() {
        val ioScope = rememberCoroutineScope()
        var settings by remember { mutableStateOf(OutputSettings()) }
        val capturePrefs = remember { getSharedPreferences("formsnap.live.detection", MODE_PRIVATE) }
        var autoSaveEnabled by remember { mutableStateOf(capturePrefs.getBoolean("auto_save_enabled", true)) }
        var settingsOpen by remember { mutableStateOf(false) }
        var aiSettingsOpen by remember { mutableStateOf(false) }
        var saveMessage by remember { mutableStateOf<String?>(null) }
        var pendingCameraMode by remember { mutableStateOf<CaptureMode?>(null) }
        var pendingImport by remember { mutableStateOf(false) }
        var showCameraX by remember { mutableStateOf(false) }
        var cameraXMode by remember { mutableStateOf(CameraCaptureMode.WHOLE_FORM) }
        val autoSaveStore = remember { AutoSaveStore(this@MainActivity) }

        fun startAutoProcess(
            input: File,
            selectedDetections: List<org.techwithkaushik.formsnap.feature.capture.LiveDetection> = emptyList(),
            autoSave: Boolean = true,
        ) {
            ioScope.launch {
                saveMessage = "TFLite detecting objects…"
                try {
                    val saved = withContext(Dispatchers.IO) {
                        UniversalAutoSaveService.processAndSave(
                            context = this@MainActivity,
                            input = input,
                            maxKb = settings.maxKb,
                            imageType = settings.fileType,
                            selectedDetections = selectedDetections,
                            autoSaveEnabled = autoSave,
                            mergeEnabled = settings.mergeEnabled,
                            mergeOrientation = settings.mergeOrientation,
                        )
                    }
                    saveMessage = "Saved ${saved.size} output(s): " + saved.joinToString { "${it.className} (${it.byteCount / 1024} KB)" }
                } catch (t: Throwable) {
                    saveMessage = "Detection/save failed: " + (t.message ?: "unknown error")
                } finally {
                    input.delete()
                    org.techwithkaushik.formSnap.foundation.ProcessingPaths.cleanup(this@MainActivity)
                }
            }
        }

        // Compose must consume the system Back button while an editor or
        // settings dialog is open. Previously only the top-bar Back button
        // changed state, so the Android Back button finished the Activity.
        BackHandler(enabled = aiSettingsOpen) {
            aiSettingsOpen = false
        }
        BackHandler(enabled = settingsOpen && !aiSettingsOpen) {
            settingsOpen = false
        }
        val openDocument = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri != null) {
                ioScope.launch {
                    val imported = withContext(Dispatchers.IO) { uriToFile(uri, "import") }
                    if (imported == null) {
                        saveMessage = "Could not open this image. Try a different file."
                    } else {
                        startAutoProcess(imported, autoSave = true)
                    }
                    pendingImport = false
                }
            }
        }

        LaunchedEffect(pendingImport, autoSaveStore.folderUri()) {
            if (pendingImport && autoSaveStore.folderUri() != null) {
                pendingImport = false
                openDocument.launch(arrayOf("image/*"))
            }
        }

        val outputFolderLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocumentTree(),
        ) { uri ->
            if (uri == null) {
                pendingCameraMode = null
                saveMessage = "Output folder was not selected."
            } else {
                autoSaveStore.setFolder(uri)
                saveMessage = "Output folder selected. Next capture will auto-save."
                pendingCameraMode?.let { selected ->
                    cameraXMode = CameraCaptureMode.WHOLE_FORM
                    showCameraX = true
                    pendingCameraMode = null
                }
            }
        }

        fun proceedToCamera(selected: CaptureMode) {
            if (autoSaveStore.folderUri() == null) {
                pendingCameraMode = selected
                outputFolderLauncher.launch(null)
            } else {
                pendingCameraMode = null
                cameraXMode = CameraCaptureMode.WHOLE_FORM
                showCameraX = true
            }
        }

        val cameraPermissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            val selected = pendingCameraMode
            if (granted && selected != null) {
                proceedToCamera(selected)
            } else {
                pendingCameraMode = null
                saveMessage = "Camera permission is required. Allow camera access to capture an image."
            }
        }

        HomeScreen(
            settings = settings,
            onSettings = { settingsOpen = true },
            onAiModel = { aiSettingsOpen = true },
            onCamera = { selected ->
                pendingCameraMode = selected
                if (androidx.core.content.ContextCompat.checkSelfPermission(
                        this@MainActivity,
                        android.Manifest.permission.CAMERA,
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    proceedToCamera(selected)
                } else {
                    // Request permission immediately on tap, before folder selection
                    // or mounting the CameraX capture screen.
                    cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
                }
            },
            onImport = {
                pendingImport = false
                if (autoSaveStore.folderUri() == null) {
                    pendingImport = true
                    outputFolderLauncher.launch(null)
                } else {
                    openDocument.launch(arrayOf("image/*"))
                }
            },
        )

        if (showCameraX) {
            BackHandler { showCameraX = false }
            AndroidCaptureScreen(
                initialMode = cameraXMode,
                autoSaveEnabled = autoSaveEnabled,
                onAutoSaveEnabledChange = { enabled ->
                    autoSaveEnabled = enabled
                    capturePrefs.edit().putBoolean("auto_save_enabled", enabled).apply()
                },
                onImageCaptured = { image, selectedDetections ->
                    showCameraX = false
                    val uri = Uri.parse(image.uri)
                    val file = if (uri.scheme == "file") File(uri.path ?: "") else null
                    if (file?.isFile == true && file.length() > 0L) {
                        startAutoProcess(file, selectedDetections, autoSaveEnabled)
                    } else {
                        saveMessage = "Camera image could not be opened. Please capture again."
                    }
                },
                onImportImage = { image ->
                    showCameraX = false
                    ioScope.launch {
                        val imported = withContext(Dispatchers.IO) { uriToFile(Uri.parse(image.uri), "import") }
                        if (imported == null) saveMessage = "Could not open this image. Try a different file."
                        else startAutoProcess(imported, autoSave = true)
                    }
                },
                onError = { saveMessage = it },
            )
        }

        if (aiSettingsOpen) {
            AiModelSettingsDialog(
                context = this@MainActivity,
                onDismiss = { aiSettingsOpen = false },
                onMessage = { saveMessage = it },
            )
        }

        if (settingsOpen) {
            OutputSettingsDialog(
                initial = settings,
                onDismiss = { settingsOpen = false },
                onSave = {
                    settings = it
                    settingsOpen = false
                },
            )
        }

        saveMessage?.let { message ->
            LaunchedEffect(message) {
                kotlinx.coroutines.delay(1800)
                saveMessage = null
            }
            Box(
                Modifier.fillMaxSize().padding(20.dp),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Snackbar { Text(message) }
            }
        }
    }

    private fun uriToFile(uri: Uri, prefix: String): File? {
        var temporary: File? = null
        return try {
            val dir = File(
                org.techwithkaushik.formSnap.foundation.ProcessingPaths.root(this),
                "inputs",
            ).apply { check(isDirectory || mkdirs()) { "Cannot create the input cache." } }
            val mime = contentResolver.getType(uri)?.lowercase(Locale.ROOT)
            val extension = when (mime) {
                "image/png" -> "png"
                "image/webp" -> "webp"
                "image/heic", "image/heif" -> "jpg"
                else -> "jpg"
            }
            val file = File(dir, "${prefix}_${System.currentTimeMillis()}.$extension")
            temporary = File(dir, ".${file.name}.part")

            val input = contentResolver.openInputStream(uri) ?: return null
            input.use { source ->
                if (mime == "image/heic" || mime == "image/heif") {
                    val bitmap = BitmapFactory.decodeStream(source)
                        ?: error("This HEIC image could not be decoded on this device.")
                    try {
                        FileOutputStream(temporary!!).use { output ->
                            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)) {
                                "Could not convert the HEIC image."
                            }
                            output.fd.sync()
                        }
                    } finally {
                        bitmap.recycle()
                    }
                } else {
                    FileOutputStream(temporary!!).use { output ->
                        source.copyTo(output)
                        output.fd.sync()
                    }
                }
            }
            check(temporary!!.length() > 0L) { "The selected image is empty." }
            check(temporary!!.renameTo(file)) { "Could not finalize the imported image." }
            file
        } catch (_: Exception) {
            temporary?.delete()
            null
        }
    }

    @Composable
    private fun HomeScreen(
        settings: OutputSettings,
        onSettings: () -> Unit,
        onAiModel: () -> Unit,
        onCamera: (CaptureMode) -> Unit,
        onImport: () -> Unit,
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("AI ObjectSnap", fontWeight = FontWeight.Bold) },
                    actions = {
                        TextButton(onClick = onAiModel) { Text("AI Model") }
                        TextButton(onClick = onSettings) { Text("Output") }
                    },
                )
            },
        ) { padding ->
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                        ),
                        shape = RoundedCornerShape(24.dp),
                    ) {
                        Column(Modifier.padding(20.dp)) {
                            Text(
                                "AI ObjectSnap",
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.ExtraBold,
                            )
                            Spacer(Modifier.height(8.dp))
                            Text("Detect. Crop. Save. Import a TFLite model, point your camera at objects, and let AI ObjectSnap crop and save each detection automatically.")
                            Spacer(Modifier.height(8.dp))
                            
                        }
                    }
                }
                item {
                    Text("Quick actions", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ActionCard("Capture", "Form", "▣", Modifier.weight(1f)) {
                            onCamera(CaptureMode.WHOLE_FORM)
                        }
                        ActionCard("Import", "Form image", "▤", Modifier.weight(1f)) {
                            onImport()
                        }
                    }
                }
                item {
                    Card(shape = RoundedCornerShape(18.dp)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Smart object detection", fontWeight = FontWeight.Bold)
                            Text("Your selected AI model identifies objects. Detected crops are saved automatically, or tap boxes to choose objects manually.")
                        }
                    }
                }
                item {
                    Card(shape = RoundedCornerShape(18.dp)) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Output settings", fontWeight = FontWeight.Bold)
                            Text("Crop size: detected object box")
                            Text("Maximum file size: ${settings.maxKb} KB per output")
                            Text("File type: ${settings.fileType.name}")
                            Text(if (settings.mergeEnabled) "Merge: ${settings.mergeOrientation.name.lowercase()} (same ${if (settings.mergeOrientation == MergeOrientation.VERTICAL) "width" else "height"})" else "Merge: Off")
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(onClick = onSettings) { Text("Save options") }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun ActionCard(
        title: String,
        subtitle: String,
        symbol: String,
        modifier: Modifier,
        onClick: () -> Unit,
    ) {
        Card(
            modifier = modifier.clickable(onClick = onClick),
            shape = RoundedCornerShape(20.dp),
        ) {
            Column(Modifier.padding(16.dp).height(118.dp)) {
                Text(symbol, style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.weight(1f))
                Text(title, fontWeight = FontWeight.Bold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    @Composable
    private fun OutputSettingsDialog(initial: OutputSettings, onDismiss: () -> Unit, onSave: (OutputSettings) -> Unit) {
        var kb by remember { mutableStateOf(initial.maxKb.toString()) }
        var fileType by remember { mutableStateOf(initial.fileType) }
        var mergeEnabled by remember { mutableStateOf(initial.mergeEnabled) }
        var mergeOrientation by remember { mutableStateOf(initial.mergeOrientation) }
        var invalid by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Object save settings") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Crop dimensions and aspect ratio follow the detected object box. Only the maximum file size is limited.")
                    OutlinedTextField(value = kb, onValueChange = { kb = it }, label = { Text("Maximum KB per output") }, singleLine = true)
                    Text("File type", fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutputImageType.entries.forEach { type ->
                            FilterChip(selected = fileType == type, onClick = { fileType = type }, label = { Text(type.name) })
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Merge detected objects", modifier = Modifier.weight(1f))
                        Switch(checked = mergeEnabled, onCheckedChange = { mergeEnabled = it })
                    }
                    if (mergeEnabled) {
                        Text("Merge direction", fontWeight = FontWeight.Bold)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(selected = mergeOrientation == MergeOrientation.VERTICAL, onClick = { mergeOrientation = MergeOrientation.VERTICAL }, label = { Text("Vertical · same width") })
                            FilterChip(selected = mergeOrientation == MergeOrientation.HORIZONTAL, onClick = { mergeOrientation = MergeOrientation.HORIZONTAL }, label = { Text("Horizontal · same height") })
                        }
                        Text(
                            if (mergeOrientation == MergeOrientation.VERTICAL) "Stack top-to-bottom; scale every crop to the same width."
                            else "Place left-to-right; scale every crop to the same height.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (invalid) Text("Maximum size must be between 5 and 2048 KB.", color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                Button(onClick = {
                    val limit = kb.toIntOrNull()
                    if (limit != null && limit in 5..2048) onSave(OutputSettings(limit, fileType, mergeEnabled, mergeOrientation))
                    else invalid = true
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        )
    }

}

@Composable
private fun FormSnapTheme(content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary = Color(0xFF168BFF),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFF14283A),
        onPrimaryContainer = Color(0xFFEAF5FF),
        secondary = Color(0xFF25D9E8),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFF12333A),
        onSecondaryContainer = Color(0xFFDDFBFF),
        tertiary = Color(0xFF6750A4),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFF28233C),
        onTertiaryContainer = Color(0xFF25134E),
        background = Color(0xFF07111D),
        onBackground = Color(0xFFEAF1F8),
        surface = Color(0xFF101C29),
        onSurface = Color(0xFFEAF1F8),
        surfaceVariant = Color(0xFF1B2A39),
        onSurfaceVariant = Color(0xFFB9C9D8),
        outline = Color(0xFF506579),
        outlineVariant = Color(0xFF293B4D),
    )
    MaterialTheme(colorScheme = scheme, content = content)
}
