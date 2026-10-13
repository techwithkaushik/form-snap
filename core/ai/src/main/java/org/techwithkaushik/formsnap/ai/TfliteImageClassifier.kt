package org.techwithkaushik.formsnap.ai

import android.graphics.Bitmap
import android.graphics.Color
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter

data class ClassificationPrediction(
    val classId: Int,
    val label: String,
    val confidence: Float,
)

/**
 * Minimal offline classifier for common single-image TFLite classification
 * exports. Supports fixed RGB NHWC/NCHW inputs and a single class-score output.
 *
 * Preprocessing defaults to RGB [0,1]. Models requiring a different
 * normalization or non-image inputs must provide an adapter rather than being
 * silently treated as compatible.
 */
class TfliteImageClassifier(
    modelFile: File,
    private val labels: List<String> = emptyList(),
    numThreads: Int = 2,
) : Closeable {
    private val interpreter: Interpreter
    private val inputShape: IntArray
    private val outputShape: IntArray
    private val channelsFirst: Boolean
    private val inputType: DataType
    private val outputType: DataType
    private val inputScale: Float
    private val inputZeroPoint: Int
    private val normalizationMean: FloatArray
    private val normalizationStd: FloatArray
    private val outputScale: Float
    private val outputZeroPoint: Int
    private var closed = false

    init {
        require(modelFile.isFile && modelFile.length() > 0L) { "TFLite model file is missing or empty." }
        val buffer = FileInputStream(modelFile).use { stream ->
            stream.channel.use { it.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, it.size()) }
        }
        interpreter = Interpreter(buffer, Interpreter.Options().setNumThreads(numThreads.coerceIn(1, 4)))
        try {
            require(interpreter.inputTensorCount == 1 && interpreter.outputTensorCount == 1) {
                "Classifier adapter requires exactly one input and one output tensor."
            }
            val input = interpreter.getInputTensor(0)
            val output = interpreter.getOutputTensor(0)
            inputShape = input.shape().copyOf()
            outputShape = output.shape().copyOf()
            require(ModelTaskClassifier.classify(inputShape, outputShape) == ModelTask.IMAGE_CLASSIFICATION) {
                "Model tensors do not match the supported image-classification contract."
            }
            channelsFirst = inputShape[1] == 3
            inputType = input.dataType()
            outputType = output.dataType()
            require(inputType in setOf(DataType.FLOAT32, DataType.UINT8, DataType.INT8)) {
                "Unsupported classifier input type: $inputType"
            }
            require(outputType in setOf(DataType.FLOAT32, DataType.UINT8, DataType.INT8)) {
                "Unsupported classifier output type: $outputType"
            }
            val iq = input.quantizationParams()
            val oq = output.quantizationParams()
            inputScale = iq.scale
            inputZeroPoint = iq.zeroPoint
            val normalization = readNormalizationOptions(buffer)
            normalizationMean = normalization?.first ?: floatArrayOf(0f)
            normalizationStd = normalization?.second ?: floatArrayOf(255f)
            require(normalizationMean.size in 1..3 && normalizationStd.size == normalizationMean.size && normalizationStd.all { it.isFinite() && it != 0f }) {
                "Invalid TFLite metadata normalization options."
            }
            outputScale = oq.scale
            outputZeroPoint = oq.zeroPoint
            if (inputType != DataType.FLOAT32) require(inputScale > 0f) { "Invalid input quantization scale." }
            if (outputType != DataType.FLOAT32) require(outputScale > 0f) { "Invalid output quantization scale." }
        } catch (error: Throwable) {
            interpreter.close()
            throw error
        }
    }

    @Synchronized
    fun classify(bitmap: Bitmap, topK: Int = 5): List<ClassificationPrediction> {
        check(!closed) { "Classifier is closed." }
        require(!bitmap.isRecycled) { "Input bitmap is recycled." }
        val height = if (channelsFirst) inputShape[2] else inputShape[1]
        val width = if (channelsFirst) inputShape[3] else inputShape[2]
        val resized = Bitmap.createScaledBitmap(bitmap, width, height, true)
        try {
            val pixels = IntArray(width * height)
            resized.getPixels(pixels, 0, width, 0, 0, width, height)
            val input = ByteBuffer.allocateDirect(interpreter.getInputTensor(0).numBytes()).order(ByteOrder.nativeOrder())
            fun put(channel: Int, channelIndex: Int) {
                val index = if (normalizationMean.size == 1) 0 else channelIndex
                val normalized = (channel.toFloat() - normalizationMean[index]) / normalizationStd[index]
                when (inputType) {
                    DataType.FLOAT32 -> input.putFloat(normalized)
                    DataType.UINT8 -> input.put(kotlin.math.round(normalized / inputScale + inputZeroPoint).toInt().coerceIn(0, 255).toByte())
                    DataType.INT8 -> input.put(kotlin.math.round(normalized / inputScale + inputZeroPoint).toInt().coerceIn(-128, 127).toByte())
                    else -> error("Unsupported classifier input type: $inputType")
                }
            }
            if (channelsFirst) {
                for (channel in 0..2) for (pixel in pixels) put(
                    when (channel) { 0 -> Color.red(pixel); 1 -> Color.green(pixel); else -> Color.blue(pixel) }, channel,
                )
            } else {
                for (pixel in pixels) {
                    put(Color.red(pixel), 0); put(Color.green(pixel), 1); put(Color.blue(pixel), 2)
                }
            }
            input.rewind()
            val outputTensor = interpreter.getOutputTensor(0)
            val output = ByteBuffer.allocateDirect(outputTensor.numBytes()).order(ByteOrder.nativeOrder())
            interpreter.run(input, output)
            output.rewind()
            val scores = FloatArray(outputTensor.numElements())
            when (outputType) {
                DataType.FLOAT32 -> for (i in scores.indices) scores[i] = output.float
                DataType.UINT8 -> for (i in scores.indices) scores[i] = ((output.get().toInt() and 0xFF) - outputZeroPoint) * outputScale
                DataType.INT8 -> for (i in scores.indices) scores[i] = (output.get().toInt() - outputZeroPoint) * outputScale
                else -> error("Unsupported classifier output type: $outputType")
            }
            return ClassificationScoreDecoder.topK(scores, topK).map { (id, confidence) ->
                ClassificationPrediction(
                    id,
                    labels.getOrNull(id)?.takeIf(String::isNotBlank) ?: "Class $id",
                    confidence,
                )
            }
        } finally {
            if (resized !== bitmap) resized.recycle()
        }
    }


    /**
     * Read per-channel normalization from TFLite metadata when it exists.
     * Metadata is optional; without it, use the conservative RGB [0,1]
     * convention (mean=0, std=255). Reflection keeps this adapter tolerant of
     * metadata-schema generated API versions while still reading official
     * NormalizationOptions fields.
     */
    private fun readNormalizationOptions(model: java.nio.ByteBuffer): Pair<FloatArray, FloatArray>? = runCatching {
        val extractorClass = Class.forName("org.tensorflow.lite.support.metadata.MetadataExtractor")
        val extractor = extractorClass.getConstructor(java.nio.ByteBuffer::class.java).newInstance(model)
        if (!(extractorClass.getMethod("hasMetadata").invoke(extractor) as Boolean)) return@runCatching null
        val tensorMetadata = extractorClass.getMethod("getInputTensorMetadata", Int::class.javaPrimitiveType)
            .invoke(extractor, 0) ?: return@runCatching null
        val processCount = tensorMetadata.javaClass.getMethod("processUnitsLength").invoke(tensorMetadata) as Int
        val enumClass = Class.forName("org.tensorflow.lite.support.metadata.schema.ProcessUnitOptions")
        val normalizationType = (enumClass.getField("NormalizationOptions").get(null) as Number).toInt()
        for (index in 0 until processCount) {
            val unit = tensorMetadata.javaClass.getMethod("processUnits", Int::class.javaPrimitiveType).invoke(tensorMetadata, index)
            val optionType = unit.javaClass.getMethod("optionsType").invoke(unit) as Number
            if (optionType.toInt() != normalizationType) continue
            val optionsMethod = unit.javaClass.methods.first { it.name == "options" && it.parameterTypes.size == 1 }
            val options = optionsMethod.invoke(unit, optionsMethod.parameterTypes[0].getDeclaredConstructor().newInstance())
            val meanCount = options.javaClass.getMethod("meanLength").invoke(options) as Int
            val stdCount = options.javaClass.getMethod("stdLength").invoke(options) as Int
            if (meanCount !in 1..3 || meanCount != stdCount) return@runCatching null
            val mean = FloatArray(meanCount) { options.javaClass.getMethod("mean", Int::class.javaPrimitiveType).invoke(options, it) as Float }
            val std = FloatArray(stdCount) { options.javaClass.getMethod("std", Int::class.javaPrimitiveType).invoke(options, it) as Float }
            return@runCatching mean to std
        }
        null
    }.getOrNull()

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        interpreter.close()
    }
}
