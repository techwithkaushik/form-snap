package org.techwithkaushik.formSnap

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.techwithkaushik.formsnap.ai.AiModelManager
import org.techwithkaushik.formsnap.ai.DetectionConfig
import org.techwithkaushik.formsnap.ai.YoloV8TfliteDetector
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlin.math.roundToInt

enum class OutputImageType(val extension: String, val mimeType: String, val format: Bitmap.CompressFormat) {
    JPEG("jpg", "image/jpeg", Bitmap.CompressFormat.JPEG),
    PNG("png", "image/png", Bitmap.CompressFormat.PNG),
    WEBP("webp", "image/webp", Bitmap.CompressFormat.WEBP)
}

data class UniversalSavedDetection(val className: String, val classId: Int, val confidence: Float, val relativePath: String, val byteCount: Int)

object UniversalAutoSaveService {
    fun processAndSave(context: Context, input: File, outputWidthMm: Double, outputHeightMm: Double, dpi: Int, maxKb: Int, imageType: OutputImageType): List<UniversalSavedDetection> {
        require(input.isFile && input.length() > 0L) { "Input image is missing or empty." }
        require(outputWidthMm > 0.0 && outputHeightMm > 0.0 && outputWidthMm.isFinite() && outputHeightMm.isFinite()) { "Output width and height must be positive." }
        require(dpi in 72..1200) { "DPI must be between 72 and 1200." }
        require(maxKb in 5..2048) { "Maximum output size must be between 5 and 2048 KB." }
        val manager = AiModelManager(context)
        val model = manager.activeModelFile() ?: throw IOException("Import and activate a TFLite object-detection model first.")
        val source = BitmapFactory.decodeFile(input.absolutePath) ?: throw IOException("Unable to decode input image.")
        if (source.width.toLong() * source.height > MAX_SOURCE_PIXELS) {
            source.recycle()
            throw IOException("Image is too large to process safely.")
        }
        val threshold = context.getSharedPreferences("formsnap.live.detection", Context.MODE_PRIVATE).getFloat("confidence_threshold", 0.35f).coerceIn(0.05f, 0.95f)
        val widthPx = (outputWidthMm * dpi / 25.4).roundToInt().coerceAtLeast(1)
        val heightPx = (outputHeightMm * dpi / 25.4).roundToInt().coerceAtLeast(1)
        require(widthPx.toLong() * heightPx <= MAX_OUTPUT_PIXELS) { "Requested output dimensions are too large." }
        try {
            val detections = YoloV8TfliteDetector(
                context, modelFile = model,
                config = DetectionConfig(confidenceThreshold = threshold, iouThreshold = 0.45f, maxDetections = 50, classLabels = manager.classLabels(model)),
            ).use { it.detect(source) }
            if (detections.isEmpty()) throw IOException("No objects passed the ${(threshold * 100).roundToInt()}% confidence threshold. Check the model, class labels or lighting.")
            val store = AutoSaveStore(context)
            return detections.map { detection ->
                val box = detection.boundingBox
                val left = box.left.roundToInt().coerceIn(0, source.width - 1)
                val top = box.top.roundToInt().coerceIn(0, source.height - 1)
                val right = box.right.roundToInt().coerceIn(left + 1, source.width)
                val bottom = box.bottom.roundToInt().coerceIn(top + 1, source.height)
                val crop = Bitmap.createBitmap(source, left, top, right - left, bottom - top)
                try {
                    val resized = Bitmap.createScaledBitmap(crop, widthPx, heightPx, true)
                    try {
                        val bytes = UniversalImageEncoder.encode(resized, maxKb, imageType)
                        val path = store.saveDetection(model.name, detection.label, bytes, imageType.extension, imageType.mimeType)
                        UniversalSavedDetection(detection.label, detection.classId, detection.confidence, path, bytes.size)
                    } finally { if (resized !== crop) resized.recycle() }
                } finally { crop.recycle() }
            }
        } finally { source.recycle() }
    }
    private const val MAX_SOURCE_PIXELS = 24_000_000L
    private const val MAX_OUTPUT_PIXELS = 12_000_000L
}

private object UniversalImageEncoder {
    fun encode(bitmap: Bitmap, maxKb: Int, type: OutputImageType): ByteArray {
        val limit = maxKb * 1024
        var current = bitmap
        var owned: Bitmap? = null
        try {
            for (step in 0..14) {
                val qualities = if (type == OutputImageType.JPEG || type == OutputImageType.WEBP) (95 downTo 25 step 5) else listOf(100)
                for (quality in qualities) {
                    val stream = ByteArrayOutputStream()
                    if (!current.compress(type.format, quality, stream)) throw IOException("Image encoder failed for ${type.extension}.")
                    val bytes = stream.toByteArray()
                    if (bytes.size <= limit) return bytes
                }
                if (step == 14) break
                val w = (current.width * 0.85f).roundToInt().coerceAtLeast(1)
                val h = (current.height * 0.85f).roundToInt().coerceAtLeast(1)
                if (w < 64 || h < 64 || (w == current.width && h == current.height)) break
                val scaled = Bitmap.createScaledBitmap(current, w, h, true)
                owned?.recycle()
                owned = scaled
                current = scaled
            }
            throw IOException("Could not fit output within ${maxKb} KB. Increase Max KB or reduce output dimensions.")
        } finally { owned?.recycle() }
    }
}
