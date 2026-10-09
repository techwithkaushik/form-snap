package org.techwithkaushik.formsnap.feature.capture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.Matrix
import android.view.Surface
import android.util.Log
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

    companion object {
        private const val TAG = "FormSnapAI"
    }
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
    private var lastLiveDetections: List<LiveDetection> = emptyList()
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
        lastLiveDetections = emptyList()
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
            val mapped = detections
                .asSequence()
                .filter { it.confidence >= 0.30f }
                .map { toLiveDetection(it, sourceWidth, sourceHeight) }
                .toList()
            val stable = stabilizeDetections(mapped)
            lastLiveDetections = stable
            onLiveDetections?.invoke(stable)
        }, { error ->
            bitmap.recycle()
            inferenceBusy.set(false)
            Log.e(TAG, "Live PHOTO/SIGNATURE inference failed", error)
            presenter.onCaptureFailure(
                "AI detection failed: ${error.message ?: error::class.java.simpleName}",
            )
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

    private fun stabilizeDetections(current: List<LiveDetection>): List<LiveDetection> {
        if (lastLiveDetections.isEmpty()) return current
        val used = BooleanArray(lastLiveDetections.size)
        return current.map { next ->
            var bestIndex = -1
            var bestIou = 0f
            lastLiveDetections.forEachIndexed { index, previous ->
                if (!used[index] && previous.label == next.label) {
                    val overlap = iou(previous, next)
                    if (overlap > bestIou) { bestIou = overlap; bestIndex = index }
                }
            }
            if (bestIndex < 0 || bestIou < 0.15f) next else {
                used[bestIndex] = true
                val previous = lastLiveDetections[bestIndex]
                val alpha = 0.45f
                next.copy(
                    confidence = previous.confidence * (1f - alpha) + next.confidence * alpha,
                    left = previous.left * (1f - alpha) + next.left * alpha,
                    top = previous.top * (1f - alpha) + next.top * alpha,
                    right = previous.right * (1f - alpha) + next.right * alpha,
                    bottom = previous.bottom * (1f - alpha) + next.bottom * alpha,
                )
            }
        }
    }

    private fun iou(a: LiveDetection, b: LiveDetection): Float {
        val left = maxOf(a.left, b.left)
        val top = maxOf(a.top, b.top)
        val right = minOf(a.right, b.right)
        val bottom = minOf(a.bottom, b.bottom)
        val intersection = (right - left).coerceAtLeast(0f) * (bottom - top).coerceAtLeast(0f)
        val union = area(a) + area(b) - intersection
        return if (union <= 0f) 0f else intersection / union
    }

    private fun area(d: LiveDetection): Float =
        (d.right - d.left).coerceAtLeast(0f) * (d.bottom - d.top).coerceAtLeast(0f)
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
                                inputSize = 320,
                                confidenceThreshold = 0.35f,
                                iouThreshold = 0.45f,
                                maxDetections = 4,
                                maxClassId = 1,
                            ),
                        )
                    }.getOrElse { error ->
                        Log.e(TAG, "Unable to initialize active AI model", error)
                        presenter.onCaptureFailure(
                            "Unable to initialize AI model: ${error.message ?: error::class.java.simpleName}",
                        )
                        null
                    }
                    if (active != null && active.modelAvailable()) {
                        runCatching { active.modelDiagnostics() }
                            .onSuccess { diagnostics ->
                                Log.i(TAG, diagnostics)
                                detector = active
                                presenter.onCaptureFailure("AI ready. ${diagnostics}")
                            }
                            .onFailure { error ->
                                Log.e(TAG, "Active AI model is incompatible", error)
                                active.close()
                                presenter.onCaptureFailure(
                                    "Active AI model is incompatible: ${error.message ?: "unsupported output"}",
                                )
                            }
                    } else {
                        active?.close()
                        presenter.onCaptureFailure(
                            "No compatible active AI model. Import a trained PHOTO/SIGNATURE .tflite model; " +
                                "saving labels or exporting a dataset does not train the detector.",
                        )
                    }
                } else {
                    // Previously this path stayed silent, leaving users with only the camera grid.
                    presenter.onCaptureFailure(
                        "AI model missing. Open AI learning to collect labels, then install a trained " +
                            "PHOTO/SIGNATURE .tflite model. Labels alone cannot enable live AI detection.",
                    )
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
