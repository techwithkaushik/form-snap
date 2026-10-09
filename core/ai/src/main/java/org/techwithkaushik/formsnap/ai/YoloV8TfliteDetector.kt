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
    private var inputShape: IntArray = intArrayOf()
    private var inputLayout: InputLayout = InputLayout.NHWC
    private var inputHeight: Int = config.inputSize
    private var inputWidth: Int = config.inputSize

    @Volatile
    var lastInferenceDiagnostics: String = "Inference has not run"
        private set

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
        modelFile?.let { it.isFile && it.length() > 0L } ?: runCatching {
            appContext.assets.open(modelAssetName).use { }
            true
        }.getOrDefault(false)

    /**
     * Returns the actual tensor metadata after loading the model.
     * Useful for diagnosing imported models that do not match FormSnap's
     * PHOTO(0)/SIGNATURE(1) detector contract.
     */
    fun modelDiagnostics(): String {
        check(!closed) { "Detector is already closed." }
        return executor.submit<String> {
            ensureInterpreter()
            val model = requireNotNull(interpreter)
            val input = model.getInputTensor(0)
            val output = model.getOutputTensor(0)
            "Model: ${modelFile?.name ?: modelAssetName}; " +
                "input=${input.shape().contentToString()} ${input.dataType()}; " +
                "output=${output.shape().contentToString()} ${output.dataType()}; " +
"mode=OBJECT DETECTION; classes=PHOTO(0), SIGNATURE(1)"
        }.get()
    }

    private fun detectOnInferenceThread(bitmap: Bitmap): List<DetectedObject> {
        require(!bitmap.isRecycled) { "Input bitmap is recycled." }
        ensureInterpreter()

        val model = requireNotNull(interpreter)
        val input = requireNotNull(inputBuffer)
        val output = requireNotNull(outputBuffer)
        val prepared = letterbox(bitmap, inputWidth, inputHeight)
        try {
            input.clear()
            writeBitmapToInput(prepared.bitmap, input, model.getInputTensor(0))
            input.rewind()
            output.clear()
            model.run(input, output)
            val values = readOutputAsFloatArray(output, model.getOutputTensor(0))
            lastInferenceDiagnostics = summarizeOutput(values, outputShape)
            return decoder.decode(values, outputShape, prepared.transform)
        } finally {
            prepared.bitmap.recycle()
        }
    }

    private fun summarizeOutput(values: FloatArray, shape: IntArray): String {
        if (shape.size < 3) return "tensor=${shape.contentToString()} values=${values.size}"
        val a = shape[shape.size - 2]
        val b = shape.last()
        val channelsFirst = a == 6 && b > a
        val channels = if (channelsFirst) a else b
        val candidates = if (channelsFirst) b else a
        if (channels != 6 || candidates <= 0 || values.size < channels * candidates) {
            val finite = values.filter { it.isFinite() }
            return "tensor=${shape.contentToString()} min=${finite.minOrNull()} max=${finite.maxOrNull()}"
        }
        var best0 = Float.NEGATIVE_INFINITY
        var best1 = Float.NEGATIVE_INFINITY
        var above005 = 0
        var above012 = 0
        var minClass = Float.POSITIVE_INFINITY
        var maxClass = Float.NEGATIVE_INFINITY
        for (i in 0 until candidates) {
            val s0 = if (channelsFirst) values[4 * candidates + i] else values[i * channels + 4]
            val s1 = if (channelsFirst) values[5 * candidates + i] else values[i * channels + 5]
            if (s0.isFinite()) { best0 = maxOf(best0, s0); minClass = minOf(minClass, s0); maxClass = maxOf(maxClass, s0) }
            if (s1.isFinite()) { best1 = maxOf(best1, s1); minClass = minOf(minClass, s1); maxClass = maxOf(maxClass, s1) }
            val score = maxOf(s0, s1)
            if (score.isFinite() && score >= 0.05f) above005++
            if (score.isFinite() && score >= 0.12f) above012++
        }
        return "tensor=${shape.contentToString()} classRange=${"%.3f".format(java.util.Locale.US, minClass)}..${"%.3f".format(java.util.Locale.US, maxClass)} bestP=${"%.3f".format(java.util.Locale.US, best0)} bestS=${"%.3f".format(java.util.Locale.US, best1)} >=.05:$above005 >=.12:$above012"
    }

    private fun ensureInterpreter() {
        if (interpreter != null) return

        check(modelAvailable()) {
            "No AI model is available. Open AI Model in FormSnap and import your trained PHOTO/SIGNATURE .tflite detector."
        }

        val modelBuffer = if (modelFile != null) {
            FileInputStream(modelFile).use { stream ->
                stream.channel.map(
                    java.nio.channels.FileChannel.MapMode.READ_ONLY,
                    0,
                    modelFile.length(),
                )
            }
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
        inputShape = created.getInputTensor(0).shape().copyOf()
        inputLayout = detectInputLayout(inputShape)
        inputHeight = when (inputLayout) {
            InputLayout.NHWC -> inputShape[1]
            InputLayout.NCHW -> inputShape[2]
        }.takeIf { it > 0 } ?: config.inputSize
        inputWidth = when (inputLayout) {
            InputLayout.NHWC -> inputShape[2]
            InputLayout.NCHW -> inputShape[3]
        }.takeIf { it > 0 } ?: config.inputSize
        validateOutputContract(outputShape)
        inputBuffer = ByteBuffer
            .allocateDirect(created.getInputTensor(0).numBytes())
            .order(ByteOrder.nativeOrder())
        outputBuffer = ByteBuffer
            .allocateDirect(created.getOutputTensor(0).numBytes())
            .order(ByteOrder.nativeOrder())
    }

    private fun validateOutputContract(shape: IntArray) {
        require(shape.size >= 2) {
            "Unsupported FormSnap model output shape ${shape.contentToString()}. " +
                "Expected a 2-class PHOTO/SIGNATURE YOLO output."
        }

        val last = shape.last()
        val channelsFirst = shape.size >= 3 &&
            shape[shape.size - 2] == 6 && last > 6
        val channelsLast = shape.size >= 3 &&
            last == 6 && shape[shape.size - 2] > 6
        val nmsOutput = last in 6..7

        require(channelsFirst || channelsLast || nmsOutput) {
            "The active model output is ${shape.contentToString()}. Expected a two-class PHOTO(0)/SIGNATURE(1) " +
                "YOLO object detector output. A classifier cannot locate or crop objects."
        }
    }

    private fun classLabel(classId: Int): String =
        when (classId) {
            0 -> "Photo"
            1 -> "Signature"
            else -> "Class $classId"
        }

    private enum class InputLayout {
        NHWC,
        NCHW,
    }

    private fun detectInputLayout(shape: IntArray): InputLayout {
        require(shape.size == 4) {
            "Unsupported TFLite input shape: ${shape.contentToString()}. Expected a 4D image tensor."
        }

        // Standard TensorFlow Lite image tensors are NHWC: [1, height, width, 3].
        // Some exported YOLO models use NCHW: [1, 3, height, width].
        return when {
            shape[3] == 3 -> InputLayout.NHWC
            shape[1] == 3 -> InputLayout.NCHW
            else -> error(
                "Unsupported TFLite input layout: ${shape.contentToString()}. " +
                    "Expected channels=3 at dimension 1 (NCHW) or dimension 3 (NHWC).",
            )
        }
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

        fun put(value: Float) {
            when (type) {
                DataType.FLOAT32 -> target.putFloat(value)
                DataType.UINT8 -> target.put(quantize(value, scale, zeroPoint).coerceIn(0, 255).toByte())
                DataType.INT8 -> target.put(quantize(value, scale, zeroPoint).coerceIn(-128, 127).toByte())
                else -> error("Unsupported TFLite input type: $type")
            }
        }

        target.rewind()
        when (inputLayout) {
            InputLayout.NHWC -> {
                for (pixel in pixels) {
                    put(Color.red(pixel) / 255f)
                    put(Color.green(pixel) / 255f)
                    put(Color.blue(pixel) / 255f)
                }
            }
            InputLayout.NCHW -> {
                for (channel in 0..2) {
                    for (pixel in pixels) {
                        put(
                            when (channel) {
                                0 -> Color.red(pixel) / 255f
                                1 -> Color.green(pixel) / 255f
                                else -> Color.blue(pixel) / 255f
                            },
                        )
                    }
                }
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
        width: Int,
        height: Int,
    ): LetterboxedBitmap {
        val scale = minOf(
            width.toFloat() / source.width,
            height.toFloat() / source.height,
        )
        val scaledWidth = (source.width * scale).roundToInt().coerceAtLeast(1)
        val scaledHeight = (source.height * scale).roundToInt().coerceAtLeast(1)

        val resized = Bitmap.createScaledBitmap(source, scaledWidth, scaledHeight, true)
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(Color.rgb(114, 114, 114))
        val left = (width - scaledWidth) / 2f
        val top = (height - scaledHeight) / 2f
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
                inputSize = width,
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
