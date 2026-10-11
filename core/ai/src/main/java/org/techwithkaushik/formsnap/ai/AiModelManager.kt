package org.techwithkaushik.formsnap.ai

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import org.tensorflow.lite.Interpreter

data class AiModelInfo(
    val file: File,
    val name: String,
    val sizeBytes: Long,
    val active: Boolean,
    val inputDetails: String = "",
    val outputDetails: String = "",
    val classDetails: String = "",
)

class AiModelManager(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val modelsDir = File(appContext.filesDir, "ai-models").apply { mkdirs() }

    fun models(): List<AiModelInfo> =
        modelsDir.listFiles { file -> file.isFile && file.extension.equals("tflite", true) }
            ?.sortedBy { it.name.lowercase() }
            ?.map { file -> val metadata = runCatching { inspectModel(file) }.getOrNull(); AiModelInfo(file, file.name, file.length(), file.name == activeModelName(), metadata?.first.orEmpty(), metadata?.second.orEmpty(), metadata?.third.orEmpty()) }
            ?: emptyList()

    private fun inspectModel(file: File): Triple<String, String, String> {
        val buffer = FileInputStream(file).use { input ->
            input.channel.use { channel -> channel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, channel.size()) }
        }
        val interpreter = Interpreter(buffer, Interpreter.Options().setNumThreads(1))
        try {
            val input = (0 until interpreter.inputTensorCount).joinToString { index ->
                val tensor = interpreter.getInputTensor(index)
                "#$index ${tensor.shape().contentToString()} ${tensor.dataType()}"
            }
            val output = (0 until interpreter.outputTensorCount).joinToString { index ->
                val tensor = interpreter.getOutputTensor(index)
                "#$index ${tensor.shape().contentToString()} ${tensor.dataType()}"
            }
            val shape = interpreter.getOutputTensor(0).shape()
            val a = if (shape.size >= 2) shape[shape.lastIndex - 1] else 0
            val b = if (shape.isNotEmpty()) shape.last() else 0
            val count = when { a in 5..512 && b > a -> a - 4; b in 5..512 -> b - 4; b in 6..7 -> -1; else -> 0 }
            val labels = classLabels(file)\n            val classes = if (labels.isNotEmpty()) labels.mapIndexed { index, label -> "$index: $label" }.joinToString() else if (count > 0) (0 until count).joinToString { "Class $it" } else if (count == -1) "NMS class IDs; label map required" else "Unable to infer classes"
            return Triple(input, output, classes)
        } finally { interpreter.close() }
    }

    fun classLabels(file: File): List<String> = runCatching {
        File(modelsDir, file.nameWithoutExtension + ".labels.txt")
            .takeIf { it.isFile }?.readLines(Charsets.UTF_8)?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
    }.getOrDefault(emptyList())

    fun importLabels(uri: Uri, modelName: String) {
        val model = File(modelsDir, modelName)
        require(model.isFile && model.extension.equals("tflite", true)) { "Select an imported TFLite model first." }
        val labels = appContext.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readLines() }
            ?.map { it.trim() }?.filter { it.isNotEmpty() } ?: throw IllegalArgumentException("Unable to read labels file.")
        require(labels.isNotEmpty() && labels.size <= 1000) { "Labels file must contain 1–1000 class names." }
        FileOutputStream(File(modelsDir, model.nameWithoutExtension + ".labels.txt")).use { output ->
            output.write(labels.joinToString("\\n", postfix = "\\n").toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
    }

    fun activeModelFile(): File? =
        activeModelName()?.let { File(modelsDir, it).takeIf { file -> file.isFile && file.length() > 0L } }

    fun activeModelName(): String? =
        prefs.getString(KEY_ACTIVE, null)?.takeIf { name -> File(modelsDir, name).isFile }

    fun importModel(uri: Uri): AiModelInfo {
        // Android document providers can return an opaque URI or a misleading display name.
        // The actual file content and tensor contract are authoritative, not the provider name.
        val providerName = runCatching {
            appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index >= 0) cursor.getString(index) else null
                    } else null
                }
        }.getOrNull()
        val uriName = uri.lastPathSegment?.substringAfterLast('/')?.substringBefore('?')
            ?.takeIf { it.isNotBlank() && !it.contains(':') && !it.matches(Regex("[0-9a-fA-F-]{20,}")) }
        val discoveredName = providerName?.takeIf { it.isNotBlank() } ?: uriName
        // Keep a useful name only when the provider clearly supplies a .tflite name.
        // Otherwise use a neutral .tflite name and validate the copied bytes with TFLite.
        val originalName = discoveredName
            ?.takeIf { it.lowercase().endsWith(".tflite") }
            ?: "formsnap_model_" + System.currentTimeMillis() + ".tflite"

        val safeName = originalName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val destination = uniqueFile(safeName)
        val temp = File(modelsDir, "." + destination.name + ".part")
        try {
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(temp).use { output -> input.copyTo(output); output.fd.sync() }
            } ?: error("Unable to open selected model.")
            require(temp.length() > 0L) { "Selected model is empty." }

            // Validate the actual model bytes before publishing the file or changing preferences.
            validateCompatibleModel(temp)
            require(temp.renameTo(destination)) { "Unable to store AI model." }
            setActive(destination.name)
            return AiModelInfo(destination, destination.name, destination.length(), true)
        } catch (t: Throwable) {
            temp.delete()
            destination.delete()
            throw t
        }
    }

    fun setActive(name: String) {
        val file = File(modelsDir, name)
        require(file.isFile && file.length() > 0L) { "AI model not found or empty." }
        validateCompatibleModel(file)
        check(prefs.edit().putString(KEY_ACTIVE, name).commit()) {
            "Unable to save active AI model preference."
        }
    }

    fun delete(name: String) {
        require(name != activeModelName()) { "Active model cannot be deleted. Activate another model first." }
        val file = File(modelsDir, name)
        if (file.exists()) require(file.delete()) { "Unable to delete AI model." }
    }

    fun exportModel(name: String, destination: Uri) {
        val source = File(modelsDir, name)
        require(source.isFile) { "AI model not found." }
        appContext.contentResolver.openOutputStream(destination)?.use { output ->
            FileInputStream(source).use { input -> input.copyTo(output) }
        } ?: error("Unable to create backup file.")
    }

    private fun validateCompatibleModel(file: File) {
        require(file.length() > 0L) { "AI model file is empty." }

        val modelBuffer = FileInputStream(file).use { input ->
            input.channel.use { channel ->
                channel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, channel.size())
            }
        }
        val interpreter = try {
            Interpreter(modelBuffer, Interpreter.Options().setNumThreads(1))
        } catch (t: Throwable) {
            throw IllegalArgumentException(
                "Selected file is not a loadable TensorFlow Lite model (.tflite). Choose photo_sign_model.tflite, not a ZIP or dataset file.",
                t,
            )
        }

        try {
            require(interpreter.inputTensorCount == 1 && interpreter.outputTensorCount == 1) {
                "FormSnap currently supports models with exactly one input and one output tensor."
            }
            val input = interpreter.getInputTensor(0)
            val inputShape = input.shape()
            require(inputShape.size == 4) {
                "Unsupported input shape ${inputShape.contentToString()}; expected a 4D RGB image tensor."
            }
            val channelsLast = inputShape[3] == 3
            val channelsFirst = inputShape[1] == 3
            require(channelsLast || channelsFirst) {
                "Unsupported input shape ${inputShape.contentToString()}; expected RGB channels in NHWC or NCHW layout."
            }
            require(inputShape[0] == 1) {
                "Unsupported batch size ${inputShape[0]}; FormSnap runs one image at a time (batch size 1)."
            }
            require(inputShape.all { it > 0 }) { "Model input dimensions must all be fixed and positive." }
            require(input.dataType() in setOf(
                org.tensorflow.lite.DataType.FLOAT32,
                org.tensorflow.lite.DataType.UINT8,
                org.tensorflow.lite.DataType.INT8,
            )) {
                "Unsupported input data type ${input.dataType()}; FormSnap supports FLOAT32, UINT8, and INT8 image tensors."
            }
            if (input.dataType() == org.tensorflow.lite.DataType.UINT8 ||
                input.dataType() == org.tensorflow.lite.DataType.INT8
            ) {
                require(input.quantizationParams().scale > 0f) {
                    "Quantized model input has invalid quantization parameters."
                }
            }

            val outputTensor = interpreter.getOutputTensor(0)
            require(outputTensor.dataType() in setOf(
                org.tensorflow.lite.DataType.FLOAT32,
                org.tensorflow.lite.DataType.UINT8,
                org.tensorflow.lite.DataType.INT8,
            )) {
                "Unsupported output data type ${outputTensor.dataType()}; FormSnap supports FLOAT32, UINT8, and INT8 outputs."
            }
            if (outputTensor.dataType() == org.tensorflow.lite.DataType.UINT8 ||
                outputTensor.dataType() == org.tensorflow.lite.DataType.INT8
            ) {
                require(outputTensor.quantizationParams().scale > 0f) {
                    "Quantized model output has invalid quantization parameters."
                }
            }

            val outputShape = outputTensor.shape()
            val isRawYolo = outputShape.size == 3 && (
                (outputShape[1] in 5..512 && outputShape[2] > 100) ||
                    (outputShape[2] in 5..512 && outputShape[1] > 100)
            )
            val isNmsYolo = outputShape.size == 3 &&
                outputShape.last() in 6..7 &&
                outputShape[outputShape.lastIndex - 1] in 1..1000
            require(isRawYolo || isNmsYolo) {
                "Unsupported output shape ${outputShape.contentToString()}. Import a YOLO-style object detector " +
                    "with box coordinates and at least one class score. Classification-only models cannot locate objects."
            }

            // Run a real inference before activating the imported file. Shape checks alone
            // cannot catch models with unsupported operators or broken runtime tensors.
            val smokeInput = java.nio.ByteBuffer.allocateDirect(input.numBytes())
                .order(java.nio.ByteOrder.nativeOrder())
            val smokeOutput = java.nio.ByteBuffer.allocateDirect(outputTensor.numBytes())
                .order(java.nio.ByteOrder.nativeOrder())
            try {
                interpreter.run(smokeInput, smokeOutput)
            } catch (t: Throwable) {
                throw IllegalArgumentException(
                    "The model tensors look compatible, but TensorFlow Lite could not run inference. " +
                        "Re-export the PHOTO/SIGNATURE model for Android TFLite.",
                    t,
                )
            }
        } catch (t: Throwable) {
            if (t is IllegalArgumentException) throw t
            throw IllegalArgumentException("Unable to validate this AI model: ${t.message ?: "unknown error"}", t)
        } finally {
            interpreter.close()
        }
    }

    private fun uniqueFile(name: String): File {
        val base = name.removeSuffix(".tflite")
        var candidate = File(modelsDir, "$base.tflite")
        var index = 2
        while (candidate.exists()) {
            candidate = File(modelsDir, "$base-$index.tflite")
            index++
        }
        return candidate
    }

    companion object {
        private const val PREFS = "formsnap.ai.models"
        private const val KEY_ACTIVE = "active_model"
    }
}
