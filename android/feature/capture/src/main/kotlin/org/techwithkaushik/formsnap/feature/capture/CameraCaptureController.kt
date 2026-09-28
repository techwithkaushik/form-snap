package org.techwithkaushik.formsnap.feature.capture

import android.content.Context
import android.net.Uri
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File

class CameraCaptureController(
    context: Context,
    private val viewModel: CaptureViewModel,
) {
    private val appContext = context.applicationContext
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null

    fun bind(previewView: PreviewView, lifecycleOwner: LifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(appContext)
        future.addListener(
            {
                runCatching {
                    val cameraProvider = future.get()
                    provider = cameraProvider
                    val rotation = previewView.display?.rotation
                        ?: android.view.Surface.ROTATION_0

                    val preview = Preview.Builder()
                        .setTargetRotation(rotation)
                        .build()
                        .also { it.surfaceProvider = previewView.surfaceProvider }

                    val capture = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .setJpegQuality(92)
                        .setTargetRotation(rotation)
                        .build()

                    imageCapture = capture
                    bindUseCases(cameraProvider, lifecycleOwner, preview, capture)
                }.onFailure {
                    viewModel.onCameraInitialized(false, false)
                    viewModel.onCaptureFailure(
                        it.message ?: "Unable to initialize the camera.",
                    )
                }
            },
            ContextCompat.getMainExecutor(appContext),
        )
    }

    fun updateLens(previewView: PreviewView, lifecycleOwner: LifecycleOwner) {
        val cameraProvider = provider ?: return
        val capture = imageCapture ?: return
        val rotation = previewView.display?.rotation ?: android.view.Surface.ROTATION_0
        val preview = Preview.Builder()
            .setTargetRotation(rotation)
            .build()
            .also { it.surfaceProvider = previewView.surfaceProvider }
        bindUseCases(cameraProvider, lifecycleOwner, preview, capture)
    }

    fun updateFlash(enabled: Boolean) {
        val currentCamera = camera ?: return
        if (currentCamera.cameraInfo.hasFlashUnit()) {
            currentCamera.cameraControl.enableTorch(enabled)
        }
    }

    fun takePicture(directory: File) {
        val capture = imageCapture
        if (capture == null) {
            viewModel.onCaptureFailure("Camera is not ready.")
            return
        }

        viewModel.capture()
        if (!viewModel.state.value.capturing) return

        directory.mkdirs()
        val file = File(directory, "capture_${System.currentTimeMillis()}.jpg")
        val options = ImageCapture.OutputFileOptions.Builder(file).build()

        capture.takePicture(
            options,
            ContextCompat.getMainExecutor(appContext),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exception: ImageCaptureException) {
                    file.delete()
                    viewModel.onCaptureFailure(
                        exception.message ?: "Failed to capture image.",
                    )
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    viewModel.onCaptured(Uri.fromFile(file), file.name)
                }
            },
        )
    }

    fun shutdown() {
        provider?.unbindAll()
        provider = null
        camera = null
        imageCapture = null
    }

    private fun bindUseCases(
        cameraProvider: ProcessCameraProvider,
        lifecycleOwner: LifecycleOwner,
        preview: Preview,
        capture: ImageCapture,
    ) {
        val selector = if (viewModel.state.value.lens == CameraLens.FRONT) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }

        runCatching {
            cameraProvider.unbindAll()
            camera = cameraProvider.bindToLifecycle(
                lifecycleOwner,
                selector,
                preview,
                capture,
            )
            viewModel.onCameraInitialized(true, true)
            updateFlash(viewModel.state.value.flashEnabled)
        }.onFailure {
            viewModel.onCameraInitialized(false, false)
            viewModel.onCaptureFailure(
                it.message ?: "Unable to bind the camera.",
            )
        }
    }
}
