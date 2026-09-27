package org.techwithkaushik.formSnap

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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

        if (result.resultCode == RESULT_OK) {
            val resultUri = result.data?.let { UCrop.getOutput(it) }
            val resultPath = resultUri?.path
            if (!resultPath.isNullOrBlank()) {
                pendingExternalCorrection = resultPath to kind
            }
        }
    }

    private var correctionKindForResult: DetectionKind? = null
    private var pendingExternalCorrection: Pair<String, DetectionKind>? = null

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
            val viewModel = remember { PipelinePreviewViewModel(applicationContext) }
            val state by viewModel.state.collectAsState()
            val scope = rememberCoroutineScope()
            val editKind = remember { mutableStateOf<DetectionKind?>(null) }

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
            val photoBitmap = remember(state.photoPreviewPath, state.photoPreviewVersion) {
                viewModel.loadBitmap(state.photoPreviewPath)
            }
            val signatureBitmap = remember(
                state.signaturePreviewPath,
                state.signaturePreviewVersion,
            ) {
                viewModel.loadBitmap(state.signaturePreviewPath)
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
                        viewModel.renderPhoto()
                        viewModel.renderSignature()
                    }
                },
                onEditPhoto = {
                    val file = state.photoPreviewPath?.let(::File)
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
                    val file = state.signaturePreviewPath?.let(::File)
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
                    viewModel.close()
                    finish()
                },
            )
        }
    }

    private fun openDetectedEditor(source: File, kind: DetectionKind) {
        val destination = File(
            cacheDir,
            "ucrop_" + System.nanoTime() + "_" + kind.name.lowercase() + ".jpg",
        )

        val options = UCrop.Options().apply {
            setFreeStyleCropEnabled(true)
            setCompressionQuality(95)
            setShowCropGrid(true)
            setShowCropFrame(true)
            setBrightnessEnabled(true)
            setContrastEnabled(true)
            setSaturationEnabled(true)
            setSharpnessEnabled(true)
            setHideBottomControls(false)
        }

        UCrop.of(
            Uri.fromFile(source),
            Uri.fromFile(destination),
        )
            .withOptions(options)
            .withMaxResultSize(1600, 1600)
            .start(this, correctionLauncher)
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