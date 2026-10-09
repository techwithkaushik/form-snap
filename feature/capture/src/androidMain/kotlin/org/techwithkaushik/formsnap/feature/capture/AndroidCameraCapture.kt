package org.techwithkaushik.formsnap.feature.capture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.YuvImage
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
import java.io.ByteArrayOutputStream
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
        val bitmap = try {
            imageToBitmap(image)
        } catch (error: Throwable) {
            Log.e(TAG, "Unable to convert camera frame to bitmap", error)
            null
        } finally {
            image.close()
        }
        if (bitmap == null) {
            inferenceBusy.set(false)
            return
        }
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
            Log.d(TAG, "Live detector returned ${detections.size} boxes; ${mapped.size} passed confidence threshold")
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

    /**
     * ImageAnalysis delivers YUV_420_888 by default, not packed ARGB pixels.
     * Reading the Y plane directly as an ARGB bitmap corrupts the frame and
     * prevents the detector from seeing real photo/signature content.
     */
    private fun imageToBitmap(image: ImageProxy): Bitmap? {
        val width = image.width
        val height = image.height
        val bitmap = when (image.format) {
            // CameraX OUTPUT_IMAGE_FORMAT_RGBA_8888 reports PixelFormat.RGBA_8888
            // on supported devices; FLEX_RGBA_8888 may also be reported by providers.
            PixelFormat.RGBA_8888, ImageFormat.FLEX_RGBA_8888 -> {
                val plane = image.planes.firstOrNull() ?: return null
                val source = plane.buffer.duplicate()
                val pixels = IntArray(width * height)
                for (row in 0 until height) {
                    val rowStart = row * plane.rowStride
                    for (col in 0 until width) {
                        val pixelStart = rowStart + col * plane.pixelStride
                        if (pixelStart + 3 >= source.limit()) {
                            Log.e(TAG, "RGBA frame buffer is shorter than rowStride/pixelStride require")
                            return null
                        }
                        val red = source.get(pixelStart).toInt() and 0xFF
                        val green = source.get(pixelStart + 1).toInt() and 0xFF
                        val blue = source.get(pixelStart + 2).toInt() and 0xFF
                        val alpha = source.get(pixelStart + 3).toInt() and 0xFF
                        pixels[row * width + col] =
                            (alpha shl 24) or (red shl 16) or (green shl 8) or blue
                    }
                }
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                    it.setPixels(pixels, 0, width, 0, 0, width, height)
                }
            }
            ImageFormat.YUV_420_888 -> {
                val planes = image.planes
                if (planes.size < 3) return null
                val yPlane = planes[0]
                val uPlane = planes[1]
                val vPlane = planes[2]
                val nv21 = ByteArray(width * height + 2 * ((width + 1) / 2) * ((height + 1) / 2))
                val yBuffer = yPlane.buffer.duplicate()
                var outputIndex = 0
                for (row in 0 until height) {
                    val rowStart = row * yPlane.rowStride
                    for (col in 0 until width) {
                        nv21[outputIndex++] = yBuffer.get(rowStart + col * yPlane.pixelStride)
                    }
                }
                val chromaWidth = (width + 1) / 2
                val chromaHeight = (height + 1) / 2
                val uBuffer = uPlane.buffer.duplicate()
                val vBuffer = vPlane.buffer.duplicate()
                for (row in 0 until chromaHeight) {
                    val uRowStart = row * uPlane.rowStride
                    val vRowStart = row * vPlane.rowStride
                    for (col in 0 until chromaWidth) {
                        nv21[outputIndex++] = vBuffer.get(vRowStart + col * vPlane.pixelStride)
                        nv21[outputIndex++] = uBuffer.get(uRowStart + col * uPlane.pixelStride)
                    }
                }
                val jpeg = ByteArrayOutputStream()
                val converted = YuvImage(nv21, ImageFormat.NV21, width, height, null)
                    .compressToJpeg(Rect(0, 0, width, height), 90, jpeg)
                if (!converted) return null
                BitmapFactory.decodeByteArray(jpeg.toByteArray(), 0, jpeg.size()) ?: return null
            }
            else -> {
                Log.w(TAG, "Unsupported camera analysis format: ${image.format}")
                return null
            }
        }
        val degrees = image.imageInfo.rotationDegrees
        if (degrees == 0) return bitmap
        return try {
            Bitmap.createBitmap(
                bitmap, 0, 0, bitmap.width, bitmap.height,
                Matrix().apply { postRotate(degrees.toFloat()) }, true,
            ).also { if (it !== bitmap) bitmap.recycle() }
        } catch (error: Throwable) {
            bitmap.recycle()
            throw error
        }
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
    /**
     * Convert detector coordinates into the visible PreviewView coordinates.
     * PreviewView defaults to FILL_CENTER, so a camera frame with a different
     * aspect ratio is center-cropped. Front-camera preview is mirrored as well.
     */
    private fun toLiveDetection(
        detection: DetectedObject,
        sourceWidth: Float,
        sourceHeight: Float,
    ): LiveDetection {
        var left = (detection.boundingBox.left / sourceWidth).coerceIn(0f, 1f)
        var top = (detection.boundingBox.top / sourceHeight).coerceIn(0f, 1f)
        var right = (detection.boundingBox.right / sourceWidth).coerceIn(0f, 1f)
        var bottom = (detection.boundingBox.bottom / sourceHeight).coerceIn(0f, 1f)

        val viewWidth = previewView?.width?.toFloat() ?: 0f
        val viewHeight = previewView?.height?.toFloat() ?: 0f
        if (viewWidth > 0f && viewHeight > 0f && sourceWidth > 0f && sourceHeight > 0f) {
            val sourceAspect = sourceWidth / sourceHeight
            val viewAspect = viewWidth / viewHeight
            if (sourceAspect > viewAspect) {
                // The source is wider than the view; FILL_CENTER crops both sides.
                val visibleFraction = (viewAspect / sourceAspect).coerceIn(0f, 1f)
                val cropStart = (1f - visibleFraction) / 2f
                left = (left - cropStart) / visibleFraction
                right = (right - cropStart) / visibleFraction
            } else if (sourceAspect < viewAspect) {
                // The source is taller than the view; FILL_CENTER crops top/bottom.
                val visibleFraction = (sourceAspect / viewAspect).coerceIn(0f, 1f)
                val cropStart = (1f - visibleFraction) / 2f
                top = (top - cropStart) / visibleFraction
                bottom = (bottom - cropStart) / visibleFraction
            }
        }

        if (presenter.state.value.lens == CameraLens.FRONT) {
            val mirroredLeft = 1f - right
            right = 1f - left
            left = mirroredLeft
        }

        return LiveDetection(
            label = detection.label,
            confidence = detection.confidence,
            id = detection.id,
            left = left.coerceIn(0f, 1f),
            top = top.coerceIn(0f, 1f),
            right = right.coerceIn(0f, 1f),
            bottom = bottom.coerceIn(0f, 1f),
        )
    }

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
                                Log.i(TAG, "Active model ready: $diagnostics")
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
