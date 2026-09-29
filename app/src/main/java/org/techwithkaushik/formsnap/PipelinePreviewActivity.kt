package org.techwithkaushik.formSnap

import org.techwithkaushik.formsnap.BuildConfig

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.yalantis.ucrop.UCrop
import kotlinx.coroutines.launch
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
            putExtra(EXTRA_MAX_KB, intent.getDoubleExtra(EXTRA_MAX_KB, 50.0))
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

            val inputBitmap = remember(state.source?.absolutePath) {
                state.source?.let { BitmapFactory.decodeFile(it.absolutePath) }
            }
            val photoBitmap = remember(
                state.photoPreviewPath,
                state.photoPreviewVersion,
                editedPhotoPath.value,
                editedPhotoVersion.value,
            ) {
                viewModel.loadBitmap(editedPhotoPath.value ?: state.photoPreviewPath)
            }
            val signatureBitmap = remember(
                state.signaturePreviewPath,
                state.signaturePreviewVersion,
                editedSignaturePath.value,
                editedSignatureVersion.value,
            ) {
                viewModel.loadBitmap(editedSignaturePath.value ?: state.signaturePreviewPath)
            }

            PipelinePreviewScreen(
                inputPreview = inputBitmap,
                photoPreview = photoBitmap,
                signaturePreview = signatureBitmap,
                photoDetected = state.photoState != null && state.photoPreviewPath != null,
                signatureDetected = state.signatureState != null && state.signaturePreviewPath != null,
                processing = state.processing,
                message = state.error,
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
                    scope.launch { viewModel.accept(DetectionKind.PHOTO) }
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
                    scope.launch { viewModel.accept(DetectionKind.SIGNATURE) }
                },
                onRejectSignature = {
                    viewModel.reject(DetectionKind.SIGNATURE)
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