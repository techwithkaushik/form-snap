package org.techwithkaushik.formsnap.feature.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.roundToInt

/** Stores explicitly reviewed PHOTO/SIGNATURE examples offline in app-private storage. */
class AiLearningStore(context: Context) {
    private val app = context.applicationContext
    private val root = File(app.filesDir, "ai-learning").apply { mkdirs() }
    private val imageDir = File(root, "images").apply { mkdirs() }
    private val index = File(root, "examples.json")

    data class Example(
        val id: String,
        val imageFile: String,
        val classId: Int,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val source: String,
        val createdAt: Long,
    )

    @Synchronized fun examples(): List<Example> = readAll()
    @Synchronized fun count(): Int = readAll().size

    /** Normalized coordinates are relative to the full source image and must be user-reviewed. */
    @Synchronized
    fun saveReviewedCrop(
        sourceUri: String,
        classId: Int,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        source: String,
    ): Example {
        require(classId in 0..1) { "Use PHOTO (0) or SIGNATURE (1)." }
        require(listOf(left, top, right, bottom).all { it.isFinite() })
        val l = left.coerceIn(0f, 1f)
        val t = top.coerceIn(0f, 1f)
        val r = right.coerceIn(0f, 1f)
        val b = bottom.coerceIn(0f, 1f)
        require(r - l >= 0.01f && b - t >= 0.01f) { "Selection is too small." }

        val uri = Uri.parse(sourceUri)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        app.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        } ?: error("Cannot open source image.")
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Unsupported source image." }

        val maxSide = maxOf(bounds.outWidth, bounds.outHeight)
        val sample = Integer.highestOneBit((maxSide / 1600).coerceAtLeast(1))
        val decoded = app.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: error("Cannot decode source image.")
        val rect = Rect(
            (l * decoded.width).roundToInt().coerceIn(0, decoded.width - 1),
            (t * decoded.height).roundToInt().coerceIn(0, decoded.height - 1),
            (r * decoded.width).roundToInt().coerceIn(1, decoded.width),
            (b * decoded.height).roundToInt().coerceIn(1, decoded.height),
        )
        if (rect.width() < 2 || rect.height() < 2) {
            decoded.recycle()
            error("Selection is too small.")
        }
        val crop = Bitmap.createBitmap(decoded, rect.left, rect.top, rect.width(), rect.height())
        decoded.recycle()

        val id = UUID.randomUUID().toString()
        val image = File(imageDir, "$id.jpg")
        try {
            FileOutputStream(image).use { check(crop.compress(Bitmap.CompressFormat.JPEG, 94, it)) }
        } catch (failure: Throwable) {
            image.delete()
            throw failure
        } finally {
            crop.recycle()
        }

        val example = Example(id, image.name, classId, l, t, r, b, source, System.currentTimeMillis())
        writeAll(readAll() + example)
        return example
    }

    @Synchronized
    fun delete(id: String): Boolean {
        val all = readAll()
        val item = all.firstOrNull { it.id == id } ?: return false
        File(imageDir, item.imageFile).delete()
        writeAll(all.filterNot { it.id == id })
        return true
    }

    private fun readAll(): List<Example> {
        if (!index.exists()) return emptyList()
        val json = runCatching { JSONArray(index.readText()) }.getOrElse { return emptyList() }
        return buildList {
            for (i in 0 until json.length()) {
                val o = json.optJSONObject(i) ?: continue
                runCatching {
                    Example(
                        o.getString("id"), o.getString("imageFile"), o.getInt("classId"),
                        o.getDouble("left").toFloat(), o.getDouble("top").toFloat(),
                        o.getDouble("right").toFloat(), o.getDouble("bottom").toFloat(),
                        o.getString("source"), o.getLong("createdAt"),
                    )
                }.getOrNull()?.let(::add)
            }
        }
    }

    private fun writeAll(items: List<Example>) {
        val json = JSONArray()
        items.forEach { e ->
            json.put(
                JSONObject().put("id", e.id).put("imageFile", e.imageFile)
                    .put("classId", e.classId).put("left", e.left.toDouble())
                    .put("top", e.top.toDouble()).put("right", e.right.toDouble())
                    .put("bottom", e.bottom.toDouble()).put("source", e.source)
                    .put("createdAt", e.createdAt),
            )
        }
        val temporary = File(root, "examples.json.tmp")
        temporary.writeText(json.toString())
        if (!temporary.renameTo(index)) {
            index.writeText(json.toString())
            temporary.delete()
        }
    }
}
