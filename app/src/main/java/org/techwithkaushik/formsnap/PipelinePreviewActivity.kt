package org.techwithkaushik.formSnap

import org.techwithkaushik.formsnap.BuildConfig

import android.graphics.BitmapFactory
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.content.Intent
import android.content.ClipData
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import com.yalantis.ucrop.UCrop

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

    private var pendingCropOutputFile: File? = null
    private var pendingCropSourceFile: File? = null
    private var pendingCropSourceBounds: RectF? = null

    private val cropLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val kind = correctionKindForResult
        correctionKindForResult = null
        val output = pendingCropOutputFile
        val seed = pendingCropSourceFile
        val seedBounds = pendingCropSourceBounds
        pendingCropOutputFile = null
        pendingCropSourceFile = null
        pendingCropSourceBounds = null
        if (kind == null || output == null) {
            seed?.delete()
            return@registerForActivityResult
        }

        if (result.resultCode == RESULT_OK && output.isFile && output.length() > 0L) {
            ioScope.launch {
                try {
                    val cropBounds = result.data?.let { data ->
                        val offsetX = data.getIntExtra(UCrop.EXTRA_OUTPUT_OFFSET_X, -1)
                        val offsetY = data.getIntExtra(UCrop.EXTRA_OUTPUT_OFFSET_Y, -1)
                        val cropWidth = data.getIntExtra(UCrop.EXTRA_OUTPUT_IMAGE_WIDTH, -1)
                        val cropHeight = data.getIntExtra(UCrop.EXTRA_OUTPUT_IMAGE_HEIGHT, -1)
                        val origin = seedBounds
                        if (origin != null && offsetX >= 0 && offsetY >= 0 &&
                            cropWidth > 0 && cropHeight > 0
                        ) RectF(
                            origin.left + offsetX,
                            origin.top + offsetY,
                            origin.left + offsetX + cropWidth,
                            origin.top + offsetY + cropHeight,
                        ) else null
                    }
                    activePipelineViewModel?.replacePreviewFromExternal(kind, output, cropBounds)
                        ?: error("The preview processor is no longer available. Please reopen the crop editor.")
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    android.widget.Toast.makeText(
                        this@PipelinePreviewActivity,
                        error.localizedMessage ?: "Unable to apply crop.",
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                } finally {
                    output.delete()
                    seed?.delete()
                }
            }
        } else if (result.resultCode == RESULT_OK) {
            android.widget.Toast.makeText(
                this,
                "Crop completed without an output file. Please adjust the crop and tap the checkmark again.",
                android.widget.Toast.LENGTH_LONG,
            ).show()
            output.delete()
            seed?.delete()
        } else if (result.resultCode == UCrop.RESULT_ERROR) {
            val error = result.data?.let { UCrop.getError(it) }
            android.widget.Toast.makeText(
                this,
                error?.localizedMessage ?: "Crop failed. Please try again.",
                android.widget.Toast.LENGTH_LONG,
            ).show()
            output.delete()
            seed?.delete()
        } else {
            output.delete()
            seed?.delete()
        }
    }

    private var activePipelineViewModel: PipelinePreviewViewModel? = null

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
        )
        try {
            check(file.parentFile?.isDirectory == true || file.parentFile?.mkdirs() == true) {
                "Cannot create the camera output directory."
            }
            cameraSourceFile = file
            cameraUri = androidx.core.content.FileProvider.getUriForFile(
                this,
                BuildConfig.APPLICATION_ID + ".fileprovider",
                file,
            )
            cameraLauncher.launch(cameraUri)
        } catch (error: Exception) {
            cameraSourceFile = null
            file.delete()
            android.widget.Toast.makeText(
                this,
                error.message ?: "Unable to open the camera. Please try again.",
                android.widget.Toast.LENGTH_LONG,
            ).show()
        }
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
            val savingKind = remember { mutableStateOf<DetectionKind?>(null) }
            val photoSaved = remember { mutableStateOf(false) }
            val signatureSaved = remember { mutableStateOf(false) }
            val prefs = remember {
                getSharedPreferences("formsnap_storage", MODE_PRIVATE)
            }
            // Person name is session-specific and intentionally resets for every imported image.
            val personName = remember(path) { mutableStateOf("") }
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
                                savingKind.value = kind
                                saveMessage.value = try {
                                    saveOutputToFolder(kind, pathToSave, uri, nameToSave, signatureJpegToSave)
                                        .also { result ->
                                            val succeeded = result.startsWith("${kind.label()} saved as")
                                            if (kind == DetectionKind.PHOTO) photoSaved.value = succeeded
                                            else signatureSaved.value = succeeded
                                        }
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (t: Exception) {
                                    if (kind == DetectionKind.PHOTO) photoSaved.value = false
                                    else signatureSaved.value = false
                                    t.message ?: "Save failed."
                                } finally {
                                    savingKind.value = null
                                }
                            }
                        } else {
                            saveMessage.value = "Output folder selected. Both photo and signature will be saved here."
                        }
                    } catch (t: Exception) {
                        saveMessage.value = t.message ?: "Unable to remember folder permission."
                    }
                }
            }

            fun saveOrChooseFolder(kind: DetectionKind, path: String?) {
                val requiredName = personName.value.trim()
                if (requiredName.isBlank()) {
                    saveMessage.value = "Enter the person's name before saving the photo or signature."
                    return
                }
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
                        savingKind.value = kind
                        saveMessage.value = try {
                            saveOutputToFolder(kind, path, savedUri, requiredName, signatureAsJpeg.value)
                                .also { result ->
                                    val succeeded = result.startsWith("${kind.label()} saved as")
                                    if (kind == DetectionKind.PHOTO) photoSaved.value = succeeded
                                    else signatureSaved.value = succeeded
                                }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (t: Exception) {
                            if (kind == DetectionKind.PHOTO) photoSaved.value = false
                            else signatureSaved.value = false
                            t.message ?: "Save failed."
                        } finally {
                            savingKind.value = null
                        }
                    }
                } else {
                    pendingFolderKind = kind
                    pendingSavePath = path
                    pendingPersonName = requiredName
                    pendingSignatureAsJpeg = signatureAsJpeg.value
                    folderPicker.launch(null)
                }
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
                state.photoState == null && state.signatureState == null &&
                    state.photoPreviewPath == null && state.signaturePreviewPath == null ->
                    "No photo or signature was confidently detected. Select a crop manually if automatic detection misses either region."
                state.photoPreviewPath != null && state.signaturePreviewPath == null ->
                    "Photo ready. No signature was confidently detected; use Select crop to choose it manually."
                state.photoPreviewPath == null && state.signaturePreviewPath != null ->
                    "Signature ready. No photo was confidently detected; use Select crop to choose it manually."
                else -> null
            }

            PipelinePreviewScreen(
                inputPreview = inputBitmap,
                photoPreview = photoBitmap,
                signaturePreview = signatureBitmap,
                // A manually selected crop is a valid output even without an automatic detection state.
                photoDetected = state.photoPreviewPath != null,
                signatureDetected = state.signaturePreviewPath != null,
                photoConfidence = state.photoConfidence,
                signatureConfidence = state.signatureConfidence,
                processing = state.processing,
                message = saveMessage.value ?: state.error ?: detectionMessage,
                personName = personName.value,
                onPersonNameChange = { value ->
                    personName.value = value
                    photoSaved.value = false
                    signatureSaved.value = false
                },
                signatureAsJpeg = signatureAsJpeg.value,
                onSignatureAsJpegChange = { enabled ->
                    signatureAsJpeg.value = enabled
                    prefs.edit().putBoolean("signature_as_jpeg", enabled).apply()
                },
                saving = savingKind.value != null,
                savingPhoto = savingKind.value == DetectionKind.PHOTO,
                savingSignature = savingKind.value == DetectionKind.SIGNATURE,
                photoSaved = photoSaved.value,
                signatureSaved = signatureSaved.value,
                onProcess = {
                    scope.launch {
                        viewModel.redetect()
                    }
                },
                onRecapture = { launchRecapture() },
                onReimport = { launchImport() },
                onEditPhoto = {
                    photoSaved.value = false
                    val source = state.source
                    val bounds = state.photoState?.currentBounds
                        ?: source?.takeIf { it.isFile }?.let(::fullImageBounds)
                    if (source?.isFile == true && bounds != null) {
                        correctionKindForResult = DetectionKind.PHOTO
                        openDetectedEditor(source, DetectionKind.PHOTO, bounds)
                    }
                },
                onAcceptPhoto = {
                    if (state.photoState == null && state.photoPreviewPath != null) {
                        saveMessage.value = "Manual photo crop is ready. Save it when satisfied."
                    } else {
                        scope.launch { viewModel.accept(DetectionKind.PHOTO) }
                    }
                },
                onRejectPhoto = {
                    photoSaved.value = false
                    viewModel.reject(DetectionKind.PHOTO)
                },
                onEditSignature = {
                    signatureSaved.value = false
                    val source = state.source
                    val bounds = state.signatureState?.currentBounds
                        ?: source?.takeIf { it.isFile }?.let(::fullImageBounds)
                    if (source?.isFile == true && bounds != null) {
                        correctionKindForResult = DetectionKind.SIGNATURE
                        openDetectedEditor(source, DetectionKind.SIGNATURE, bounds)
                    }
                },
                onAcceptSignature = {
                    if (state.signatureState == null && state.signaturePreviewPath != null) {
                        saveMessage.value = "Manual signature crop is ready. Save it when satisfied."
                    } else {
                        scope.launch { viewModel.accept(DetectionKind.SIGNATURE) }
                    }
                },
                onRejectSignature = {
                    signatureSaved.value = false
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
        val requiredName = personName.trim()
        require(requiredName.isNotBlank()) {
            "Enter the person's name before saving the photo or signature."
        }
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
        val baseName = OutputFileNaming.desiredName(kind, requiredName, signatureAsJpeg)
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

    private fun fullImageBounds(file: File): RectF? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        return RectF(0f, 0f, options.outWidth.toFloat(), options.outHeight.toFloat())
    }

    private fun openDetectedEditor(source: File, kind: DetectionKind, bounds: RectF) {
        ioScope.launch {
            var editorSource: File? = null
            var outputFile: File? = null
            var seedBounds: RectF? = null
            try {
                val editorData = withContext(Dispatchers.IO) {
                    val root = org.techwithkaushik.formSnap.foundation.ProcessingPaths.root(this@PipelinePreviewActivity)
                    val cropDir = File(root, "ucrop").apply { mkdirs() }
                    val seed = createSeedCrop(source, bounds, kind, cropDir)
                    seedBounds = seed.sourceBounds
                    seed.file
                    check(cropDir.isDirectory || cropDir.mkdirs()) {
                        "Cannot create the crop output directory."
                    }
                    val destination = File(cropDir, "result_" + System.currentTimeMillis() + ".jpg")
                    if (destination.exists()) check(destination.delete()) {
                        "Cannot replace the previous crop output."
                    }
                    // uCrop-n-Edit's crop worker writes to the destination's filesystem path.
                    // A FileProvider content:// URI can be misread as a path by this fork,
                    // causing ENOENT and leaving its toolbar crop action hidden while loading.
                    check(destination.createNewFile()) {
                        "Cannot create the crop output file."
                    }
                    sourceForEdit to destination
                }
                editorSource = editorData.first
                outputFile = editorData.second
                withContext(Dispatchers.Main.immediate) {
                    pendingCropSourceFile = editorData.first
                    pendingCropSourceBounds = seedBounds
                    pendingCropOutputFile = editorData.second
                    val sourceUri = FileProvider.getUriForFile(
                        this@PipelinePreviewActivity,
                        BuildConfig.APPLICATION_ID + ".fileprovider",
                        editorData.first,
                    )
                    val ratio = if (kind == DetectionKind.PHOTO) 40f / 50f else 50f / 20f
                    val options = UCrop.Options().apply {
                        setShowCropGrid(true)
                        setShowCropFrame(true)
                        setFreeStyleCropEnabled(true)
                        setHideBottomControls(false)
                        setCompressionFormat(android.graphics.Bitmap.CompressFormat.JPEG)
                        setCompressionQuality(96)
                        setToolbarTitle(if (kind == DetectionKind.PHOTO) "Adjust photo" else "Adjust signature")
                        setToolbarColor(android.graphics.Color.rgb(25, 38, 55))
                        setStatusBarColor(android.graphics.Color.rgb(18, 28, 42))
                        setToolbarWidgetColor(android.graphics.Color.WHITE)
                        setToolbarCropDrawable(com.yalantis.ucrop.R.drawable.ucrop_ic_done)
                    }
                    // uCrop's supported pattern is a file:// destination URI. Keep the
                    // source as a FileProvider URI, but do not pass a content:// output URI
                    // to this non-native fork's filesystem-based crop worker.
                    val destinationUri = Uri.fromFile(editorData.second)
                    val cropIntent = UCrop.of(sourceUri, destinationUri)
                        .withAspectRatio(ratio, 1f)
                        .withMaxResultSize(4096, 4096)
                        .withOptions(options)
                        .getIntent(this@PipelinePreviewActivity)
                        .apply {
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            clipData = ClipData.newRawUri("FormSnap crop source", sourceUri)
                        }
                    cropLauncher.launch(cropIntent)
                }
            } catch (cancelled: CancellationException) {
                outputFile?.delete()
                editorSource?.delete()
                pendingCropSourceFile = null
                pendingCropSourceBounds = null
                pendingCropOutputFile = null
                throw cancelled
            } catch (error: Exception) {
                outputFile?.delete()
                editorSource?.delete()
                pendingCropSourceFile = null
                pendingCropSourceBounds = null
                pendingCropOutputFile = null
                android.widget.Toast.makeText(
                    this@PipelinePreviewActivity,
                    error.message ?: "Unable to open crop editor.",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private data class SeedCrop(val file: File, val sourceBounds: RectF)

    private fun createSeedCrop(
        sourceFile: File,
        bounds: RectF,
        kind: DetectionKind,
        directory: File,
    ): SeedCrop {
        val source = org.opencv.imgcodecs.Imgcodecs.imread(sourceFile.absolutePath)
        require(!source.empty()) { "Unable to open source image for cropping." }
        try {
            val width = source.cols()
            val height = source.rows()
            val minSide = if (kind == DetectionKind.PHOTO) 96f else 64f
            val padX = maxOf(bounds.width() * 0.28f, minSide)
            val padY = maxOf(bounds.height() * 0.28f, minSide * 0.5f)
            val left = (bounds.left - padX).toInt().coerceIn(0, width - 1)
            val top = (bounds.top - padY).toInt().coerceIn(0, height - 1)
            val right = (bounds.right + padX).toInt().coerceIn(left + 1, width)
            val bottom = (bounds.bottom + padY).toInt().coerceIn(top + 1, height)
            val region = org.opencv.core.Rect(left, top, right - left, bottom - top)
            val roi = source.submat(region)
            val output = File(directory, "seed_" + System.currentTimeMillis() + ".jpg")
            try {
                check(org.opencv.imgcodecs.Imgcodecs.imwrite(output.absolutePath, roi)) {
                    "Unable to prepare crop preview."
                }
            } finally {
                roi.release()
            }
            return SeedCrop(output, RectF(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat()))
        } finally {
            source.release()
        }
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
