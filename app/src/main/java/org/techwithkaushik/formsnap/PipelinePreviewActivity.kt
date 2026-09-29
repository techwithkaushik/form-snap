package org.techwithkaushik.formSnap

import org.techwithkaushik.formsnap.BuildConfig

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.content.Intent
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.produceState
import com.yalantis.ucrop.UCrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.techwithkaushik.formSnap.pipeline.DetectionKind
import org.techwithkaushik.formSnap.pipeline.PipelinePreviewViewModel
import org.techwithkaushik.formSnap.ui.PipelinePreviewScreen
import java.io.File

class PipelinePreviewActivity : ComponentActivity() {

    private val correctionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val kind = correctionKindForResult ?: return@registerForActivityResult
        correctionKindForResult = null
        if (result.resultCode != RESULT_OK) return@registerForActivityResult

        val resultUri = result.data?.let { UCrop.getOutput(it) } ?: return@registerForActivityResult
        val resultPath = resultUri.path ?: return@registerForActivityResult
        onExternalCorrection?.invoke(kind, File(resultPath))
    }

    private var activePipelineViewModel: PipelinePreviewViewModel? = null
    private var onExternalCorrection: ((DetectionKind, File) -> Unit)? = null

    private var correctionKindForResult: DetectionKind? = null
    private var pendingFolderKind: DetectionKind? = null
    private var pendingSavePath: String? = null

    private val cameraLauncher: ActivityResultLauncher<Uri> =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
            if (!ok) return@registerForActivityResult
            val uri = cameraUri
            val file = File(
                org.techwithkaushik.formSnap.foundation.ProcessingPaths.root(this@PipelinePreviewActivity),
                "inputs/recapture_" + System.nanoTime() + ".jpg",
            ).apply { parentFile?.mkdirs() }
            contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
            if (file.exists()) recreatePipelineWithInput(file)
        }

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            val file = File(
                org.techwithkaushik.formSnap.foundation.ProcessingPaths.root(this@PipelinePreviewActivity),
                "inputs/reimport_" + System.nanoTime() + ".jpg",
            ).apply { parentFile?.mkdirs() }
            contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
            if (file.exists()) recreatePipelineWithInput(file)
        }

    private lateinit var cameraUri: Uri

    private fun launchRecapture() {
        val file = File(
            org.techwithkaushik.formSnap.foundation.ProcessingPaths.root(this),
            "inputs/captures/recapture_source_" + System.nanoTime() + ".jpg",
        ).apply { parentFile?.mkdirs() }
        cameraUri = androidx.core.content.FileProvider.getUriForFile(
            this,
            BuildConfig.APPLICATION_ID + ".fileprovider",
            file,
        )
        cameraLauncher.launch(cameraUri)
    }

    private fun launchImport() {
        importLauncher.launch(arrayOf("image/*"))
    }

    private fun recreatePipelineWithInput(file: File) {
        val launchIntent = android.content.Intent(this, PipelinePreviewActivity::class.java).apply {
            putExtra(EXTRA_INPUT_PATH, file.absolutePath)
            putExtra(EXTRA_MAX_KB, intent.getIntExtra(EXTRA_MAX_KB, 50))
            putExtra(EXTRA_DPI, intent.getDoubleExtra(EXTRA_DPI, 300.0))
            putExtra(EXTRA_PHOTO_WIDTH_MM, intent.getDoubleExtra(EXTRA_PHOTO_WIDTH_MM, 40.0))
            putExtra(EXTRA_PHOTO_HEIGHT_MM, intent.getDoubleExtra(EXTRA_PHOTO_HEIGHT_MM, 50.0))
            putExtra(EXTRA_SIGNATURE_WIDTH_MM, intent.getDoubleExtra(EXTRA_SIGNATURE_WIDTH_MM, 50.0))
            putExtra(EXTRA_SIGNATURE_HEIGHT_MM, intent.getDoubleExtra(EXTRA_SIGNATURE_HEIGHT_MM, 20.0))
        }
        startActivity(launchIntent)
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val path = intent.getStringExtra(EXTRA_INPUT_PATH)
        val dpi = intent.getDoubleExtra(EXTRA_DPI, 300.0)
        val photoWidthMm = intent.getDoubleExtra(EXTRA_PHOTO_WIDTH_MM, 40.0)
        val photoHeightMm = intent.getDoubleExtra(EXTRA_PHOTO_HEIGHT_MM, 50.0)
        val signatureWidthMm = intent.getDoubleExtra(EXTRA_SIGNATURE_WIDTH_MM, 50.0)
        val signatureHeightMm = intent.getDoubleExtra(EXTRA_SIGNATURE_HEIGHT_MM, 20.0)
        if (path.isNullOrBlank()) {
            finish()
            return
        }

        setContent {
            val viewModel = remember {
                PipelinePreviewViewModel(applicationContext).also {
                    activePipelineViewModel = it
                }
            }
            val state by viewModel.state.collectAsState()
            val scope = rememberCoroutineScope()
            val editKind = remember { mutableStateOf<DetectionKind?>(null) }
            val saveMessage = remember { mutableStateOf<String?>(null) }
            val saving = remember { mutableStateOf(false) }
            val prefs = remember {
                getSharedPreferences("formsnap_storage", MODE_PRIVATE)
            }

            val folderPicker = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocumentTree(),
            ) { uri ->
                val kind = pendingFolderKind
                val pathToSave = pendingSavePath
                pendingFolderKind = null
                pendingSavePath = null
                if (uri == null || kind == null) {
                    saveMessage.value = if (uri == null) "Folder selection cancelled." else null
                } else {
                    try {
                        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        contentResolver.takePersistableUriPermission(uri, flags)
                        prefs.edit().putString("${kind.folderKey()}_directory_uri", uri.toString()).apply()
                        if (pathToSave != null) {
                            scope.launch {
                                saving.value = true
                                saveMessage.value = try {
                                    saveOutputToFolder(kind, pathToSave, uri)
                                } catch (t: Throwable) {
                                    t.message ?: "Save failed."
                                } finally {
                                    saving.value = false
                                }
                            }
                        } else {
                            saveMessage.value = "${kind.label()} folder selected."
                        }
                    } catch (t: Throwable) {
                        saveMessage.value = t.message ?: "Unable to remember folder permission."
                    }
                }
            }

            fun saveOrChooseFolder(kind: DetectionKind, path: String?) {
                if (path.isNullOrBlank() || !File(path).isFile) {
                    saveMessage.value = "No processed ${kind.label().lowercase()} is available to save."
                    return
                }
                val savedUri = prefs.getString("${kind.folderKey()}_directory_uri", null)
                    ?.let(Uri::parse)
                val hasPermission = savedUri != null &&
                    contentResolver.persistedUriPermissions.any {
                        it.uri == savedUri && it.isWritePermission
                    }
                if (hasPermission && savedUri != null) {
                    scope.launch {
                        saving.value = true
                        saveMessage.value = try {
                            saveOutputToFolder(kind, path, savedUri)
                        } catch (t: Throwable) {
                            t.message ?: "Save failed."
                        } finally {
                            saving.value = false
                        }
                    }
                } else {
                    pendingFolderKind = kind
                    pendingSavePath = path
                    folderPicker.launch(null)
                }
            }
            val editedPhotoPath = remember { mutableStateOf<String?>(null) }
            val editedSignaturePath = remember { mutableStateOf<String?>(null) }
            val editedPhotoVersion = remember { mutableStateOf(0L) }
            val editedSignatureVersion = remember { mutableStateOf(0L) }

            onExternalCorrection = { kind, file ->
                when (kind) {
                    DetectionKind.PHOTO -> {
                        editedPhotoPath.value = file.absolutePath
                        editedPhotoVersion.value += 1L
                    }
                    DetectionKind.SIGNATURE -> {
                        editedSignaturePath.value = file.absolutePath
                        editedSignatureVersion.value += 1L
                    }
                }
                editKind.value = kind
            }

            LaunchedEffect(path) {
                viewModel.load(
                    input = File(path),
                    dpi = dpi.toInt(),
                    photoWidthMm = photoWidthMm,
                    photoHeightMm = photoHeightMm,
                    signatureWidthMm = signatureWidthMm,
                    signatureHeightMm = signatureHeightMm,
                )
            }

            // Decode display thumbnails off the main thread and cap their dimensions.
            // Full-resolution decoding here could block Compose and allocate hundreds of MB.
            val inputBitmap by produceState<android.graphics.Bitmap?>(
                initialValue = null,
                key1 = state.source?.absolutePath,
            ) {
                value = withContext(Dispatchers.IO) {
                    state.source?.let { decodePreviewBitmap(it.absolutePath, maxDimension = 1200) }
                }
            }
            val photoBitmap by produceState<android.graphics.Bitmap?>(
                initialValue = null,
                key1 = listOf(
                    state.photoPreviewPath,
                    state.photoPreviewVersion,
                    editedPhotoPath.value,
                    editedPhotoVersion.value,
                ),
            ) {
                val previewPath = editedPhotoPath.value ?: state.photoPreviewPath
                value = withContext(Dispatchers.IO) {
                    previewPath?.let { decodePreviewBitmap(it, maxDimension = 900) }
                }
            }
            val signatureBitmap by produceState<android.graphics.Bitmap?>(
                initialValue = null,
                key1 = listOf(
                    state.signaturePreviewPath,
                    state.signaturePreviewVersion,
                    editedSignaturePath.value,
                    editedSignatureVersion.value,
                ),
            ) {
                val previewPath = editedSignaturePath.value ?: state.signaturePreviewPath
                value = withContext(Dispatchers.IO) {
                    previewPath?.let { decodePreviewBitmap(it, maxDimension = 900) }
                }
            }

            val detectionMessage = when {
                state.processing -> null
                state.photoState == null && state.signatureState == null ->
                    "No photo or signature was confidently detected. Try a clearer, closer image with even lighting."
                state.photoState != null && state.signatureState == null ->
                    "Photo detected. No signature was confidently detected."
                state.photoState == null && state.signatureState != null ->
                    "Signature detected. No photo was confidently detected."
                else -> null
            }

            PipelinePreviewScreen(
                inputPreview = inputBitmap,
                photoPreview = photoBitmap,
                signaturePreview = signatureBitmap,
                photoDetected = state.photoState != null && state.photoPreviewPath != null,
                signatureDetected = state.signatureState != null && state.signaturePreviewPath != null,
                photoConfidence = state.photoConfidence,
                signatureConfidence = state.signatureConfidence,
                processing = state.processing,
                message = saveMessage.value ?: state.error ?: detectionMessage,
                saving = saving.value,
                onProcess = {
                    scope.launch {
                        viewModel.redetect()
                    }
                },
                onRecapture = { launchRecapture() },
                onReimport = { launchImport() },
                onEditPhoto = {
                    val currentPath = editedPhotoPath.value ?: state.photoPreviewPath
                    val file = currentPath?.let(::File)
                    if (state.photoState != null && file?.exists() == true) {
                        correctionKindForResult = DetectionKind.PHOTO
                        openDetectedEditor(file, DetectionKind.PHOTO)
                    }
                },
                onAcceptPhoto = {
                    scope.launch {
                        if (editedPhotoPath.value != null) {
                            viewModel.accept(DetectionKind.PHOTO, recordFeedback = false)
                            saveMessage.value =
                                "Edited photo accepted. Automatic learning was skipped because the editor does not provide source-image crop coordinates."
                        } else {
                            viewModel.accept(DetectionKind.PHOTO)
                        }
                    }
                },
                onRejectPhoto = {
                    viewModel.reject(DetectionKind.PHOTO)
                },
                onEditSignature = {
                    val currentPath = editedSignaturePath.value ?: state.signaturePreviewPath
                    val file = currentPath?.let(::File)
                    if (state.signatureState != null && file?.exists() == true) {
                        correctionKindForResult = DetectionKind.SIGNATURE
                        openDetectedEditor(file, DetectionKind.SIGNATURE)
                    }
                },
                onAcceptSignature = {
                    scope.launch {
                        if (editedSignaturePath.value != null) {
                            viewModel.accept(DetectionKind.SIGNATURE, recordFeedback = false)
                            saveMessage.value =
                                "Edited signature accepted. Automatic learning was skipped because the editor does not provide source-image crop coordinates."
                        } else {
                            viewModel.accept(DetectionKind.SIGNATURE)
                        }
                    }
                },
                onRejectSignature = {
                    viewModel.reject(DetectionKind.SIGNATURE)
                },
                onSavePhoto = {
                    saveOrChooseFolder(
                        DetectionKind.PHOTO,
                        editedPhotoPath.value ?: state.photoPreviewPath,
                    )
                },
                onChoosePhotoFolder = {
                    pendingFolderKind = DetectionKind.PHOTO
                    pendingSavePath = null
                    folderPicker.launch(null)
                },
                onSaveSignature = {
                    saveOrChooseFolder(
                        DetectionKind.SIGNATURE,
                        editedSignaturePath.value ?: state.signaturePreviewPath,
                    )
                },
                onChooseSignatureFolder = {
                    pendingFolderKind = DetectionKind.SIGNATURE
                    pendingSavePath = null
                    folderPicker.launch(null)
                },
                onBack = {
                    finish()
                },
            )
        }
    }

    override fun onDestroy() {
        activePipelineViewModel?.close()
        activePipelineViewModel = null
        super.onDestroy()
    }


    private suspend fun saveOutputToFolder(
        kind: DetectionKind,
        sourcePath: String,
        treeUri: Uri,
    ): String = withContext(Dispatchers.IO) {
        val maxKb = intent.getIntExtra(EXTRA_MAX_KB, 50)
            .coerceIn(5, 2048)
        val bytes = SavedImageEncoder.encodeWithinLimit(File(sourcePath), maxKb)
        val documentId = DocumentsContract.getTreeDocumentId(treeUri)
        val parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
        val fileName = "FormSnap-${kind.name.lowercase()}-${System.currentTimeMillis()}.jpg"
        val target = DocumentsContract.createDocument(
            contentResolver,
            parent,
            "image/jpeg",
            fileName,
        ) ?: error("The selected folder refused to create the output file.")

        try {
            contentResolver.openOutputStream(target, "w")?.use { output ->
                output.write(bytes)
                output.flush()
            } ?: error("Cannot open the selected output file.")
            val sizeKb = String.format(java.util.Locale.US, "%.1f", bytes.size / 1024.0)
            "${kind.label()} saved ($sizeKb KB)."
        } catch (t: Throwable) {
            runCatching { DocumentsContract.deleteDocument(contentResolver, target) }
            throw t
        }
    }

    private fun DetectionKind.folderKey(): String =
        if (this == DetectionKind.PHOTO) "photo" else "signature"

    private fun DetectionKind.label(): String =
        if (this == DetectionKind.PHOTO) "Photo" else "Signature"

    private fun openDetectedEditor(source: File, kind: DetectionKind) {
        val destination = File(
            org.techwithkaushik.formSnap.foundation.ProcessingPaths.root(this),
            "ucrop_" + System.nanoTime() + "_" + kind.name.lowercase() + ".jpg",
        )

        val options = UCrop.Options().apply {
            setFreeStyleCropEnabled(true)
            setCompressionQuality(95)
            setShowCropGrid(true)
            setShowCropFrame(true)
        }

        val cropIntent = UCrop.of(
            Uri.fromFile(source),
            Uri.fromFile(destination),
        )
            .withOptions(options)
            .withMaxResultSize(1600, 1600)
            .getIntent(this)
        correctionLauncher.launch(cropIntent)
    }

    companion object {
        const val EXTRA_INPUT_PATH = "formsnap.input_path"
        const val EXTRA_MAX_KB = "formsnap.max_kb"
        const val EXTRA_DPI = "formsnap.dpi"
        const val EXTRA_PHOTO_WIDTH_MM = "formsnap.photo_width_mm"
        const val EXTRA_PHOTO_HEIGHT_MM = "formsnap.photo_height_mm"
        const val EXTRA_SIGNATURE_WIDTH_MM = "formsnap.signature_width_mm"
        const val EXTRA_SIGNATURE_HEIGHT_MM = "formsnap.signature_height_mm"
        const val EXTRA_EXTERNAL_CORRECTION_PATH = "formsnap.external_correction_path"
        const val EXTRA_EXTERNAL_CORRECTION_KIND = "formsnap.external_correction_kind"
    }
}

/** Loads a display-only thumbnail; processing continues to use the full-resolution source. */
private fun decodePreviewBitmap(path: String, maxDimension: Int): android.graphics.Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sample = 1
    while (bounds.outWidth / sample > maxDimension ||
        bounds.outHeight / sample > maxDimension
    ) {
        sample *= 2
    }
    val options = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
    }
    return BitmapFactory.decodeFile(path, options)
}
