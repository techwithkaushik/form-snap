@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package org.techwithkaushik.formsnap

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
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
    private var useDarkTheme by mutableStateOf(true)

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        useDarkTheme = getSharedPreferences("objectsnap.ui", MODE_PRIVATE).getBoolean("dark_theme", true)
        // Remove stale intermediate files left by a killed or previously crashed run.
        org.techwithkaushik.formsnap.foundation.ProcessingPaths.cleanupStale(this)
        setContent { FormSnapTheme(darkTheme = useDarkTheme) { FormSnapApp() } }
    }

    @Composable
    private fun FormSnapApp() {
        val ioScope = rememberCoroutineScope()
        var settings by remember { mutableStateOf(OutputSettings()) }
        val capturePrefs = remember { getSharedPreferences("formsnap.live.detection", MODE_PRIVATE) }
        var autoSaveEnabled by remember { mutableStateOf(capturePrefs.getBoolean("auto_save_enabled", true)) }
        var settingsOpen by remember { mutableStateOf(false) }
        var aiSettingsOpen by remember { mutableStateOf(false) }
        var storageSetupOpen by remember { mutableStateOf(false) }
        var saveMessage by remember { mutableStateOf<String?>(null) }
        var pendingCameraMode by remember { mutableStateOf<CaptureMode?>(null) }
        var pendingImport by remember { mutableStateOf(false) }
        var showCameraX by remember { mutableStateOf(false) }
        var cameraXMode by remember { mutableStateOf(CameraCaptureMode.WHOLE_FORM) }
        val autoSaveStore = remember { AutoSaveStore(this@MainActivity) }

        val storagePermissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { grants ->
            if (grants.values.isNotEmpty() && grants.values.all { it }) {
                try {
                    autoSaveStore.setDirectFolder(File(defaultSharedStorageRoot(), "AI ObjectSnap"))
                    storageSetupOpen = false
                    saveMessage = "AI ObjectSnap/savedImage folder is ready."
                } catch (error: Exception) {
                    storageSetupOpen = true
                    saveMessage = "Storage permission was granted, but the folder could not be created. Choose the SD card folder."
                }
            } else {
                storageSetupOpen = true
                saveMessage = "Storage permission denied. Choose a writable SD card folder instead."
            }
        }

        LaunchedEffect(Unit) {
            if (!autoSaveStore.isConfigured()) {
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
                    val hasRead = checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
                    val hasWrite = checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
                    if (hasRead && hasWrite) {
                        runCatching {
                            autoSaveStore.setDirectFolder(File(defaultSharedStorageRoot(), "AI ObjectSnap"))
                        }.onFailure { storageSetupOpen = true }
                    } else {
                        storagePermissionLauncher.launch(arrayOf(
                            Manifest.permission.READ_EXTERNAL_STORAGE,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE,
                        ))
                    }
                } else {
                    // Android 11+ restricts direct access to shared-storage roots; use
                    // the system picker once and persist its URI permission.
                    storageSetupOpen = true
                }
            }
        }

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
                    org.techwithkaushik.formsnap.foundation.ProcessingPaths.cleanup(this@MainActivity)
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
                try {
                    autoSaveStore.setFolder(uri)
                    autoSaveStore.ensureAppFolders()
                    storageSetupOpen = false
                    saveMessage = "AI ObjectSnap/savedImage folder is ready."
                    if (pendingCameraMode != null) {
                        cameraXMode = CameraCaptureMode.WHOLE_FORM
                        showCameraX = true
                        pendingCameraMode = null
                    }
                } catch (error: Exception) {
                    storageSetupOpen = true
                    saveMessage = "Storage setup failed. Choose a writable SD card folder."
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

        if (!aiSettingsOpen && !settingsOpen && !showCameraX) HomeScreen(
            settings = settings,
            isDarkTheme = useDarkTheme,
            onToggleTheme = {
                useDarkTheme = !useDarkTheme
                getSharedPreferences("objectsnap.ui", MODE_PRIVATE).edit().putBoolean("dark_theme", useDarkTheme).apply()
            },
            onSettings = { settingsOpen = true },
            onAiModel = { aiSettingsOpen = true },
            onCamera = { selected ->
                pendingCameraMode = selected
                if (autoSaveStore.folderUri() == null) {
                    storageSetupOpen = true
                } else {
                    pendingCameraMode = null
                    cameraXMode = CameraCaptureMode.WHOLE_FORM
                    showCameraX = true
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

        if (aiSettingsOpen && !showCameraX) {
            AiModelSettingsDialog(
                context = this@MainActivity,
                onDismiss = { aiSettingsOpen = false },
                onMessage = { saveMessage = it },
            )
        }

        if (settingsOpen && !aiSettingsOpen && !showCameraX) {
            OutputSettingsDialog(
                initial = settings,
                initialAutoSave = autoSaveEnabled,
                currentFolder = autoSaveStore.folderUri()?.lastPathSegment ?: "Selected folder",
                onChooseFolder = { outputFolderLauncher.launch(null) },
                onDismiss = { settingsOpen = false },
                onSave = { updated, autoSave ->
                    settings = updated
                    autoSaveEnabled = autoSave
                    capturePrefs.edit().putBoolean("auto_save_enabled", autoSave).apply()
                    settingsOpen = false
                },
            )
        }

        if (storageSetupOpen && !showCameraX && !aiSettingsOpen && !settingsOpen) {
            AlertDialog(
                onDismissRequest = { storageSetupOpen = false },
                title = { Text("Choose save location") },
                text = { Text("Choose the SD card or shared-storage root. AI ObjectSnap will create AI ObjectSnap/savedImage/modelName/annotationName/image1.jpg folders there. If broad storage permission is unavailable, use this folder picker.") },
                confirmButton = { Button(onClick = { outputFolderLauncher.launch(null) }) { Text("Choose SD card folder") } },
                dismissButton = { TextButton(onClick = { storageSetupOpen = false }) { Text("Not now") } },
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

    /** Prefer a removable SD-card volume when Android exposes one, otherwise use primary shared storage. */
    private fun defaultSharedStorageRoot(): File {
        val roots = getExternalFilesDirs(null).filterNotNull().mapNotNull { appDir ->
            val path = appDir.absolutePath
            val marker = path.indexOf("/Android/")
            if (marker > 0) File(path.substring(0, marker)) else null
        }.distinctBy { it.absolutePath }
        return roots.firstOrNull { root ->
            runCatching { Environment.isExternalStorageRemovable(root) }.getOrDefault(false)
        } ?: Environment.getExternalStorageDirectory()
    }

    private fun uriToFile(uri: Uri, prefix: String): File? {
        var temporary: File? = null
        return try {
            val dir = File(
                org.techwithkaushik.formsnap.foundation.ProcessingPaths.root(this),
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
        isDarkTheme: Boolean,
        onToggleTheme: () -> Unit,
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
                        TextButton(onClick = onToggleTheme) { Text(if (isDarkTheme) "Light" else "Dark") }
                        TextButton(onClick = onAiModel) { Text("AI Model") }
                        TextButton(onClick = onSettings) { Text("Save") }
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
                            Text("Save settings", fontWeight = FontWeight.Bold)
                            Text("Crop size: detected object box")
                            Text("Maximum file size: ${settings.maxKb} KB per output")
                            Text("File type: ${settings.fileType.name}")
                            Text(if (settings.mergeEnabled) "Merge: ${settings.mergeOrientation.name.lowercase()} (same ${if (settings.mergeOrientation == MergeOrientation.VERTICAL) "width" else "height"})" else "Merge: Off")
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(onClick = onSettings) { Text("Configure save options") }
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
    private fun OutputSettingsDialog(
        initial: OutputSettings,
        initialAutoSave: Boolean,
        currentFolder: String,
        onChooseFolder: () -> Unit,
        onDismiss: () -> Unit,
        onSave: (OutputSettings, Boolean) -> Unit,
    ) {
        var kb by remember { mutableIntStateOf(initial.maxKb) }
        var autoSave by remember { mutableStateOf(initialAutoSave) }
        var fileType by remember { mutableStateOf(initial.fileType) }
        var mergeEnabled by remember { mutableStateOf(initial.mergeEnabled) }
        var mergeOrientation by remember { mutableStateOf(initial.mergeOrientation) }
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Save Settings", fontWeight = FontWeight.SemiBold) },
                    navigationIcon = { TextButton(onClick = onDismiss) { Text("‹", style = MaterialTheme.typography.headlineMedium) } },
                    actions = { TextButton(onClick = { onSave(OutputSettings(kb, fileType, mergeEnabled, mergeOrientation), autoSave) }) { Text("Save") } },
                )
            },
        ) { padding ->
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Card {
                        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Auto crop & save", fontWeight = FontWeight.SemiBold)
                                Text("Automatically capture and save when the model detects an object.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(checked = autoSave, onCheckedChange = { autoSave = it })
                        }
                    }
                }
                item {
                    Card {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Save location", fontWeight = FontWeight.SemiBold)
                            Text(currentFolder, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("AI ObjectSnap/savedImage/modelName/annotationName/image1.jpg", style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = onChooseFolder) { Text("Change location") }
                        }
                    }
                }
                item {
                    Card {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Merge detected objects", fontWeight = FontWeight.SemiBold)
                                    Text("Combine multiple detected objects into one image.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Switch(checked = mergeEnabled, onCheckedChange = { mergeEnabled = it })
                            }
                            if (mergeEnabled) {
                                Text("Merge direction", fontWeight = FontWeight.SemiBold)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilterChip(selected = mergeOrientation == MergeOrientation.VERTICAL, onClick = { mergeOrientation = MergeOrientation.VERTICAL }, label = { Text("↕ Vertical") }, modifier = Modifier.weight(1f))
                                    FilterChip(selected = mergeOrientation == MergeOrientation.HORIZONTAL, onClick = { mergeOrientation = MergeOrientation.HORIZONTAL }, label = { Text("↔ Horizontal") }, modifier = Modifier.weight(1f))
                                }
                                Text(if (mergeOrientation == MergeOrientation.VERTICAL) "Stack top-to-bottom; every crop uses the same width." else "Place left-to-right; every crop uses the same height.", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                item {
                    Card {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Output file type", fontWeight = FontWeight.SemiBold)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutputImageType.entries.forEach { type ->
                                    FilterChip(selected = fileType == type, onClick = { fileType = type }, label = { Text(type.name) }, modifier = Modifier.weight(1f))
                                }
                            }
                            HorizontalDivider()
                            Text("Max file size (KB)", fontWeight = FontWeight.SemiBold)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                OutlinedButton(onClick = { kb = (kb - 25).coerceAtLeast(5) }) { Text("−") }
                                Text(kb.toString(), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                                OutlinedButton(onClick = { kb = (kb + 25).coerceAtMost(2048) }) { Text("+") }
                            }
                            Text("Images follow detected object dimensions and are compressed/resized only as needed to meet this size.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                item {
                    Button(onClick = { onSave(OutputSettings(kb, fileType, mergeEnabled, mergeOrientation), autoSave) }, modifier = Modifier.fillMaxWidth()) { Text("Save settings") }
                }
            }
        }
    }

}

@Composable
private fun FormSnapTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    val scheme = if (darkTheme) darkColorScheme(
        primary = Color(0xFF168BFF),
        onPrimary = Color.White,
        primaryContainer = Color(0xFF14283A),
        onPrimaryContainer = Color(0xFFEAF5FF),
        secondary = Color(0xFF25D9E8),
        onSecondary = Color(0xFF05212A),
        secondaryContainer = Color(0xFF12333A),
        onSecondaryContainer = Color(0xFFDDFBFF),
        tertiary = Color(0xFF9B8AFF),
        onTertiary = Color(0xFF1D1637),
        tertiaryContainer = Color(0xFF28233C),
        onTertiaryContainer = Color(0xFFEAE3FF),
        background = Color(0xFF07111D),
        onBackground = Color(0xFFEAF1F8),
        surface = Color(0xFF101C29),
        onSurface = Color(0xFFEAF1F8),
        surfaceVariant = Color(0xFF1B2A39),
        onSurfaceVariant = Color(0xFFB9C9D8),
        outline = Color(0xFF506579),
        outlineVariant = Color(0xFF293B4D),
    ) else lightColorScheme(
        primary = Color(0xFF0878D1),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFD9F2FF),
        onPrimaryContainer = Color(0xFF082D49),
        secondary = Color(0xFF007F8B),
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFD0F7F7),
        onSecondaryContainer = Color(0xFF073B40),
        tertiary = Color(0xFF6550A4),
        onTertiary = Color.White,
        tertiaryContainer = Color(0xFFEAE2FF),
        onTertiaryContainer = Color(0xFF25134E),
        background = Color(0xFFF4F8FC),
        onBackground = Color(0xFF14202B),
        surface = Color.White,
        onSurface = Color(0xFF14202B),
        surfaceVariant = Color(0xFFE7EEF5),
        onSurfaceVariant = Color(0xFF435363),
        outline = Color(0xFF72869A),
        outlineVariant = Color(0xFFD1DCE6),
    )
    MaterialTheme(colorScheme = scheme, content = content)
}
