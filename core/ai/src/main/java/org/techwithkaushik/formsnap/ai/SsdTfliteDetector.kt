package org.techwithkaushik.formsnap.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter

/**
 * Adapter for the standard four-output TFLite Detection_PostProcess SSD model.
 * Preprocessing is deliberately limited to common RGB image inputs; models with
 * custom normalization/preprocessing metadata may need a dedicated adapter.
 */
class SsdTfliteDetector(
    context: Context,
    private val modelFile: File,
    private val config: DetectionConfig = DetectionConfig(),
) : DetectionAdapter {
    private val appContext = context.applicationContext
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    @Volatile private var closed = false
    private var interpreter: Interpreter? = null
    private var inputShape = intArrayOf()
    private var inputChannelsFirst = false
    private var inputType = DataType.FLOAT32
    private var inputScale = 0f
    private var inputZeroPoint = 0
    private var outputShapes: List<IntArray> = emptyList()
    private var inputBuffer: ByteBuffer? = null
    private var outputBuffers: List<ByteBuffer> = emptyList()

    @Volatile
    override var lastInferenceDiagnostics: String = "SSD inference has not run"
        private set

    init {
        require(modelFile.isFile && modelFile.length() > 0L) { "TFLite model file is missing or empty." }
    }

    override fun detectAsync(
        bitmap: Bitmap,
        onResult: (List<DetectedObject>) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        if (closed) {
            onError(IllegalStateException("SSD detector is closed."))
            return
        }
        executor.execute {
            runCatching { detect(bitmap) }
                .onSuccess(onResult)
                .onFailure(onError)
        }
    }

    private fun detect(bitmap: Bitmap): List<DetectedObject> {
        check(!closed) { "SSD detector is closed." }
        require(!bitmap.isRecycled) { "Input bitmap is recycled." }
        ensureInterpreter()
        val model = requireNotNull(interpreter)
        val input = requireNotNull(inputBuffer)
        val height = if (inputChannelsFirst) inputShape[2] else inputShape[1]
        val width = if (inputChannelsFirst) inputShape[3] else inputShape[2]
        val resized = Bitmap.createScaledBitmap(bitmap, width, height, true)
        try {
            val pixels = IntArray(width * height)
            resized.getPixels(pixels, 0, width, 0, 0, width, height)
            input.clear()
            fun put(channel: Int) {
                when (inputType) {
                    DataType.FLOAT32 -> input.putFloat(channel / 255f)
                    DataType.UINT8 -> {
                        val quantized = if (inputScale > 0f) (channel / inputScale + inputZeroPoint).toInt() else channel
                        input.put(quantized.coerceIn(0, 255).toByte())
                    }
                    DataType.INT8 -> {
                        require(inputScale > 0f) { "SSD INT8 input requires valid quantization parameters." }
                        val quantized = ((channel / 255f) / inputScale + inputZeroPoint).toInt()
                        input.put(quantized.coerceIn(-128, 127).toByte())
                    }
                    else -> error("Unsupported SSD input type: $inputType")
                }
            }
            if (inputChannelsFirst) {
                for (channel in 0..2) for (pixel in pixels) {
                    put(when (channel) { 0 -> Color.red(pixel); 1 -> Color.green(pixel); else -> Color.blue(pixel) })
                }
            } else {
                for (pixel in pixels) {
                    put(Color.red(pixel)); put(Color.green(pixel)); put(Color.blue(pixel))
                }
            }
            input.rewind()
            val outputs = outputBuffers.map { it.apply { clear() } }
            val outputMap = outputs.mapIndexed { index, buffer -> index to buffer }.toMap()
            model.runForMultipleInputsOutputs(arrayOf(input), outputMap)
            val values = outputs.mapIndexed { index, buffer -> readOutput(buffer, model.getOutputTensor(index)) }
            lastInferenceDiagnostics = "SSD Detection_PostProcess | input=${inputShape.contentToString()} | outputs=${outputShapes.joinToString { it.contentToString() }} | rawCount=${values.getOrNull(3)?.firstOrNull() ?: 0f}"
            val transform = LetterboxTransform(
                scale = 1f,
                padX = 0f,
                padY = 0f,
                sourceWidth = bitmap.width,
                sourceHeight = bitmap.height,
                inputSize = width,
                inputWidth = width,
                inputHeight = height,
            )
            return SsdDetectionDecoder.decode(
                outputShapes, values, transform, config.classLabels,
                config.confidenceThreshold, config.maxDetections,
            )
        } finally {
            if (resized !== bitmap) resized.recycle()
        }
    }

    private fun ensureInterpreter() {
        if (interpreter != null) return
        val buffer = FileInputStream(modelFile).use { stream ->
            stream.channel.use { it.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, it.size()) }
        }
        val created = Interpreter(buffer, Interpreter.Options().setNumThreads(2))
        try {
            require(created.inputTensorCount == 1) { "SSD adapter requires exactly one image input tensor." }
            val input = created.getInputTensor(0)
            inputShape = input.shape().copyOf()
            require(inputShape.size == 4 && inputShape[0] == 1 &&
                (inputShape[3] == 3 || inputShape[1] == 3) && inputShape.all { it > 0 }
            ) { "Unsupported SSD image input shape: ${inputShape.contentToString()}" }
            inputChannelsFirst = inputShape[1] == 3
            inputType = input.dataType()
            require(inputType in setOf(DataType.FLOAT32, DataType.UINT8, DataType.INT8)) {
                "Unsupported SSD input type: $inputType"
            }
            val q = input.quantizationParams()
            inputScale = q.scale
            inputZeroPoint = q.zeroPoint
            val shapes = (0 until created.outputTensorCount).map { created.getOutputTensor(it).shape().copyOf() }
            require(SsdOutputContract.isLikelyDetectionPostProcess(shapes)) {
                "Model does not match the supported four-output SSD Detection_PostProcess signature: ${shapes.joinToString { it.contentToString() }}"
            }
            require((0 until created.outputTensorCount).all {
                created.getOutputTensor(it).dataType() in setOf(DataType.FLOAT32, DataType.UINT8, DataType.INT8)
            }) { "Unsupported SSD output data type." }
            inputBuffer = ByteBuffer.allocateDirect(input.numBytes()).order(ByteOrder.nativeOrder())
            outputBuffers = (0 until created.outputTensorCount).map { index ->
                ByteBuffer.allocateDirect(created.getOutputTensor(index).numBytes()).order(ByteOrder.nativeOrder())
            }
            outputShapes = shapes
            interpreter = created
        } catch (error: Throwable) {
            created.close()
            throw error
        }
    }

    private fun readOutput(buffer: ByteBuffer, tensor: org.tensorflow.lite.Tensor): FloatArray {
        buffer.rewind()
        val count = tensor.numElements()
        val result = FloatArray(count)
        val q = tensor.quantizationParams()
        when (tensor.dataType()) {
            DataType.FLOAT32 -> repeat(count) { result[it] = buffer.float }
            DataType.UINT8 -> repeat(count) { result[it] = ((buffer.get().toInt() and 0xFF) - q.zeroPoint) * q.scale }
            DataType.INT8 -> repeat(count) { result[it] = (buffer.get().toInt() - q.zeroPoint) * q.scale }
            else -> error("Unsupported SSD output type: ${tensor.dataType()}")
        }
        return result
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { executor.submit { interpreter?.close(); interpreter = null }.get() }
        executor.shutdown()
    }
}
