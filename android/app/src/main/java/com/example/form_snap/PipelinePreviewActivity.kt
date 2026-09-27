package org.techwithkaushik.formSnap

import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import org.techwithkaushik.formSnap.pipeline.PipelinePreviewViewModel
import org.techwithkaushik.formSnap.ui.PipelinePreviewScreen

class PipelinePreviewActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val path = intent.getStringExtra(EXTRA_INPUT_PATH)
        if (path.isNullOrBlank()) {
            finish()
            return
        }

        setContent {
            val viewModel = remember {
                PipelinePreviewViewModel(applicationContext)
            }
            val state by viewModel.state.collectAsState()
            val scope = rememberCoroutineScope()

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
                    // Correction editor integration follows after this host is stable.
                },
                onCorrectSignature = {
                    // Correction editor integration follows after this host is stable.
                },
                onBack = {
                    viewModel.close()
                    finish()
                },
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    companion object {
        const val EXTRA_INPUT_PATH = "formsnap.input_path"
    }
}
