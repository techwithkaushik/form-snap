package org.techwithkaushik.formsnap.feature.capture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.view.Surface
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

class AndroidCameraCapture(
    context: Context,
    private val presenter: CapturePresenter,
) {
    private val appContext = context.applicationContext
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var owner: LifecycleOwner? = null
    private var previewView: PreviewView? = null

    fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    fun bind(preview: PreviewView, lifecycleOwner: LifecycleOwner) {
        owner = lifecycleOwner
        previewView = preview
        val future = ProcessCameraProvider.getInstance(appContext)
        future.addListener(
            {
                runCatching { future.get() }
                    .onSuccess { cameraProvider ->
                        provider = cameraProvider
                        bindUseCases(cameraProvider)
                    }
                    .onFailure {
                        presenter.onCameraInitialized(false, false)
                        presenter.onCaptureFailure(it.message ?: "Unable to initialize camera.")
                    }
            },
            ContextCompat.getMainExecutor(appContext),
        )
    }

    fun updateLens() {
        provider?.let(::bindUseCases)
    }

    fun updateFlash(enabled: Boolean) {
        camera?.let { current ->
            if (current.cameraInfo.hasFlashUnit()) {
                current.cameraControl.enableTorch(enabled)
            }
        }
    }

    fun capture(session: CaptureSession) {
        val capture = imageCapture
        if (capture == null) {
            presenter.onCaptureFailure("Camera is not ready.")
            return
        }
        if (!presenter.beginCapture()) return

        val file = session.nextCaptureFile("jpg")
        val options = ImageCapture.OutputFileOptions.Builder(file).build()

        capture.takePicture(
            options,
            ContextCompat.getMainExecutor(appContext),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exception: ImageCaptureException) {
                    file.delete()
                    presenter.onCaptureFailure(exception.message ?: "Failed to capture image.")
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    presenter.onCaptured(Uri.fromFile(file).toString(), file.name)
                }
            },
        )
    }

    fun shutdown() {
        provider?.unbindAll()
        provider = null
        camera = null
        imageCapture = null
        owner = null
        previewView = null
    }

    private fun bindUseCases(cameraProvider: ProcessCameraProvider) {
        val lifecycleOwner = owner ?: return
        val previewView = previewView ?: return
        runCatching {
            val rotation = previewView.display?.rotation ?: Surface.ROTATION_0
            val preview = Preview.Builder()
                .setTargetRotation(rotation)
                .build()
                .also { it.surfaceProvider = previewView.surfaceProvider }
            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setJpegQuality(92)
                .setTargetRotation(rotation)
                .build()
            val selector = if (presenter.state.value.lens == CameraLens.FRONT) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }

            cameraProvider.unbindAll()
            imageCapture = capture
            camera = cameraProvider.bindToLifecycle(
                lifecycleOwner,
                selector,
                preview,
                capture,
            )
            presenter.onCameraInitialized(true, true)
            updateFlash(presenter.state.value.flashEnabled)
        }.onFailure {
            presenter.onCameraInitialized(false, false)
            presenter.onCaptureFailure(it.message ?: "Unable to bind camera.")
        }
    }
}