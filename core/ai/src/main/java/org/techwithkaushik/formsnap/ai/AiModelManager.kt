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
)

class AiModelManager(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val modelsDir = File(appContext.filesDir, "ai-models").apply { mkdirs() }

    fun models(): List<AiModelInfo> =
        modelsDir.listFiles { file -> file.isFile && file.extension.equals("tflite", true) }
            ?.sortedBy { it.name.lowercase() }
            ?.map { AiModelInfo(it, it.name, it.length(), it.name == activeModelName()) }
            ?: emptyList()

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

            val outputShape = interpreter.getOutputTensor(0).shape()
            val minRawCandidates = 256
            val isRawTwoClassOutput = outputShape.size == 3 &&
                ((outputShape[1] == 6 && outputShape[2] > minRawCandidates) ||
                    (outputShape[2] == 6 && outputShape[1] > minRawCandidates))
            val isTwoClassClassifier = outputShape.contentEquals(intArrayOf(1, 2)) &&
                input.dataType() == org.tensorflow.lite.DataType.FLOAT32
            if (outputShape.size == 3 && outputShape.any { it == 84 }) {
                throw IllegalArgumentException(
                    "This is an 80-class YOLO model (output ${outputShape.contentToString()}), not a PHOTO/SIGNATURE model. " +
                        "Please import photo_sign_model.tflite with input [1,128,128,3] and output [1,2], " +
                        "or train/export a YOLO detector with exactly 2 classes: PHOTO and SIGNATURE."
                )
            }
            require(isRawTwoClassOutput || isTwoClassClassifier) {
                "Unsupported output shape ${outputShape.contentToString()}. Required: a float32 two-class classifier [1, 2] " +
                    "or a raw two-class YOLO detector with 6 channels. Do not use an 80-class COCO YOLO model. " +
                    "Classifier mode labels the whole frame only; it cannot locate or crop PHOTO/SIGNATURE."
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
