package org.techwithkaushik.formsnap.capture

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private const val TEMP_FILE_NAME = "temp_form.jpg"

@Composable
fun CameraPreview(
    modifier: Modifier = Modifier,
    onCaptured: (File) -> Unit,
    onCaptureError: (Throwable) -> Unit = {},
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var cameraSelector by remember {
        mutableStateOf(CameraSelector.DEFAULT_BACK_CAMERA)
    }

    val executor = remember {
        Executors.newSingleThreadExecutor()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            cameraProvider = null
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            cameraProvider?.unbindAll()
            executor.shutdown()
            purgeTemporaryCapture(context.cacheDir)
        }
    }

    LaunchedEffect(Unit) {
        if (
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA,
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = {
                PreviewView(it).apply {
                    implementationMode = PreviewView.ImplementationMode.PERFORMANCE
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }
            },
            update = { previewView ->
                bindCamera(
                    context = context,
                    lifecycleOwner = lifecycleOwner,
                    previewView = previewView,
                    executor = executor,
                    selector = cameraSelector,
                ) { capture ->
                    imageCapture = capture
                }
            },
        )

        LaunchedEffect(cameraSelector) {
            imageCapture = imageCapture
        }
    }
}

fun CameraCaptureController.capture(): File? {
    val outputFile = File(
        context.cacheDir,
        TEMP_FILE_NAME,
    )
    purgeTemporaryCapture(context.cacheDir)

    val capture = imageCapture ?: return null
    val latch = java.util.concurrent.CountDownLatch(1)
    var result: File? = null
    var error: Throwable? = null

    val options = ImageCapture.OutputFileOptions.Builder(outputFile)
        .setMetadata(
            ImageCapture.Metadata().apply {
                isReversedHorizontal = false
            },
        )
        .build()

    capture.takePicture(
        options,
        executor,
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(
                outputFileResults: ImageCapture.OutputFileResults,
            ) {
                result = outputFile.takeIf { it.isFile && it.length() > 0L }
                latch.countDown()
            }

            override fun onError(exception: ImageCaptureException) {
                error = exception
                latch.countDown()
            }
        },
    )

    latch.await()
    error?.let(onCaptureError)
    result?.let(onCaptured)
    return result
}

private fun bindCamera(
    context: android.content.Context,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    previewView: PreviewView,
    executor: ExecutorService,
    selector: CameraSelector,
    onCaptureReady: (ImageCapture) -> Unit,
) {
    val future = ProcessCameraProvider.getInstance(context)
    future.addListener(
        {
            runCatching {
                val provider = future.get()
                val preview = Preview.Builder()
                    .setTargetRotation(previewView.display.rotation)
                    .build()
                    .also { it.surfaceProvider = previewView.surfaceProvider }

                val imageCapture = ImageCapture.Builder()
                    .setCaptureMode(
                        ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY,
                    )
                    .setJpegQuality(92)
                    .setTargetRotation(previewView.display.rotation)
                    .build()

                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    selector,
                    preview,
                    imageCapture,
                )
                onCaptureReady(imageCapture)
            }
        }.onFailure { throwable ->
            android.util.Log.e(
                "FormSnapCamera",
                "Unable to bind CameraX.",
                throwable,
            )
        },
        ContextCompat.getMainExecutor(context),
    )
}

private fun purgeTemporaryCapture(
    cacheDir: File,
) {
    val tempFile = File(cacheDir, TEMP_FILE_NAME)
    if (tempFile.exists()) {
        runCatching {
            tempFile.delete()
        }
    }

    cacheDir.listFiles()
        ?.asSequence()
        ?.filter { file ->
            file.isFile &&
                file.name.startsWith("temp_form_") &&
                file.name.endsWith(".jpg")
        }
        ?.forEach { file ->
            runCatching {
                file.delete()
            }
        }
}

class CameraCaptureController(
    internal val context: android.content.Context,
    internal val executor: ExecutorService,
    internal val imageCapture: ImageCapture?,
    private val onCaptured: (File) -> Unit,
    private val onCaptureError: (Throwable) -> Unit,
)
