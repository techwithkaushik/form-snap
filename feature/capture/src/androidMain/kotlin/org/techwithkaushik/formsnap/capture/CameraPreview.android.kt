package org.techwithkaushik.formsnap.capture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private const val TEMP_FILE_NAME = "temp_form.jpg"
private const val TEMP_DIRECTORY_NAME = "formsnap_capture"

@Composable
fun CameraPreview(
    modifier: Modifier = Modifier,
    onCaptured: (File) -> Unit,
    onCaptureError: (Throwable) -> Unit = {},
) {
    val context = LocalContext.current.applicationContext
    val lifecycleOwner = LocalLifecycleOwner.current

    var cameraSelector by remember {
        mutableStateOf(CameraSelector.DEFAULT_BACK_CAMERA)
    }
    var imageCapture by remember {
        mutableStateOf<ImageCapture?>(null)
    }

    val executor = remember {
        Executors.newSingleThreadExecutor()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { },
    )

    DisposableEffect(context, lifecycleOwner, executor) {
        onDispose {
            runCatching {
                imageCapture = null
            }
            executor.shutdown()
            purgeTemporaryAssets(context)
        }
    }

    LaunchedEffect(context) {
        val granted =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA,
            ) == PackageManager.PERMISSION_GRANTED

        if (!granted) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { viewContext ->
                PreviewView(viewContext).apply {
                    implementationMode =
                        PreviewView.ImplementationMode.PERFORMANCE
                    scaleType =
                        PreviewView.ScaleType.FILL_CENTER
                }
            },
            update = { previewView ->
                val granted =
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.CAMERA,
                    ) == PackageManager.PERMISSION_GRANTED

                if (granted) {
                    bindCamera(
                        context = context,
                        lifecycleOwner = lifecycleOwner,
                        previewView = previewView,
                        executor = executor,
                        selector = cameraSelector,
                        onCaptureReady = { imageCapture = it },
                        onFailure = onCaptureError,
                    )
                }
            },
        )
    }
}

class CameraPreviewController(
    context: Context,
    private val onCaptured: (File) -> Unit,
    private val onCaptureError: (Throwable) -> Unit = {},
) : AutoCloseable {

    private val appContext = context.applicationContext
    private val executor: ExecutorService =
        Executors.newSingleThreadExecutor()

    private var imageCapture: ImageCapture? = null

    fun setImageCapture(capture: ImageCapture) {
        imageCapture = capture
    }

    fun capture(): Boolean {
        val capture = imageCapture ?: return false
        val outputFile = createTemporaryCaptureFile(appContext)

        purgeTemporaryAssets(appContext)

        val options =
            ImageCapture.OutputFileOptions.Builder(outputFile)
                .build()

        capture.takePicture(
            options,
            executor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(
                    outputFileResults: ImageCapture.OutputFileResults,
                ) {
                    if (outputFile.isFile && outputFile.length() > 0L) {
                        onCaptured(outputFile)
                    } else {
                        outputFile.delete()
                        onCaptureError(
                            IllegalStateException(
                                "Camera produced an empty image.",
                            ),
                        )
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    outputFile.delete()
                    onCaptureError(exception)
                }
            },
        )

        return true
    }

    fun onSaveCompleted() {
        purgeTemporaryAssets(appContext)
    }

    fun onCaptureCancelled() {
        purgeTemporaryAssets(appContext)
    }

    override fun close() {
        executor.shutdown()
        imageCapture = null
        purgeTemporaryAssets(appContext)
    }
}

private fun bindCamera(
    context: Context,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    previewView: PreviewView,
    executor: ExecutorService,
    selector: CameraSelector,
    onCaptureReady: (ImageCapture) -> Unit,
    onFailure: (Throwable) -> Unit,
) {
    val providerFuture = ProcessCameraProvider.getInstance(context)

    providerFuture.addListener(
        {
            runCatching {
                val provider = providerFuture.get()

                val rotation =
                    previewView.display?.rotation
                        ?: android.view.Surface.ROTATION_0

                val preview =
                    Preview.Builder()
                        .setTargetRotation(rotation)
                        .build()
                        .also {
                            it.surfaceProvider =
                                previewView.surfaceProvider
                        }

                val capture =
                    ImageCapture.Builder()
                        .setCaptureMode(
                            ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY,
                        )
                        .setJpegQuality(92)
                        .setTargetRotation(rotation)
                        .build()

                provider.unbindAll()

                provider.bindToLifecycle(
                    lifecycleOwner,
                    selector,
                    preview,
                    capture,
                )

                onCaptureReady(capture)
            }.onFailure(onFailure)
        },
        ContextCompat.getMainExecutor(context),
    )
}

private fun createTemporaryCaptureFile(
    context: Context,
): File {
    val directory =
        File(context.cacheDir, TEMP_DIRECTORY_NAME).apply {
            mkdirs()
        }

    return File(directory, TEMP_FILE_NAME)
}

private fun purgeTemporaryAssets(
    context: Context,
) {
    val cacheDirectory = context.applicationContext.cacheDir
    val exactTempFile = File(cacheDirectory, TEMP_FILE_NAME)

    runCatching {
        if (exactTempFile.exists()) {
            exactTempFile.delete()
        }
    }

    runCatching {
        val captureDirectory =
            File(cacheDirectory, TEMP_DIRECTORY_NAME)

        captureDirectory.listFiles()
            ?.forEach { file ->
                if (
                    file.isFile &&
                    (
                        file.name == TEMP_FILE_NAME ||
                            file.name.startsWith("temp_form_") ||
                            file.name.startsWith("capture_")
                    )
                ) {
                    file.delete()
                }
            }
    }

    runCatching {
        cacheDirectory.listFiles()
            ?.forEach { file ->
                if (
                    file.isFile &&
                    (
                        file.name == TEMP_FILE_NAME ||
                            file.name.startsWith("temp_form_")
                    )
                ) {
                    file.delete()
                }
            }
    }
}
