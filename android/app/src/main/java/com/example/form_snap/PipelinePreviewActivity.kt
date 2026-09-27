package org.techwithkaushik.formSnap

import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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

class PipelinePreviewActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val path = intent.getStringExtra(EXTRA_INPUT_PATH)
        val maxKb = intent.getIntExtra(EXTRA_MAX_KB, 50)
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

            LaunchedEffect(path) {
                viewModel.load(java.io.File(path))
            }

            val inputBitmap = remember(state.source?.absolutePath) {
                state.source?.let { BitmapFactory.decodeFile(it.absolutePath) }
            }
            val photoBitmap = remember(state.photoPreviewPath) {
                viewModel.loadBitmap(state.photoPreviewPath)
            }
            val signatureBitmap = remember(state.signaturePreviewPath) {
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
                        state.photoState?.let { correction ->
                            val candidate = org.techwithkaushik.formSnap.pipeline.DetectionCandidate(
                                kind = DetectionKind.PHOTO,
                                bounds = correction.automaticBounds,
                                confidence = 1f,
                                source = "automatic-preview",
                            )
                            correctionController.value = PreviewCorrectionController(
                                detectionCandidate = candidate,
                                initialState = correction,
                            )
                            correctionKind.value = DetectionKind.PHOTO
                        }
                    },
                    onCorrectSignature = {
                        state.signatureState?.let { correction ->
                            val candidate = org.techwithkaushik.formSnap.pipeline.DetectionCandidate(
                                kind = DetectionKind.SIGNATURE,
                                bounds = correction.automaticBounds,
                                confidence = 1f,
                                source = "automatic-preview",
                            )
                            correctionController.value = PreviewCorrectionController(
                                detectionCandidate = candidate,
                                initialState = correction,
                            )
                            correctionKind.value = DetectionKind.SIGNATURE
                        }
                    },
                    onBack = {
                        viewModel.close()
                        finish()
                    },
                )
            } else {
                val controller = correctionController.value
                val correctionState = controller?.state
                if (controller != null && correctionState != null) {
                    val preview = if (correctionKind.value == DetectionKind.PHOTO) photoBitmap else signatureBitmap
                    PreviewCorrectionScreen(
                        state = correctionState,
                        preview = preview,
                        onBoundsChange = { bounds ->
                            controller.setBounds(bounds)
                            scope.launch {
                                viewModel.applyCorrection(correctionKind.value ?: return@launch, controller.state)
                            }
                        },
                        onAppearanceChange = { appearance ->
                            controller.setAppearance(appearance)
                            scope.launch {
                                viewModel.applyCorrection(correctionKind.value ?: return@launch, controller.state)
                            }
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
                                correctionKind.value = null
                                correctionController.value = null
                            }
                        },
                        onReject = {
                            controller.reject()
                            correctionKind.value = null
                            correctionController.value = null
                        },
                        onReset = {
                            controller.reset()
                        },
                    )
                }
            }
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
    }
}
