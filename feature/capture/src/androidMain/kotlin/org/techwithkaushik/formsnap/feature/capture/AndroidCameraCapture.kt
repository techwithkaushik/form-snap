package org.techwithkaushik.formsnap.feature.capture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.Matrix
import android.view.Surface
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import org.techwithkaushik.formsnap.ai.DetectedObject
import org.techwithkaushik.formsnap.ai.FrameSkipGate
import org.techwithkaushik.formsnap.ai.YoloV8TfliteDetector

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
    private var analyzer: ImageAnalysis? = null
    private var detector: YoloV8TfliteDetector? = null
    private val frameGate = FrameSkipGate(2)
    private var onLiveDetections: ((List<LiveDetection>) -> Unit)? = null

    fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    fun setLiveDetectionListener(listener: (List<LiveDetection>) -> Unit) { onLiveDetections = listener }

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
                        presenter.onCaptureFailure(
                            it.message ?: "Unable to initialize camera.",
                        )
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
                    presenter.onCaptureFailure(
                        exception.message ?: "Failed to capture image.",
                    )
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    presenter.onCaptured(Uri.fromFile(file).toString(), file.name)
                }
            },
        )
    }

    fun shutdown() {
        analyzer?.clearAnalyzer()
        analyzer = null
        provider?.unbindAll()
        provider = null
        camera = null
        imageCapture = null
        detector?.close()
        detector = null
        onLiveDetections?.invoke(emptyList())
        owner = null
        previewView = null
    }

    private fun analyze(image: ImageProxy) {
        if (!frameGate.shouldProcess()) { image.close(); return }
        val active = detector
        if (active == null) { image.close(); return }
        val bitmap = imageToBitmap(image)
        image.close()
        if (bitmap == null) return
        val sourceWidth = bitmap.width.toFloat().coerceAtLeast(1f)
        val sourceHeight = bitmap.height.toFloat().coerceAtLeast(1f)
        active.detectAsync(bitmap, { detections ->
            bitmap.recycle()
            onLiveDetections?.invoke(detections.filter { it.isExtractable }.map { toLiveDetection(it, sourceWidth, sourceHeight) })
        }, { bitmap.recycle() })
    }

    private fun imageToBitmap(image: ImageProxy): Bitmap? {
        val plane = image.planes.firstOrNull() ?: return null
        val width = image.width
        val height = image.height
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width
        val bitmapWidth = width + rowPadding / pixelStride
        val bitmap = Bitmap.createBitmap(bitmapWidth, height, Bitmap.Config.ARGB_8888)
        plane.buffer.rewind()
        bitmap.copyPixelsFromBuffer(plane.buffer)
        val cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height)
        bitmap.recycle()
        val degrees = image.imageInfo.rotationDegrees
        if (degrees == 0) return cropped
        val rotated = Bitmap.createBitmap(cropped, 0, 0, cropped.width, cropped.height, Matrix().apply { postRotate(degrees.toFloat()) }, true)
        cropped.recycle()
        return rotated
    }

    private fun toLiveDetection(detection: DetectedObject, sourceWidth: Float, sourceHeight: Float): LiveDetection =
        LiveDetection(
            label = detection.label,
            confidence = detection.confidence,
            id = detection.id,
            left = detection.boundingBox.left / sourceWidth,
            top = detection.boundingBox.top / sourceHeight,
            right = detection.boundingBox.right / sourceWidth,
            bottom = detection.boundingBox.bottom / sourceHeight,
        )

    private fun bindUseCases(cameraProvider: ProcessCameraProvider) {
        val lifecycleOwner = owner ?: return
        val previewView = previewView ?: return
        runCatching {
            val rotation = previewView.display?.rotation ?: Surface.ROTATION_0
            val preview = Preview.Builder()
                .setTargetRotation(rotation)
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setJpegQuality(92)
                .setTargetRotation(rotation)
                .build()
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setTargetRotation(rotation)
                .build()
                .also { it.setAnalyzer(ContextCompat.getMainExecutor(appContext), ::analyze) }

            val selector = if (presenter.state.value.lens == CameraLens.FRONT) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }

            cameraProvider.unbindAll()
            analyzer?.clearAnalyzer()
            analyzer = analysis
            imageCapture = capture
            if (detector == null) {
                val candidate = runCatching { YoloV8TfliteDetector(appContext) }.getOrNull()
                if (candidate != null && candidate.modelAvailable()) detector = candidate else candidate?.close()
            }
            camera = cameraProvider.bindToLifecycle(
                lifecycleOwner,
                selector,
                preview,
                capture,
                analysis,
            )
            presenter.onCameraInitialized(true, true)
            updateFlash(presenter.state.value.flashEnabled)
        }.onFailure {
            presenter.onCameraInitialized(false, false)
            presenter.onCaptureFailure(it.message ?: "Unable to bind camera.")
        }
    }
}
