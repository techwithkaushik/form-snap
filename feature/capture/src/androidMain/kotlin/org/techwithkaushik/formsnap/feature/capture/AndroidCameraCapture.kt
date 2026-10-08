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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import org.techwithkaushik.formsnap.ai.AiModelManager
import org.techwithkaushik.formsnap.ai.DetectedObject
import org.techwithkaushik.formsnap.ai.DetectionConfig
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
    private var boundPreview: PreviewView? = null
    private var bindingInProgress = false
    private var analyzer: ImageAnalysis? = null
    private var detector: YoloV8TfliteDetector? = null
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val frameGate = FrameSkipGate(2)
    private val inferenceBusy = AtomicBoolean(false)
    private var onLiveDetections: ((List<LiveDetection>) -> Unit)? = null

    fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    fun setLiveDetectionListener(listener: (List<LiveDetection>) -> Unit) { onLiveDetections = listener }

    fun bind(preview: PreviewView, lifecycleOwner: LifecycleOwner) {
        if (boundPreview === preview && owner === lifecycleOwner && provider != null) return
        if (bindingInProgress && boundPreview === preview) return
        owner = lifecycleOwner
        previewView = preview
        bindingInProgress = true
        val future = ProcessCameraProvider.getInstance(appContext)
        future.addListener(
            {
                runCatching { future.get() }
                    .onSuccess { cameraProvider ->
                        provider = cameraProvider
                        bindUseCases(cameraProvider)
                        bindingInProgress = false
                    }
                    .onFailure {
                        bindingInProgress = false
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
        boundPreview = null
        bindingInProgress = false
        camera = null
        imageCapture = null
        detector?.close()
        detector = null
        inferenceBusy.set(false)
        analysisExecutor.shutdownNow()
        onLiveDetections?.invoke(emptyList())
        owner = null
        previewView = null
    }

    private fun analyze(image: ImageProxy) {
        if (!frameGate.shouldProcess()) { image.close(); return }
        val active = detector
        if (active == null || !inferenceBusy.compareAndSet(false, true)) { image.close(); return }
        val bitmap = imageToBitmap(image)
        image.close()
        if (bitmap == null) return
        val sourceWidth = bitmap.width.toFloat().coerceAtLeast(1f)
        val sourceHeight = bitmap.height.toFloat().coerceAtLeast(1f)
        active.detectAsync(bitmap, { detections ->
            bitmap.recycle()
            inferenceBusy.set(false)
            onLiveDetections?.invoke(
                detections
                    .asSequence()
                    .filter { it.classId in 0..2 && it.confidence >= 0.35f }
                    .map { toLiveDetection(it, sourceWidth, sourceHeight) }
                    .toList(),
            )
        }, {
            bitmap.recycle()
            inferenceBusy.set(false)
            // Live inference failures must not interrupt camera capture.
        })
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
                .also { it.setAnalyzer(analysisExecutor, ::analyze) }

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
                val importedModel = AiModelManager(appContext).activeModelFile()
                if (importedModel != null) {
                    val active = runCatching {
                        YoloV8TfliteDetector(
                            appContext,
                            modelFile = importedModel,
                            config = DetectionConfig(
                                inputSize = 640,
                                confidenceThreshold = 0.35f,
                                iouThreshold = 0.45f,
                                maxDetections = 24,
                                maxClassId = 79,
                            ),
                        )
                    }.getOrNull()
                    if (active != null && active.modelAvailable()) {
                        detector = active
                    } else {
                        active?.close()
                    }
                }
            }
            camera = cameraProvider.bindToLifecycle(
                lifecycleOwner,
                selector,
                preview,
                capture,
                analysis,
            )
            boundPreview = previewView
            presenter.onCameraInitialized(true, true)
            updateFlash(presenter.state.value.flashEnabled)
        }.onFailure {
            presenter.onCameraInitialized(false, false)
            presenter.onCaptureFailure(it.message ?: "Unable to bind camera.")
        }
    }
}
