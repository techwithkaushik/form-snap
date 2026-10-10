@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package org.techwithkaushik.formSnap

import org.techwithkaushik.formsnap.BuildConfig

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.techwithkaushik.formsnap.feature.capture.AndroidCaptureScreen
import org.techwithkaushik.formsnap.feature.capture.CaptureMode as CameraCaptureMode
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

private enum class CaptureMode { WHOLE_FORM, PHOTO, SIGNATURE }

private data class OutputSettings(
    val photoWidthMm: Double = 40.0,
    val photoHeightMm: Double = 50.0,
    val signatureWidthMm: Double = 50.0,
    val signatureHeightMm: Double = 20.0,
    val dpi: Double = 300.0,
    val maxKb: Int = 50,
)

class MainActivity : ComponentActivity() {
    private var cameraUri: Uri? = null
    private var cameraOutputFile: File? = null

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        cameraUri = savedInstanceState?.getString(STATE_CAMERA_URI)?.let(Uri::parse)
        cameraOutputFile = savedInstanceState?.getString(STATE_CAMERA_FILE)?.let(::File)
        // Remove stale intermediate files left by a killed or previously crashed run.
        org.techwithkaushik.formSnap.foundation.ProcessingPaths.cleanupStale(this)
        setContent { FormSnapTheme { FormSnapApp() } }
    }

    @Composable
    private fun FormSnapApp() {
        val ioScope = rememberCoroutineScope()
        var settings by remember { mutableStateOf(OutputSettings()) }
        var settingsOpen by remember { mutableStateOf(false) }
        var aiSettingsOpen by remember { mutableStateOf(false) }
        var source by remember { mutableStateOf<File?>(null) }
        var mode by remember { mutableStateOf(CaptureMode.WHOLE_FORM) }
        var saveMessage by remember { mutableStateOf<String?>(null) }
        var pendingCameraMode by remember { mutableStateOf<CaptureMode?>(null) }
        var pendingImport by remember { mutableStateOf(false) }
        var datasetImages by remember { mutableStateOf<List<File>>(emptyList()) }
        var showDataset by remember { mutableStateOf(false) }
        var showCameraX by remember { mutableStateOf(false) }
        var cameraXMode by remember { mutableStateOf(CameraCaptureMode.WHOLE_FORM) }
        var lockedSelections by remember { mutableStateOf<List<org.techwithkaushik.formsnap.feature.capture.LiveDetection>>(emptyList()) }
        val autoSaveStore = remember { AutoSaveStore(this@MainActivity) }

        fun startAutoProcess(input: File, selectedMode: CaptureMode) {
            ioScope.launch {
                saveMessage = "AI detecting photo and signature…"
                try {
                    val result = withContext(Dispatchers.IO) {
                        AutoExtractionService.process(
                            context = this@MainActivity,
                            selectedDetections = lockedSelections,
                            input = input,
                            dpi = settings.dpi.toInt(),
                            maxKb = settings.maxKb,
                            mode = selectedMode.name,
                            photoWidthMm = settings.photoWidthMm,
                            photoHeightMm = settings.photoHeightMm,
                            signatureWidthMm = settings.signatureWidthMm,
                            signatureHeightMm = settings.signatureHeightMm,
                        )
                    }
                    val saved = withContext(Dispatchers.IO) {
                        autoSaveStore.save(result.photoBytes, result.signatureBytes)
                    }
                    val parts = listOfNotNull(saved.first, saved.second)
                    saveMessage = if (parts.isEmpty()) {
                        "Photo/signature not detected. Please capture the form more clearly."
                    } else {
                        "Auto-saved: " + parts.joinToString(" + ")
                    }
                } catch (t: Throwable) {
                    saveMessage = "Auto extraction failed: " + (t.message ?: "unknown error")
                } finally {
                    lockedSelections = emptyList()
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
        BackHandler(enabled = source != null && !settingsOpen) {
            source?.delete()
            source = null
        }

        val openCamera = rememberLauncherForActivityResult(
            ActivityResultContracts.TakePicture(),
        ) { ok ->
            val uri = cameraUri
            val capturedFile = cameraOutputFile
            cameraUri = null
            cameraOutputFile = null
            if (ok && uri != null && capturedFile?.isFile == true && capturedFile.length() > 0L) {
                ioScope.launch {
                    val imported = withContext(Dispatchers.IO) { uriToFile(uri, "camera") }
                    capturedFile.delete()
                    if (imported == null) {
                        saveMessage = "Could not read the captured image. Please capture again."
                    } else {
                        startAutoProcess(imported, pendingCameraMode ?: CaptureMode.WHOLE_FORM)
                    }
                    pendingCameraMode = null
                }
            } else {
                capturedFile?.delete()
                if (ok) saveMessage = "Camera did not return a usable image. Please try again."
            }
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
                        startAutoProcess(imported, if (pendingImport) mode else CaptureMode.WHOLE_FORM)
                    }
                    pendingImport = false
                }
            }
        }

        val datasetPicker = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenMultipleDocuments(),
        ) { uris ->
            if (uris.isNotEmpty()) {
                ioScope.launch {
                    val importedFiles = withContext(Dispatchers.IO) {
                        uris.mapIndexedNotNull { index, uri -> uriToFile(uri, "dataset_" + (index + 1)) }
                    }
                    if (importedFiles.isEmpty()) saveMessage = "No selected forms could be opened."
                    else {
                        datasetImages = importedFiles
                        showDataset = true
                    }
                }
            }
        }

        val outputFolderLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocumentTree(),
        ) { uri ->
            if (uri == null) {
                saveMessage = "Output folder was not selected."
            } else {
                autoSaveStore.setFolder(uri)
                saveMessage = "Output folder selected. Next capture will auto-save."
                pendingCameraMode?.let { selected ->
                    cameraXMode = when (selected) {
                        CaptureMode.PHOTO -> CameraCaptureMode.PHOTO
                        CaptureMode.SIGNATURE -> CameraCaptureMode.SIGNATURE
                        CaptureMode.WHOLE_FORM -> CameraCaptureMode.WHOLE_FORM
                    }
                    showCameraX = true
                    pendingCameraMode = null
                }
            }
        }

        val pipelineLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) {
            source?.delete()
            source = null
            // The pipeline activity has finished; no temporary session should remain.
            org.techwithkaushik.formSnap.foundation.ProcessingPaths.cleanup(this@MainActivity)
            mode = CaptureMode.WHOLE_FORM
        }

        HomeScreen(
            settings = settings,
            onSettings = { settingsOpen = true },
            onAiModel = { aiSettingsOpen = true },
            onCamera = { selected ->
                mode = selected
                if (autoSaveStore.folderUri() == null) {
                    pendingCameraMode = selected
                    outputFolderLauncher.launch(null)
                } else {
                    cameraXMode = when (selected) {
                        CaptureMode.PHOTO -> CameraCaptureMode.PHOTO
                        CaptureMode.SIGNATURE -> CameraCaptureMode.SIGNATURE
                        CaptureMode.WHOLE_FORM -> CameraCaptureMode.WHOLE_FORM
                    }
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
            onDataset = { datasetImages = emptyList(); showDataset = true },
        )

        if (showDataset) {
            DatasetAnnotationScreen(
                images = datasetImages,
                onExit = { showDataset = false; datasetImages = emptyList() },
                onMessage = { saveMessage = it },
            )
        }

        if (showCameraX) {
            BackHandler { showCameraX = false }
            AndroidCaptureScreen(
                initialMode = cameraXMode,
                onSelectionCaptured = { lockedSelections = it },
                onImageCaptured = { image ->
                    showCameraX = false
                    val uri = Uri.parse(image.uri)
                    val file = if (uri.scheme == "file") File(uri.path ?: "") else null
                    if (file?.isFile == true && file.length() > 0L) {
                        startAutoProcess(file, when (image.mode) {
                            CameraCaptureMode.PHOTO -> CaptureMode.PHOTO
                            CameraCaptureMode.SIGNATURE -> CaptureMode.SIGNATURE
                            CameraCaptureMode.WHOLE_FORM -> CaptureMode.WHOLE_FORM
                        })
                    } else {
                        saveMessage = "Camera image could not be opened. Please capture again."
                    }
                },
                onImportImage = { image ->
                    showCameraX = false
                    ioScope.launch {
                        val imported = withContext(Dispatchers.IO) { uriToFile(Uri.parse(image.uri), "import") }
                        if (imported == null) saveMessage = "Could not open this image. Try a different file."
                        else startAutoProcess(imported, CaptureMode.WHOLE_FORM)
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

    private fun launchCamera(launcher: ActivityResultLauncher<Uri>) {
        try {
            val dir = File(
                org.techwithkaushik.formSnap.foundation.ProcessingPaths.root(this),
                "inputs/captures",
            ).apply {
                check(isDirectory || mkdirs()) { "Cannot create the camera output directory." }
            }
            val file = File(dir, "capture_" + System.currentTimeMillis() + ".jpg")
            check(!file.exists() || file.delete()) { "Cannot prepare the camera output file." }
            val uri = FileProvider.getUriForFile(
                this,
                BuildConfig.APPLICATION_ID + ".fileprovider",
                file,
            )
            cameraOutputFile = file
            cameraUri = uri
            launcher.launch(uri)
        } catch (error: Exception) {
            cameraUri = null
            cameraOutputFile?.delete()
            cameraOutputFile = null
            android.widget.Toast.makeText(
                this,
                error.message ?: "Unable to open the camera. Check camera permission and try again.",
                android.widget.Toast.LENGTH_LONG,
            ).show()
        }
    }

    override fun onSaveInstanceState(outState: android.os.Bundle) {
        outState.putString(STATE_CAMERA_URI, cameraUri?.toString())
        outState.putString(STATE_CAMERA_FILE, cameraOutputFile?.absolutePath)
        super.onSaveInstanceState(outState)
    }

    companion object {
        private const val STATE_CAMERA_URI = "formsnap.camera.uri"
        private const val STATE_CAMERA_FILE = "formsnap.camera.file"
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
        onDataset: () -> Unit,
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("FormSnap", fontWeight = FontWeight.Bold) },
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
                                "FormSnap",
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.ExtraBold,
                            )
                            Spacer(Modifier.height(8.dp))
                            Text("Capture or import a form. AI will detect, crop and auto-save photo and signature without a review screen.")
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
                    ActionCard("Dataset Builder", "Annotate forms / export ZIP", "▧", Modifier.fillMaxWidth(), onDataset)
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ActionCard("Photo", "Close capture", "◉", Modifier.weight(1f)) {
                            onCamera(CaptureMode.PHOTO)
                        }
                        ActionCard("Signature", "Close capture", "✎", Modifier.weight(1f)) {
                            onCamera(CaptureMode.SIGNATURE)
                        }
                    }
                }
                item {
                    Card(shape = RoundedCornerShape(18.dp)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Zero-touch AI mode", fontWeight = FontWeight.Bold)
                            Text("YOLO detects Photo and Signature locally. Handwriting is ignored; final crops come from the original full-resolution image and are auto-saved.")
                        }
                    }
                }
                item {
                    Card(shape = RoundedCornerShape(18.dp)) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Output settings", fontWeight = FontWeight.Bold)
                            Text("Photo: ${fmt(settings.photoWidthMm)} × ${fmt(settings.photoHeightMm)} mm")
                            Text("Signature: ${fmt(settings.signatureWidthMm)} × ${fmt(settings.signatureHeightMm)} mm")
                            Text("${settings.dpi.toInt()} DPI • ≤${settings.maxKb} KB each")
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(onClick = onSettings) { Text("Change size / DPI") }
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
    private fun EditorScreen(
        file: File,
        mode: CaptureMode,
        onSettings: () -> Unit,
        onBack: () -> Unit,
        onCaptureAgain: () -> Unit,
        onImportAgain: () -> Unit,
        onExtract: suspend () -> Unit,
    ) {
        var processing by remember { mutableStateOf(false) }
        var status by remember { mutableStateOf("Extracting…") }
        LaunchedEffect(file.absolutePath, mode) {
            processing = true
            status = "Opening OpenCV extraction preview…"
            try {
                onExtract()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Exception) {
                status = t.message ?: "Extraction failed"
            } finally {
                processing = false
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Preview", fontWeight = FontWeight.Bold) },
                    navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                    actions = { TextButton(onClick = onSettings) { Text("Output") } },
                )
            },
        ) { padding ->
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item {
                    Card(shape = RoundedCornerShape(20.dp)) {
                        Column {
                            Text("Input", Modifier.padding(16.dp), fontWeight = FontWeight.Bold)
                            BitmapImage(file, Modifier.fillMaxWidth().height(300.dp))
                        }
                    }
                }
                
                item {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = onCaptureAgain,
                            modifier = Modifier.weight(1f),
                            enabled = !processing,
                        ) { Text("Capture") }
                        OutlinedButton(
                            onClick = onImportAgain,
                            modifier = Modifier.weight(1f),
                            enabled = !processing,
                        ) { Text("Import") }
                    }
                }
                item {
                    Text(status, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    @Composable
    private fun BitmapImage(file: File, modifier: Modifier = Modifier) {
        val bitmap = remember(file.absolutePath) { decodeSampled(file, 1200) }
        if (bitmap != null) {
            Image(
                bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = modifier,
                contentScale = ContentScale.Fit,
            )
        } else {
            Box(
                modifier.background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) { Text("Unable to preview") }
        }
    }

    @Composable
    private fun OutputSettingsDialog(
        initial: OutputSettings,
        onDismiss: () -> Unit,
        onSave: (OutputSettings) -> Unit,
    ) {
        var pw by remember { mutableStateOf(initial.photoWidthMm.toString()) }
        var ph by remember { mutableStateOf(initial.photoHeightMm.toString()) }
        var sw by remember { mutableStateOf(initial.signatureWidthMm.toString()) }
        var sh by remember { mutableStateOf(initial.signatureHeightMm.toString()) }
        var dpi by remember { mutableStateOf(initial.dpi.toInt().toString()) }
        var kb by remember { mutableStateOf(initial.maxKb.toString()) }
        var invalid by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Output size") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Card(shape = RoundedCornerShape(14.dp)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Photo size", fontWeight = FontWeight.Bold)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = pw,
                                    onValueChange = { pw = it },
                                    modifier = Modifier.weight(1f),
                                    label = { Text("Width (mm)") },
                                    singleLine = true,
                                )
                                OutlinedTextField(
                                    value = ph,
                                    onValueChange = { ph = it },
                                    modifier = Modifier.weight(1f),
                                    label = { Text("Height (mm)") },
                                    singleLine = true,
                                )
                            }
                        }
                    }
                    Card(shape = RoundedCornerShape(14.dp)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Signature size", fontWeight = FontWeight.Bold)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = sw,
                                    onValueChange = { sw = it },
                                    modifier = Modifier.weight(1f),
                                    label = { Text("Width (mm)") },
                                    singleLine = true,
                                )
                                OutlinedTextField(
                                    value = sh,
                                    onValueChange = { sh = it },
                                    modifier = Modifier.weight(1f),
                                    label = { Text("Height (mm)") },
                                    singleLine = true,
                                )
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = dpi,
                            onValueChange = { dpi = it },
                            modifier = Modifier.weight(1f),
                            label = { Text("DPI") },
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = kb,
                            onValueChange = { kb = it },
                            modifier = Modifier.weight(1f),
                            label = { Text("Max KB") },
                            singleLine = true,
                        )
                    }
                    Text(
                        "Photo and signature dimensions are independent. DPI and Max KB apply to both outputs.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (invalid) {
                        Text(
                            "Enter valid positive sizes, DPI ≥72 and Max KB between 5 and 200.",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    val photoW = pw.toDoubleOrNull()
                    val photoH = ph.toDoubleOrNull()
                    val signW = sw.toDoubleOrNull()
                    val signH = sh.toDoubleOrNull()
                    val dpiValue = dpi.toDoubleOrNull()
                    val kbValue = kb.toIntOrNull()
                    if (photoW != null && photoW > 0.0 &&
                        photoH != null && photoH > 0.0 &&
                        signW != null && signW > 0.0 &&
                        signH != null && signH > 0.0 &&
                        dpiValue != null && dpiValue >= 72.0 &&
                        kbValue != null && kbValue in 5..200
                    ) {
                        onSave(
                            OutputSettings(
                                photoWidthMm = photoW,
                                photoHeightMm = photoH,
                                signatureWidthMm = signW,
                                signatureHeightMm = signH,
                                dpi = dpiValue,
                                maxKb = kbValue,
                            ),
                        )
                    } else {
                        invalid = true
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        )
    }

    private fun decodeSampled(file: File, max: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > max || bounds.outHeight / sample > max) sample *= 2
        return BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )
    }

    private fun fmt(value: Double): String = String.format(Locale.US, "%.1f", value)
}

@Composable
private fun FormSnapTheme(content: @Composable () -> Unit) {
    val scheme = lightColorScheme(
        primary = Color(0xFF2457A7),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFDCE8FF),
        onPrimaryContainer = Color(0xFF102B55),
        secondary = Color(0xFF176B68),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFD2F3EE),
        onSecondaryContainer = Color(0xFF103B39),
        tertiary = Color(0xFF6750A4),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFE9DDFF),
        onTertiaryContainer = Color(0xFF25134E),
        background = Color(0xFFF7F8FC),
        onBackground = Color(0xFF191C22),
        surface = Color(0xFFFDFBFF),
        onSurface = Color(0xFF191C22),
        surfaceVariant = Color(0xFFE8ECF4),
        onSurfaceVariant = Color(0xFF444A56),
        outline = Color(0xFF747B88),
        outlineVariant = Color(0xFFD0D6E1),
    )
    MaterialTheme(colorScheme = scheme, content = content)
}
