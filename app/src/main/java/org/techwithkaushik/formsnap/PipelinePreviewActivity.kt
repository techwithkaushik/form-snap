package org.techwithkaushik.formSnap

import org.techwithkaushik.formsnap.BuildConfig

import android.graphics.BitmapFactory
import android.graphics.RectF
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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

        val data = result.data ?: return@registerForActivityResult
        val left = data.getFloatExtra(CropCorrectionActivity.EXTRA_LEFT, Float.NaN)
        val top = data.getFloatExtra(CropCorrectionActivity.EXTRA_TOP, Float.NaN)
        val right = data.getFloatExtra(CropCorrectionActivity.EXTRA_RIGHT, Float.NaN)
        val bottom = data.getFloatExtra(CropCorrectionActivity.EXTRA_BOTTOM, Float.NaN)
        if (listOf(left, top, right, bottom).all { it.isFinite() } &&
            right > left && bottom > top
        ) {
            onExternalCorrection?.invoke(kind, RectF(left, top, right, bottom))
        }
    }

    private var activePipelineViewModel: PipelinePreviewViewModel? = null
    private var onExternalCorrection: ((DetectionKind, RectF) -> Unit)? = null

    private var correctionKindForResult: DetectionKind? = null
    private var pendingFolderKind: DetectionKind? = null
    private var pendingSavePath: String? = null
    private var pendingPersonName: String? = null
    private var pendingSignatureAsJpeg: Boolean? = null
    private var cameraSourceFile: File? = null
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val cameraLauncher: ActivityResultLauncher<Uri> =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
            val file = cameraSourceFile
            cameraSourceFile = null
            if (!ok) {
                file?.delete()
                return@registerForActivityResult
            }
            if (file?.isFile == true && file.length() > 0L) {
                recreatePipelineWithInput(file)
            } else {
                android.widget.Toast.makeText(
                    this,
                    "Camera did not produce an image. Please try again.",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
        }

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            ioScope.launch {
                try {
                    val file = withContext(Dispatchers.IO) {
                        val mime = contentResolver.getType(uri)
                        val extension = when (mime?.lowercase()) {
                            "image/png" -> "png"
                            "image/webp" -> "webp"
                            "image/heic", "image/heif" -> "heic"
                            else -> "jpg"
                        }
                        File(
                            org.techwithkaushik.formSnap.foundation.ProcessingPaths.root(this@PipelinePreviewActivity),
                            "inputs/reimport_" + System.nanoTime() + ".$extension",
                        ).apply { parentFile?.mkdirs() }.also { destination ->
                            contentResolver.openInputStream(uri)?.use { input ->
                                destination.outputStream().use { output -> input.copyTo(output) }
                            } ?: error("Unable to open the selected image.")
                            check(destination.isFile && destination.length() > 0L) {
                                "The selected image is empty."
                            }
                        }
                    }
                    if (!isFinishing && !isDestroyed) recreatePipelineWithInput(file)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    android.widget.Toast.makeText(
                        this@PipelinePreviewActivity,
                        error.message ?: "Could not import this image.",
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }

    private lateinit var cameraUri: Uri

    private fun launchRecapture() {
        val file = File(
            org.techwithkaushik.formSnap.foundation.ProcessingPaths.root(this),
            "inputs/captures/recapture_source_" + System.nanoTime() + ".jpg",
        ).apply { parentFile?.mkdirs() }
        cameraSourceFile = file
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
            val saveMessage = remember { mutableStateOf<String?>(null) }
            val saving = remember { mutableStateOf(false) }
            val prefs = remember {
                getSharedPreferences("formsnap_storage", MODE_PRIVATE)
            }
            val personName = remember {
                mutableStateOf(prefs.getString("person_name", "") ?: "")
            }
            val signatureAsJpeg = remember {
                mutableStateOf(prefs.getBoolean("signature_as_jpeg", false))
            }

            val folderPicker = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocumentTree(),
            ) { uri ->
                val kind = pendingFolderKind
                val pathToSave = pendingSavePath
                val nameToSave = pendingPersonName.orEmpty()
                val signatureJpegToSave = pendingSignatureAsJpeg
                    ?: prefs.getBoolean("signature_as_jpeg", false)
                pendingFolderKind = null
                pendingSavePath = null
                pendingPersonName = null
                pendingSignatureAsJpeg = null
                if (uri == null || kind == null) {
                    saveMessage.value = if (uri == null) "Folder selection cancelled." else null
                } else {
                    try {
                        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        contentResolver.takePersistableUriPermission(uri, flags)
                        prefs.edit().putString("output_directory_uri", uri.toString()).apply()
                        if (pathToSave != null) {
                            scope.launch {
                                saving.value = true
                                saveMessage.value = try {
                                    saveOutputToFolder(kind, pathToSave, uri, nameToSave, signatureJpegToSave)
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (t: Exception) {
                                    t.message ?: "Save failed."
                                } finally {
                                    saving.value = false
                                }
                            }
                        } else {
                            saveMessage.value = "${kind.label()} folder selected."
                        }
                    } catch (t: Exception) {
                        saveMessage.value = t.message ?: "Unable to remember folder permission."
                    }
                }
            }

            fun saveOrChooseFolder(kind: DetectionKind, path: String?) {
                if (path.isNullOrBlank() || !File(path).isFile) {
                    saveMessage.value = "No processed ${kind.label().lowercase()} is available to save."
                    return
                }
                val legacyUri = prefs.getString("${kind.legacyFolderKey()}_directory_uri", null)
                val savedUriString = prefs.getString("output_directory_uri", null) ?: legacyUri
                if (savedUriString != null && prefs.getString("output_directory_uri", null) == null) {
                    prefs.edit().putString("output_directory_uri", savedUriString).apply()
                }
                val savedUri = savedUriString?.let(Uri::parse)
                val hasPermission = savedUri != null &&
                    contentResolver.persistedUriPermissions.any {
                        it.uri == savedUri && it.isWritePermission
                    }
                if (hasPermission && savedUri != null) {
                    scope.launch {
                        saving.value = true
                        saveMessage.value = try {
                            saveOutputToFolder(kind, path, savedUri, personName.value, signatureAsJpeg.value)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (t: Exception) {
                            t.message ?: "Save failed."
                        } finally {
                            saving.value = false
                        }
                    }
                } else {
                    pendingFolderKind = kind
                    pendingSavePath = path
                    pendingPersonName = personName.value
                    pendingSignatureAsJpeg = signatureAsJpeg.value
                    folderPicker.launch(null)
                }
            }
            onExternalCorrection = { kind, bounds ->
                scope.launch { viewModel.applyExternalCorrection(kind, bounds) }
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
                ),
            ) {
                value = withContext(Dispatchers.IO) {
                    state.photoPreviewPath?.let { decodePreviewBitmap(it, maxDimension = 900) }
                }
            }
            val signatureBitmap by produceState<android.graphics.Bitmap?>(
                initialValue = null,
                key1 = listOf(
                    state.signaturePreviewPath,
                    state.signaturePreviewVersion,
                ),
            ) {
                value = withContext(Dispatchers.IO) {
                    state.signaturePreviewPath?.let { decodePreviewBitmap(it, maxDimension = 900) }
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
                personName = personName.value,
                onPersonNameChange = { value ->
                    personName.value = value
                    prefs.edit().putString("person_name", value).apply()
                },
                signatureAsJpeg = signatureAsJpeg.value,
                onSignatureAsJpegChange = { enabled ->
                    signatureAsJpeg.value = enabled
                    prefs.edit().putBoolean("signature_as_jpeg", enabled).apply()
                },
                saving = saving.value,
                onProcess = {
                    scope.launch {
                        viewModel.redetect()
                    }
                },
                onRecapture = { launchRecapture() },
                onReimport = { launchImport() },
                onEditPhoto = {
                    val source = state.source
                    val bounds = state.photoState?.currentBounds
                    if (source?.isFile == true && bounds != null) {
                        correctionKindForResult = DetectionKind.PHOTO
                        openDetectedEditor(source, DetectionKind.PHOTO, bounds)
                    }
                },
                onAcceptPhoto = {
                    scope.launch { viewModel.accept(DetectionKind.PHOTO) }
                },
                onRejectPhoto = {
                    viewModel.reject(DetectionKind.PHOTO)
                },
                onEditSignature = {
                    val source = state.source
                    val bounds = state.signatureState?.currentBounds
                    if (source?.isFile == true && bounds != null) {
                        correctionKindForResult = DetectionKind.SIGNATURE
                        openDetectedEditor(source, DetectionKind.SIGNATURE, bounds)
                    }
                },
                onAcceptSignature = {
                    scope.launch { viewModel.accept(DetectionKind.SIGNATURE) }
                },
                onRejectSignature = {
                    viewModel.reject(DetectionKind.SIGNATURE)
                },
                onSavePhoto = {
                    saveOrChooseFolder(
                        DetectionKind.PHOTO,
                        state.photoPreviewPath,
                    )
                },
                onChoosePhotoFolder = {
                    pendingFolderKind = DetectionKind.PHOTO
                    pendingSavePath = null
                    pendingPersonName = personName.value
                    pendingSignatureAsJpeg = signatureAsJpeg.value
                    folderPicker.launch(null)
                },
                onSaveSignature = {
                    saveOrChooseFolder(
                        DetectionKind.SIGNATURE,
                        state.signaturePreviewPath,
                    )
                },
                onChooseSignatureFolder = {
                    pendingFolderKind = DetectionKind.SIGNATURE
                    pendingSavePath = null
                    pendingPersonName = personName.value
                    pendingSignatureAsJpeg = signatureAsJpeg.value
                    folderPicker.launch(null)
                },
                onBack = {
                    finish()
                },
            )
        }
    }

    override fun onDestroy() {
        ioScope.cancel()
        cameraSourceFile = null
        activePipelineViewModel?.close()
        activePipelineViewModel = null
        super.onDestroy()
    }


    private suspend fun saveOutputToFolder(
        kind: DetectionKind,
        sourcePath: String,
        treeUri: Uri,
        personName: String,
        signatureAsJpeg: Boolean,
    ): String = withContext(Dispatchers.IO) {
        val maxKb = intent.getIntExtra(EXTRA_MAX_KB, 50).coerceIn(5, 2048)
        val isSignature = kind == DetectionKind.SIGNATURE
        val usePng = isSignature && !signatureAsJpeg
        val bytes = if (usePng) {
            SavedImageEncoder.encodePngWithinLimit(File(sourcePath), maxKb)
        } else {
            SavedImageEncoder.encodeWithinLimit(File(sourcePath), maxKb)
        }
        val documentId = DocumentsContract.getTreeDocumentId(treeUri)
        val parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
        val childDocuments = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        val existingNames = mutableSetOf<String>()
        contentResolver.query(
            childDocuments,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            val nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            while (cursor.moveToNext()) existingNames += cursor.getString(nameColumn)
        }
        val baseName = OutputFileNaming.desiredName(kind, personName, signatureAsJpeg)
        var fileName = baseName
        var suffix = 1
        while (fileName in existingNames) fileName = OutputFileNaming.withSuffix(baseName, suffix++)
        val mimeType = if (usePng) "image/png" else "image/jpeg"
        val target = DocumentsContract.createDocument(
            contentResolver,
            parent,
            mimeType,
            fileName,
        ) ?: error("The selected folder refused to create the output file.")

        try {
            contentResolver.openOutputStream(target, "w")?.use { output ->
                output.write(bytes)
                output.flush()
            } ?: error("Cannot open the selected output file.")
            val writtenBytes = contentResolver.openInputStream(target)?.use { it.readBytes().size }
                ?: error("Could not verify the saved output.")
            check(writtenBytes == bytes.size) { "Saved output verification failed." }
            val sizeKb = String.format(java.util.Locale.US, "%.1f", bytes.size / 1024.0)
            "${kind.label()} saved as $fileName ($sizeKb KB)."
        } catch (t: Throwable) {
            runCatching { DocumentsContract.deleteDocument(contentResolver, target) }
            throw t
        }
    }

    private fun DetectionKind.folderKey(): String = "output"

    private fun DetectionKind.legacyFolderKey(): String =
        if (this == DetectionKind.PHOTO) "photo" else "signature"

    private fun DetectionKind.label(): String =
        if (this == DetectionKind.PHOTO) "Photo" else "Signature"

    private fun openDetectedEditor(source: File, kind: DetectionKind, bounds: RectF) {
        val cropIntent = Intent(this, CropCorrectionActivity::class.java).apply {
            putExtra(CropCorrectionActivity.EXTRA_SOURCE_PATH, source.absolutePath)
            putExtra(CropCorrectionActivity.EXTRA_KIND, kind.name)
            putExtra(CropCorrectionActivity.EXTRA_LEFT, bounds.left)
            putExtra(CropCorrectionActivity.EXTRA_TOP, bounds.top)
            putExtra(CropCorrectionActivity.EXTRA_RIGHT, bounds.right)
            putExtra(CropCorrectionActivity.EXTRA_BOTTOM, bounds.bottom)
        }
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
