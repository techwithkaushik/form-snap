package org.techwithkaushik.formsnap.core.ai

import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter

/**
 * Loads a user-provided TFLite model without bundling it in the APK.
 *
 * This class deliberately exposes tensor metadata before preprocessing/decoding is added:
 * detection models have different input layouts and output encodings, so guessing those
 * details can make inference appear to work while producing incorrect boxes.
 */
class ExternalTfliteModel private constructor(
    private val interpreter: Interpreter,
    val input: TensorInfo,
    val outputs: List<TensorInfo>,
) : Closeable {

    data class TensorInfo(
        val index: Int,
        val name: String,
        val shape: IntArray,
        val dataType: DataType,
        val byteSize: Int,
    )

    data class InferenceResult(
        val outputs: List<FloatArray>,
        val outputTensors: List<TensorInfo>,
    )

    /**
     * Runs a model stored at [modelFile]. [numThreads] is kept low by default for
     * older/low-memory Android devices.
     */
    fun runFloatInput(inputValues: FloatArray, numThreads: Int = 2): InferenceResult {
        require(input.dataType == DataType.FLOAT32) {
            "This input tensor is ${input.dataType}; float preprocessing is not valid for this model."
        }
        val expected = input.shape.fold(1L) { acc, dim ->
            require(dim > 0) { "Dynamic input dimensions are not supported by runFloatInput: ${input.shape.contentToString()}" }
            acc * dim
        }
        require(expected == inputValues.size.toLong()) {
            "Wrong input element count: expected $expected, received ${inputValues.size}. " +
                "Input shape: ${input.shape.contentToString()}"
        }

        // Use a direct native-order buffer so TFLite can read it without an extra copy.
        val inputBuffer = ByteBuffer.allocateDirect(inputValues.size * 4)
            .order(ByteOrder.nativeOrder())
        inputValues.forEach(inputBuffer::putFloat)
        inputBuffer.rewind()

        val outputBuffers = outputs.map { tensor ->
            require(tensor.dataType == DataType.FLOAT32) {
                "Output tensor ${tensor.name} uses ${tensor.dataType}; float output decoding is not valid."
            }
            ByteBuffer.allocateDirect(tensor.byteSize).order(ByteOrder.nativeOrder())
        }
        val outputMap = outputBuffers.mapIndexed { index, buffer -> index to buffer }.toMap()
        interpreter.runForMultipleInputsOutputs(arrayOf(inputBuffer), outputMap)

        val values = outputBuffers.mapIndexed { index, buffer ->
            buffer.rewind()
            val count = outputs[index].byteSize / 4
            FloatArray(count) { buffer.float }
        }
        return InferenceResult(values, outputs)
    }

    override fun close() {
        interpreter.close()
    }

    companion object {
        fun open(modelFile: File, numThreads: Int = 2): ExternalTfliteModel {
            require(modelFile.isFile) { "Model file does not exist: ${modelFile.absolutePath}" }
            require(modelFile.length() > 0L) { "Model file is empty." }

            val interpreter = FileInputStream(modelFile).use { stream ->
                val channel = stream.channel
                val mapped = channel.map(
                    java.nio.channels.FileChannel.MapMode.READ_ONLY,
                    0,
                    channel.size(),
                )
                Interpreter(mapped, Interpreter.Options().setNumThreads(numThreads.coerceIn(1, 4)))
            }

            try {
                val input = interpreter.getInputTensor(0).toInfo(0)
                val outputs = (0 until interpreter.outputTensorCount).map { index ->
                    interpreter.getOutputTensor(index).toInfo(index)
                }
                return ExternalTfliteModel(interpreter, input, outputs)
            } catch (error: Throwable) {
                interpreter.close()
                throw error
            }
        }

        private fun org.tensorflow.lite.Tensor.toInfo(index: Int) = TensorInfo(
            index = index,
            name = name(),
            shape = shape().copyOf(),
            dataType = dataType(),
            byteSize = numBytes(),
        )
    }
}
