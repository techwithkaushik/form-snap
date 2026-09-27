package org.techwithkaushik.formSnap

import android.content.Intent
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
import kotlinx.coroutines.launch
import org.techwithkaushik.formSnap.pipeline.DetectionKind
import org.techwithkaushik.formSnap.pipeline.FeedbackRecorder
import org.techwithkaushik.formSnap.pipeline.PreviewCorrectionController
import org.techwithkaushik.formSnap.pipeline.PipelinePreviewViewModel
import org.techwithkaushik.formSnap.ui.PipelinePreviewScreen
import org.techwithkaushik.formSnap.ui.PreviewCorrectionScreen
import org.techwithkaushik.formSnap.ui.UcropCorrectionActivity

class PipelinePreviewActivity : ComponentActivity() {

    private val correctionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val resultPath = result.data?.getStringExtra(UcropCorrectionActivity.EXTRA_RESULT_PATH)
            ?: return@registerForActivityResult
        val kind = correctionKindForResult ?: return@registerForActivityResult

        // Return the corrected crop to this activity; MainActivity is not involved.
        val correctionResult = Intent()
            .putExtra(EXTRA_EXTERNAL_CORRECTION_PATH, resultPath)
            .putExtra(EXTRA_EXTERNAL_CORRECTION_KIND, kind.name)
        setResult(RESULT_OK, correctionResult)

        // Re-render the corrected crop inside the existing preview/editor pipeline.
        externalCorrectionPath = resultPath
        externalCorrectionKind = kind
        correctionKindForResult = null
    }

    private var correctionKindForResult: DetectionKind? = null
    private var externalCorrectionPath: String? = null
    private var externalCorrectionKind: DetectionKind? = null

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
            val correctionKind = remember { mutableStateOf<DetectionKind?>(null) }
            val correctionController = remember { mutableStateOf<PreviewCorrectionController?>(null) }
            val correctionUiState = remember { mutableStateOf<org.techwithkaushik.formSnap.pipeline.PreviewCorrectionState?>(null) }

            LaunchedEffect(path) {
                viewModel.load(
                    input = java.io.File(path),
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
            val signatureBitmap = remember(state.signaturePreviewPath, state.signaturePreviewVersion) {
                viewModel.loadBitmap(state.signaturePreviewPath)
            }

            if (correctionKind.value == null) {
                PipelinePreviewScreen(
                    inputPreview = inputBitmap,
                    photoPreview = photoBitmap,
                    signaturePreview = signatureBitmap,
                    photoDetected = state.photoState != null,
                    signatureDetected = state.signatureState != null,
                    processing = state.processing,
                    message = state.error,
                    onProcess = {
                        scope.launch {
                            viewModel.renderPhoto()
                            viewModel.renderSignature()
                        }
                    },
                    onCorrectPhoto = {
                        state.photoState?.let {
                            openUcrop(DetectionKind.PHOTO, path)
                        }
                    },
                    onCorrectSignature = {
                        state.signatureState?.let {
                            openUcrop(DetectionKind.SIGNATURE, path)
                        }
                    },
                    onBack = {
                        viewModel.close()
                        finish()
                    },
                )
            } else {
                val controller = correctionController.value
                val correctionState = correctionUiState.value
                if (controller != null && correctionState != null) {
                    val preview = if (correctionKind.value == DetectionKind.PHOTO) photoBitmap else signatureBitmap
                    PreviewCorrectionScreen(
                        state = correctionState,
                        source = inputBitmap,
                        resultPreview = preview,
                        onBoundsChange = { bounds ->
                            controller.setBounds(bounds)
                            correctionUiState.value = controller.state
                            correctionKind.value?.let { viewModel.schedulePreview(it, controller.state) }
                        },
                        onAppearanceChange = { appearance ->
                            controller.setAppearance(appearance)
                            correctionUiState.value = controller.state
                            correctionKind.value?.let { viewModel.schedulePreview(it, controller.state) }
                        },
                        onAccept = {
                            controller.accept()
                            FeedbackRecorder.record(applicationContext, controller.feedback())
                            scope.launch {
                                if (correctionKind.value == DetectionKind.PHOTO) {
                                    viewModel.renderPhoto()
                                } else {
                                    viewModel.renderSignature()
                                }
                                correctionUiState.value = null
                                correctionKind.value = null
                                correctionController.value = null
                            }
                        },
                        onReject = {
                            controller.reject()
                            correctionUiState.value = null
                            correctionKind.value = null
                            correctionController.value = null
                        },
                        onReset = {
                            controller.reset()
                            correctionUiState.value = controller.state
                            correctionKind.value?.let { viewModel.schedulePreview(it, controller.state) }
                        },
                    )
                }
            }

            externalCorrectionPath?.let { correctedPath ->
                val kind = externalCorrectionKind ?: return@let
                val correctedUri = Uri.fromFile(java.io.File(correctedPath))
                // The uCrop output is already cropped. Keep it as the current
                // candidate preview while retaining the existing OpenCV pipeline
                // for final appearance normalization and learning feedback.
                scope.launch {
                    viewModel.replacePreviewFromExternal(kind, correctedUri)
                    externalCorrectionPath = null
                    externalCorrectionKind = null
                }
            }
        }
    }

    private fun openUcrop(kind: DetectionKind, sourcePath: String) {
        correctionKindForResult = kind
        correctionLauncher.launch(
            Intent(this, UcropCorrectionActivity::class.java)
                .putExtra(UcropCorrectionActivity.EXTRA_SOURCE_PATH, sourcePath)
                .putExtra(UcropCorrectionActivity.EXTRA_MAX_WIDTH, 4000)
                .putExtra(UcropCorrectionActivity.EXTRA_MAX_HEIGHT, 4000),
        )
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
