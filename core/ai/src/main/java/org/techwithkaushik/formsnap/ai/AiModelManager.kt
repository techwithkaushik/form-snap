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
        // Content-provider URIs commonly have opaque IDs as lastPathSegment. Prefer the
        // provider's real display name so a genuine .tflite file is not rejected by its URI ID.
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
        // Some Android 10 document providers expose only an opaque URI and omit DISPLAY_NAME.
        // In that case, use a neutral .tflite destination name and validate the file contents
        // with the TFLite interpreter below; do not reject a real model based on the URI ID.
        val originalName = when {
            discoveredName == null -> "formsnap_model_" + System.currentTimeMillis() + ".tflite"
            discoveredName.lowercase().endsWith(".tflite") -> discoveredName
            !discoveredName.contains('.') -> "$discoveredName.tflite"
            else -> throw IllegalArgumentException(
                "Selected file is '$discoveredName', not a .tflite model. Choose the actual .tflite model file, not a dataset ZIP."
            )
        }

        val safeName = originalName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val destination = uniqueFile(safeName)
        val temp = File(modelsDir, "." + destination.name + ".part")
        try {
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(temp).use { output -> input.copyTo(output); output.fd.sync() }
            } ?: error("Unable to open selected model.")
            require(temp.length() > 0L) { "Selected model is empty." }

            // Validate before publishing the file or changing the active-model preference.
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
        // Commit only after validation so a bad model cannot replace a working model.
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
        require(file.extension.equals("tflite", ignoreCase = true)) {
            "Only .tflite AI models are supported."
        }
        require(file.length() > 0L) { "AI model file is empty." }

        val modelBuffer = FileInputStream(file).use { input ->
            input.channel.use { channel ->
                channel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, channel.size())
            }
        }
        val interpreter = try {
            Interpreter(modelBuffer, Interpreter.Options().setNumThreads(1))
        } catch (t: Throwable) {
            throw IllegalArgumentException("This file is not a loadable TensorFlow Lite model.", t)
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
            // Only accept raw two-class YOLO output. NMS output shapes are ambiguous:
            // tensor shape alone cannot prove their class order is PHOTO(0), SIGNATURE(1).
            // Keep importer validation aligned with the decoder: raw YOLO has hundreds
            // of candidates, while small row-wise tensors may be NMS output and are ambiguous.
            val minRawCandidates = 256
            val isRawTwoClassOutput = outputShape.size == 3 &&
                ((outputShape[1] == 6 && outputShape[2] > minRawCandidates) ||
                    (outputShape[2] == 6 && outputShape[1] > minRawCandidates))
            require(isRawTwoClassOutput) {
                "Incompatible output shape ${outputShape.contentToString()}. Import a raw two-class YOLO model " +
                    "with 6 channels (4 box values + PHOTO(0) + SIGNATURE(1)) and more than 256 raw candidates. " +
                    "Generic COCO and ambiguous NMS-output models are not supported."
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
