package org.techwithkaushik.formSnap

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import org.techwithkaushik.formSnap.pipeline.DetectionKind
import org.techwithkaushik.formSnap.pipeline.PipelinePreviewViewModel
import org.techwithkaushik.formSnap.ui.PipelinePreviewScreen
import org.techwithkaushik.formSnap.ui.PreviewCorrectionScreen
import org.techwithkaushik.formSnap.ui.UcropCorrectionActivity

class PipelinePreviewActivity : ComponentActivity() {

    private val correctionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != RESULT_OK) {
            correctionKindForResult = null
            return@registerForActivityResult
        }

        val resultPath = result.data
            ?.getStringExtra(UcropCorrectionActivity.EXTRA_RESULT_PATH)
            ?: return@registerForActivityResult

        val kind = correctionKindForResult ?: return@registerForActivityResult
        correctionKindForResult = null

        setResult(
            RESULT_OK,
            Intent()
                .putExtra(EXTRA_EXTERNAL_CORRECTION_PATH, resultPath)
                .putExtra(EXTRA_EXTERNAL_CORRECTION_KIND, kind.name),
        )

        pendingExternalCorrection = resultPath to kind
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
            val cropKind = remember { mutableStateOf<DetectionKind?>(null) }
            val cropSource = remember { mutableStateOf<String?>(null) }

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

            LaunchedEffect(pendingExternalCorrection) {
                val pending = pendingExternalCorrection ?: return@LaunchedEffect
                pendingExternalCorrection = null
                viewModel.replacePreviewFromExternal(
                    pending.second,
                    File(pending.first),
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

            if (cropKind.value == null) {
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
                        if (state.photoState != null) {
                            cropKind.value = DetectionKind.PHOTO
                            cropSource.value = path
                        }
                    },
                    onCorrectSignature = {
                        if (state.signatureState != null) {
                            cropKind.value = DetectionKind.SIGNATURE
                            cropSource.value = path
                        }
                    },
                    onBack = {
                        viewModel.close()
                        finish()
                    },
                )
            } else {
                val kind = cropKind.value!!
                val preview = if (kind == DetectionKind.PHOTO) photoBitmap else signatureBitmap

                PreviewCorrectionScreen(
                    source = inputBitmap,
                    resultPreview = preview,
                    onOpenCrop = {
                        correctionKindForResult = kind
                        cropSource.value?.let { sourcePath ->
                            correctionLauncher.launch(
                                Intent(this@PipelinePreviewActivity, UcropCorrectionActivity::class.java)
                                    .putExtra(UcropCorrectionActivity.EXTRA_SOURCE_PATH, sourcePath)
                                    .putExtra(UcropCorrectionActivity.EXTRA_MAX_WIDTH, 4000)
                                    .putExtra(UcropCorrectionActivity.EXTRA_MAX_HEIGHT, 4000),
                            )
                        }
                    },
                    onAccept = {
                        cropKind.value = null
                        cropSource.value = null
                    },
                    onReject = {
                        cropKind.value = null
                        cropSource.value = null
                    },
                )
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
        const val EXTRA_EXTERNAL_CORRECTION_PATH = "formsnap.external_correction_path"
        const val EXTRA_EXTERNAL_CORRECTION_KIND = "formsnap.external_correction_kind"
    }
}
