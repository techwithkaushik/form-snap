package org.techwithkaushik.formsnap.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.roundToInt
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.Tensor

class YoloV8TfliteDetector(
    context: Context,
    private val modelAssetName: String = DEFAULT_MODEL_ASSET,
    private val config: DetectionConfig = DetectionConfig(),
    private val modelFile: File? = null,
) : AutoCloseable {

    companion object {
        const val DEFAULT_MODEL_ASSET = "formsnap_yolov8n_int8.tflite"
    }

    private val appContext = context.applicationContext
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val decoder = YoloV8OutputDecoder(config)

    @Volatile
    private var closed = false

    private var interpreter: Interpreter? = null
    private var inputBuffer: ByteBuffer? = null
    private var outputBuffer: ByteBuffer? = null
    private var outputShape: IntArray = intArrayOf()

    fun detect(bitmap: Bitmap): List<DetectedObject> {
        check(!closed) { "Detector is already closed." }
        val future: Future<List<DetectedObject>> = executor.submit<List<DetectedObject>> {
            detectOnInferenceThread(bitmap)
        }
        return future.get()
    }

    fun detectAsync(
        bitmap: Bitmap,
        onResult: (List<DetectedObject>) -> Unit,
        onError: (Throwable) -> Unit = {},
    ) {
        if (closed) {
            onError(IllegalStateException("Detector is already closed."))
            return
        }
        executor.execute {
            runCatching { detectOnInferenceThread(bitmap) }
                .onSuccess(onResult)
                .onFailure(onError)
        }
    }

    fun modelAvailable(): Boolean =
        runCatching {
            appContext.assets.open(modelAssetName).use { }
            true
        }.getOrDefault(false)

    private fun detectOnInferenceThread(bitmap: Bitmap): List<DetectedObject> {
        require(!bitmap.isRecycled) { "Input bitmap is recycled." }
        ensureInterpreter()

        val model = requireNotNull(interpreter)
        val input = requireNotNull(inputBuffer)
        val output = requireNotNull(outputBuffer)
        val prepared = letterbox(bitmap, config.inputSize)

        try {
            input.clear()
            writeBitmapToInput(prepared.bitmap, input, model.getInputTensor(0))
            input.rewind()

            output.clear()
            model.run(input, output)
            output.rewind()

            val values = readOutputAsFloatArray(
                output = output,
                tensor = model.getOutputTensor(0),
            )

            return decoder.decode(values, outputShape, prepared.transform)
        } finally {
            prepared.bitmap.recycle()
        }
    }

    private fun ensureInterpreter() {
        if (interpreter != null) return

        check(modelAvailable()) {
            "AI model '$modelAssetName' is missing. Add the trained YOLOv8 INT8 model to src/main/assets."
        }

        val modelBuffer = if (modelFile != null) {
            FileInputStream(modelFile).channel.map(
                java.nio.channels.FileChannel.MapMode.READ_ONLY,
                0,
                modelFile.length(),
            )
        } else {
            val descriptor = appContext.assets.openFd(modelAssetName)
            val mapped = FileInputStream(descriptor.fileDescriptor).channel.map(
                java.nio.channels.FileChannel.MapMode.READ_ONLY,
                descriptor.startOffset,
                descriptor.declaredLength,
            )
            descriptor.close()
            mapped
        }

        // Keep the first production path on the TensorFlow Lite CPU runtime.
        // This avoids GPU delegate ABI/classpath conflicts across old Android
        // devices. The detector still runs fully offline and on-device.
        val options = Interpreter.Options().apply {
            setNumThreads(4)
        }

        val created = Interpreter(modelBuffer, options)
        interpreter = created
        outputShape = created.getOutputTensor(0).shape().copyOf()
        inputBuffer = ByteBuffer
            .allocateDirect(created.getInputTensor(0).numBytes())
            .order(ByteOrder.nativeOrder())
        outputBuffer = ByteBuffer
            .allocateDirect(created.getOutputTensor(0).numBytes())
            .order(ByteOrder.nativeOrder())
    }

    private fun writeBitmapToInput(
        bitmap: Bitmap,
        target: ByteBuffer,
        tensor: Tensor,
    ) {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val type = tensor.dataType()
        val params = tensor.quantizationParams()
        val scale = params.scale
        val zeroPoint = params.zeroPoint

        target.rewind()
        for (pixel in pixels) {
            val r = Color.red(pixel) / 255f
            val g = Color.green(pixel) / 255f
            val b = Color.blue(pixel) / 255f

            when (type) {
                DataType.FLOAT32 -> {
                    target.putFloat(r)
                    target.putFloat(g)
                    target.putFloat(b)
                }
                DataType.UINT8 -> {
                    target.put(quantize(r, scale, zeroPoint).coerceIn(0, 255).toByte())
                    target.put(quantize(g, scale, zeroPoint).coerceIn(0, 255).toByte())
                    target.put(quantize(b, scale, zeroPoint).coerceIn(0, 255).toByte())
                }
                DataType.INT8 -> {
                    target.put(quantize(r, scale, zeroPoint).coerceIn(-128, 127).toByte())
                    target.put(quantize(g, scale, zeroPoint).coerceIn(-128, 127).toByte())
                    target.put(quantize(b, scale, zeroPoint).coerceIn(-128, 127).toByte())
                }
                else -> error("Unsupported TFLite input type: $type")
            }
        }
    }

    private fun readOutputAsFloatArray(
        output: ByteBuffer,
        tensor: Tensor,
    ): FloatArray {
        output.rewind()
        val count = tensor.numElements()
        val result = FloatArray(count)
        val params = tensor.quantizationParams()
        val scale = params.scale
        val zeroPoint = params.zeroPoint

        when (tensor.dataType()) {
            DataType.FLOAT32 -> repeat(count) { result[it] = output.getFloat() }
            DataType.UINT8 -> repeat(count) {
                result[it] = ((output.get().toInt() and 0xFF) - zeroPoint) * scale
            }
            DataType.INT8 -> repeat(count) {
                result[it] = (output.get().toInt() - zeroPoint) * scale
            }
            else -> error("Unsupported TFLite output type: ${tensor.dataType()}")
        }
        return result
    }

    private fun quantize(
        value: Float,
        scale: Float,
        zeroPoint: Int,
    ): Int {
        require(scale > 0f) { "Invalid TFLite input quantization scale." }
        return (value / scale + zeroPoint).roundToInt()
    }

    private fun letterbox(
        source: Bitmap,
        size: Int,
    ): LetterboxedBitmap {
        val scale = minOf(
            size.toFloat() / source.width,
            size.toFloat() / source.height,
        )
        val scaledWidth = (source.width * scale).roundToInt().coerceAtLeast(1)
        val scaledHeight = (source.height * scale).roundToInt().coerceAtLeast(1)

        val resized = Bitmap.createScaledBitmap(source, scaledWidth, scaledHeight, true)
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(Color.rgb(114, 114, 114))
        val left = (size - scaledWidth) / 2f
        val top = (size - scaledHeight) / 2f
        canvas.drawBitmap(resized, left, top, Paint(Paint.FILTER_BITMAP_FLAG))
        resized.recycle()

        return LetterboxedBitmap(
            bitmap = output,
            transform = LetterboxTransform(
                scale = scale,
                padX = left,
                padY = top,
                sourceWidth = source.width,
                sourceHeight = source.height,
                inputSize = size,
            ),
        )
    }

    override fun close() {
        if (closed) return
        closed = true
        executor.submit {
            interpreter?.close()
            interpreter = null
        }.get()
        executor.shutdown()
    }

    private data class LetterboxedBitmap(
        val bitmap: Bitmap,
        val transform: LetterboxTransform,
    )
}
