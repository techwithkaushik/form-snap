@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package org.techwithkaushik.formSnap

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader
import java.io.File
import java.io.FileInputStream
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

private data class Outputs(
    val photo: String? = null,
    val signature: String? = null,
)

class MainActivity : ComponentActivity() {
    private val prefs by lazy { getSharedPreferences("formsnap_storage", MODE_PRIVATE) }
    private var cameraUri: Uri? = null
    private var pendingFolderType = "photo"
    private var pendingSaveType: String? = null
    private var pendingSavePath: String? = null
    private var pendingSaveName: String = ""

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        clearTempFiles()
        setContent { FormSnapTheme { FormSnapApp() } }
    }

    @Composable
    private fun FormSnapApp() {
        var settings by remember { mutableStateOf(OutputSettings()) }
        var settingsOpen by remember { mutableStateOf(false) }
        var source by remember { mutableStateOf<File?>(null) }
        var mode by remember { mutableStateOf(CaptureMode.WHOLE_FORM) }
        var saveMessage by remember { mutableStateOf<String?>(null) }
        var importKeepsCurrentMode by remember { mutableStateOf(false) }

        // Compose must consume the system Back button while an editor or
        // settings dialog is open. Previously only the top-bar Back button
        // changed state, so the Android Back button finished the Activity.
        BackHandler(enabled = settingsOpen) {
            settingsOpen = false
        }
        BackHandler(enabled = source != null && !settingsOpen) {
            clearTempFiles()
            source = null
        }

        val openCamera = rememberLauncherForActivityResult(
            ActivityResultContracts.TakePicture(),
        ) { ok ->
            val uri = cameraUri
            if (ok && uri != null) {
                source = uriToFile(uri, "camera")
            }
        }

        val requestCamera = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            if (granted) launchCamera(openCamera)
        }

        val openDocument = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri != null) {
                source = uriToFile(uri, "import")
                if (!importKeepsCurrentMode) {
                    mode = CaptureMode.WHOLE_FORM
                }
            }
        }

        val folderPicker = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocumentTree(),
        ) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            try {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                contentResolver.takePersistableUriPermission(uri, flags)
                prefs.edit().putString(
                    "${pendingFolderType}_directory_uri",
                    uri.toString(),
                ).apply()

                val saveType = pendingSaveType
                val savePath = pendingSavePath
                if (saveType != null && savePath != null) {
                    if (saveOutput(saveType, savePath, pendingSaveName)) {
                        saveMessage = "${saveType.replaceFirstChar { it.uppercase() }} saved successfully."
                    } else {
                        saveMessage = "Could not save the file."
                    }
                }
            } catch (t: Throwable) {
                saveMessage = t.message ?: "Folder permission failed."
            } finally {
                pendingSaveType = null
                pendingSavePath = null
                pendingSaveName = ""
            }
        }

        if (source == null) {
            HomeScreen(
                settings = settings,
                onSettings = { settingsOpen = true },
                onCamera = { selected ->
                    mode = selected
                    if (ContextCompat.checkSelfPermission(
                            this@MainActivity,
                            Manifest.permission.CAMERA,
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        launchCamera(openCamera)
                    } else {
                        requestCamera.launch(Manifest.permission.CAMERA)
                    }
                },
                onImport = { selected ->
                    importKeepsCurrentMode = true
                    mode = selected
                    openDocument.launch(arrayOf("image/*"))
                },
            )
        } else {
            EditorScreen(
                file = source!!,
                mode = mode,
                settings = settings,
                onSettings = { settingsOpen = true },
                onBack = {
                    clearTempFiles()
                    source = null
                },
                onCaptureAgain = {
                    if (ContextCompat.checkSelfPermission(
                            this@MainActivity,
                            Manifest.permission.CAMERA,
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        launchCamera(openCamera)
                    } else {
                        requestCamera.launch(Manifest.permission.CAMERA)
                    }
                },
                onImportAgain = {
                    importKeepsCurrentMode = true
                    openDocument.launch(arrayOf("image/*"))
                },
                onExtract = { processSource(source!!, mode, settings) },
                onSave = { type, path, personName ->
                    if (hasFolder(type)) {
                        saveMessage = if (saveOutput(type, path, personName)) {
                            "${type.replaceFirstChar { it.uppercase() }} saved successfully."
                        } else {
                            "Could not save the file."
                        }
                    } else {
                        pendingFolderType = type
                        pendingSaveType = type
                        pendingSavePath = path
                        pendingSaveName = personName
                        folderPicker.launch(null)
                    }
                },
                onFolder = { type ->
                    pendingFolderType = type
                    folderPicker.launch(null)
                },
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
        val dir = File(cacheDir, "captures").apply { mkdirs() }
        val file = File(dir, "capture_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(
            this,
            "${BuildConfig.APPLICATION_ID}.fileprovider",
            file,
        )
        cameraUri = uri
        launcher.launch(uri)
    }

    private fun uriToFile(uri: Uri, prefix: String): File? {
        return try {
            val dir = File(cacheDir, "inputs").apply { mkdirs() }
            val file = File(dir, "${prefix}_${System.currentTimeMillis()}.jpg")
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(file).use { output -> input.copyTo(output) }
            } ?: return null

            clearTempFiles(keep = setOf(file.absolutePath))
            file
        } catch (_: Throwable) {
            null
        }
    }

    private fun clearTempFiles(keep: Set<String> = emptySet()) {
        val roots = arrayOf(
            File(cacheDir, "captures"),
            File(cacheDir, "inputs"),
            File(cacheDir, "formsnap_outputs"),
        )
        for (root in roots) {
            if (!root.exists()) continue
            root.walkBottomUp().forEach { file ->
                if (file.isFile && file.absolutePath !in keep) file.delete()
            }
        }
    }

    private fun hasFolder(type: String): Boolean {
        val uriString = prefs.getString("${type}_directory_uri", null) ?: return false
        return try {
            val uri = Uri.parse(uriString)
            DocumentsContract.isTreeUri(uri) &&
                contentResolver.persistedUriPermissions.any {
                    it.uri == uri && it.isWritePermission
                }
        } catch (_: Throwable) {
            false
        }
    }

    private fun saveOutput(type: String, path: String, personName: String): Boolean {
        val uriString = prefs.getString("${type}_directory_uri", null) ?: return false
        val treeUri = Uri.parse(uriString)
        if (!hasFolder(type)) return false

        return try {
            val documentId = DocumentsContract.getTreeDocumentId(treeUri)
            val parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
            val safeName = sanitizePersonName(personName)
            if (safeName.isBlank()) return false
            val suffix = if (type == "signature") "sign" else "photo"
            val name = safeName + "-" + suffix + ".jpg"
            val target = DocumentsContract.createDocument(
                contentResolver,
                parent,
                "image/jpeg",
                name,
            ) ?: return false
            contentResolver.openOutputStream(target)?.use { output ->
                FileInputStream(path).use { input -> input.copyTo(output) }
            } ?: return false
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun sanitizePersonName(value: String): String {
        return value.trim()
            .replace(Regex("""[\\/:*?"<>|\r\n]+"""), "_")
            .replace(Regex("\\s+"), " ")
            .take(80)
            .trim(' ', '.', '_')
    }

    private suspend fun processSource(
        file: File,
        mode: CaptureMode,
        settings: OutputSettings,
    ): Outputs = withContext(Dispatchers.Default) {
        clearTempFiles(keep = setOf(file.absolutePath))
        if (!OpenCVLoader.initLocal()) error("OpenCV initialization failed")
        val nativeMode = when (mode) {
            CaptureMode.WHOLE_FORM -> "wholeForm"
            CaptureMode.PHOTO -> "closePhoto"
            CaptureMode.SIGNATURE -> "closeSignature"
        }
        val result = FormSnapOpenCvProcessor.process(
            this@MainActivity,
            mapOf(
                "sourcePath" to file.absolutePath,
                "mode" to nativeMode,
                "photoWidthMm" to settings.photoWidthMm,
                "photoHeightMm" to settings.photoHeightMm,
                "signatureWidthMm" to settings.signatureWidthMm,
                "signatureHeightMm" to settings.signatureHeightMm,
                "dpi" to settings.dpi,
                "maxKb" to settings.maxKb,
            ),
        )
        Outputs(
            photo = result["photoPath"] as? String,
            signature = result["signaturePath"] as? String,
        )
    }

    @Composable
    private fun HomeScreen(
        settings: OutputSettings,
        onSettings: () -> Unit,
        onCamera: (CaptureMode) -> Unit,
        onImport: (CaptureMode) -> Unit,
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("FormSnap", fontWeight = FontWeight.Bold) },
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
                            Text("Capture forms • extract photo & signature • resize • save")
                            Spacer(Modifier.height(8.dp))
                            Text("Class 8 • 2026–27 • offline processing")
                        }
                    }
                }
                item {
                    Text("Quick actions", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ActionCard("Whole Form", "Capture & extract", "▣", Modifier.weight(1f)) {
                            onCamera(CaptureMode.WHOLE_FORM)
                        }
                        ActionCard("Import Form", "Existing image", "▤", Modifier.weight(1f)) {
                            onImport(CaptureMode.WHOLE_FORM)
                        }
                    }
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
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ActionCard("Import Photo", "Photo only", "◉", Modifier.weight(1f)) {
                            onImport(CaptureMode.PHOTO)
                        }
                        ActionCard("Import Sign", "Signature only", "✎", Modifier.weight(1f)) {
                            onImport(CaptureMode.SIGNATURE)
                        }
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
        settings: OutputSettings,
        onSettings: () -> Unit,
        onBack: () -> Unit,
        onCaptureAgain: () -> Unit,
        onImportAgain: () -> Unit,
        onExtract: suspend () -> Outputs,
        onSave: (String, String, String) -> Unit,
        onFolder: (String) -> Unit,
    ) {
        var processing by remember { mutableStateOf(false) }
        var status by remember { mutableStateOf("Extracting…") }
        var outputs by remember { mutableStateOf(Outputs()) }
        var personName by remember { mutableStateOf("") }

        LaunchedEffect(file.absolutePath, mode, settings) {
            processing = true
            status = "Extracting photo & signature…"
            outputs = Outputs()
            try {
                outputs = onExtract()
                status = if (outputs.photo != null || outputs.signature != null) {
                    "Extraction complete"
                } else {
                    "Photo/signature could not be detected"
                }
            } catch (t: Throwable) {
                status = t.message ?: "Extraction failed"
            } finally {
                processing = false
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Preview & Extract", fontWeight = FontWeight.Bold) },
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
                            Text("Source image", Modifier.padding(16.dp), fontWeight = FontWeight.Bold)
                            BitmapImage(file, Modifier.fillMaxWidth().height(300.dp))
                        }
                    }
                }
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        ),
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                when (mode) {
                                    CaptureMode.WHOLE_FORM -> "Whole form extraction"
                                    CaptureMode.PHOTO -> "Photo extraction"
                                    CaptureMode.SIGNATURE -> "Signature extraction"
                                },
                                fontWeight = FontWeight.Bold,
                            )
                            Spacer(Modifier.height(5.dp))
                            Text(
                                "Photo ${fmt(settings.photoWidthMm)}×${fmt(settings.photoHeightMm)} mm • " +
                                    "Signature ${fmt(settings.signatureWidthMm)}×${fmt(settings.signatureHeightMm)} mm • " +
                                    "${settings.dpi.toInt()} DPI • ≤${settings.maxKb} KB",
                            )
                        }
                    }
                }
                item {
                    OutlinedTextField(
                        value = personName,
                        onValueChange = { personName = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Student / Person Name") },
                        placeholder = { Text("e.g. Ishant") },
                        supportingText = {
                            Text(
                                "Save as: " + personName.ifBlank { "PersonName" } +
                                    "-photo.jpg / -sign.jpg"
                            )
                        },
                    )
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
                        ) { Text("Capture Again") }
                        OutlinedButton(
                            onClick = onImportAgain,
                            modifier = Modifier.weight(1f),
                            enabled = !processing,
                        ) { Text("Import Again") }
                    }
                }
                item {
                    Text(status, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall)
                }
                outputs.photo?.let { path ->
                    item {
                        OutputCard(
                            "Photo", path,
                            "${fmt(settings.photoWidthMm)} × ${fmt(settings.photoHeightMm)} mm • ${settings.dpi.toInt()} DPI",
                            personName, onSave, onFolder,
                        )
                    }
                }
                outputs.signature?.let { path ->
                    item {
                        OutputCard(
                            "Signature", path,
                            "${fmt(settings.signatureWidthMm)} × ${fmt(settings.signatureHeightMm)} mm • ${settings.dpi.toInt()} DPI",
                            personName, onSave, onFolder,
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun OutputCard(
        title: String,
        path: String,
        subtitle: String,
        personName: String,
        onSave: (String, String, String) -> Unit,
        onFolder: (String) -> Unit,
    ) {
        val type = if (title == "Photo") "photo" else "signature"
        Card(shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(12.dp)) {
                Text(title, fontWeight = FontWeight.Bold, modifier = Modifier.padding(4.dp))
                BitmapImage(
                    File(path),
                    Modifier.fillMaxWidth().height(if (title == "Photo") 250.dp else 130.dp),
                )
                Spacer(Modifier.height(8.dp))
                Text(subtitle, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 4.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            if (personName.trim().isNotBlank()) {
                                onSave(type, path, personName)
                            }
                        },
                        enabled = personName.trim().isNotBlank(),
                        modifier = Modifier.weight(1f),
                    ) { Text("Save $title") }
                    OutlinedButton(
                        onClick = { onFolder(type) },
                        Modifier.weight(1f),
                    ) { Text("Folder") }
                }
            }
        }
    }

    @Composable
    private fun BitmapImage(file: File, modifier: Modifier = Modifier) {
        val bitmap = remember(file.absolutePath) { decodeSampled(file, 900) }
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
    MaterialTheme(colorScheme = lightColorScheme(), content = content)
}
