package org.techwithkaushik.formsnap.feature.capture

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Stores reviewed full-form images and normalized boxes locally for offline detector training. */
class AiLearningStore(context: Context) {
    private val app = context.applicationContext
    private val root = File(app.filesDir, "ai-learning").apply { mkdirs() }
    private val imageDir = File(root, "images").apply { mkdirs() }
    private val index = File(root, "examples.json")

    data class Example(
        val id: String, val imageFile: String, val classId: Int,
        val left: Float, val top: Float, val right: Float, val bottom: Float,
        val source: String, val createdAt: Long,
    ) {
        val className: String get() = if (classId == PHOTO) "PHOTO" else "SIGNATURE"
    }

    @Synchronized fun examples(): List<Example> = readAll()
    @Synchronized fun count(): Int = readAll().size
    @Synchronized fun count(classId: Int): Int = readAll().count { it.classId == classId }

    /**
     * Keeps the full source image, not just its crop: the detector must learn the object's
     * location in a complete form. Coordinates are normalized to the source image.
     */
    @Synchronized
    fun saveReviewedCrop(
        sourceUri: String, classId: Int,
        left: Float, top: Float, right: Float, bottom: Float, source: String,
    ): Example {
        require(classId in PHOTO..SIGNATURE) { "Use PHOTO (0) or SIGNATURE (1)." }
        require(listOf(left, top, right, bottom).all { it.isFinite() })
        val l = left.coerceIn(0f, 1f); val t = top.coerceIn(0f, 1f)
        val r = right.coerceIn(0f, 1f); val b = bottom.coerceIn(0f, 1f)
        require(r - l >= 0.01f && b - t >= 0.01f) { "Selection is too small." }

        val uri = Uri.parse(sourceUri)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        app.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            ?: error("Cannot open source image.")
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Unsupported source image." }
        val maxSide = maxOf(bounds.outWidth, bounds.outHeight)
        val sample = Integer.highestOneBit((maxSide / MAX_IMAGE_SIDE).coerceAtLeast(1))
        val bitmap = app.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: error("Cannot decode source image.")

        val id = UUID.randomUUID().toString()
        val image = File(imageDir, "\${id}.jpg")
        try {
            FileOutputStream(image).use {
                check(bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it))
            }
        } catch (failure: Throwable) {
            image.delete()
            throw failure
        } finally {
            bitmap.recycle()
        }

        val example = Example(id, image.name, classId, l, t, r, b, source, System.currentTimeMillis())
        try { writeAll(readAll() + example) } catch (failure: Throwable) {
            image.delete()
            throw failure
        }
        return example
    }

    /** Explicitly exports a standard YOLO dataset ZIP; it is never uploaded automatically. */
    @Synchronized
    fun exportYoloZip(destination: File): File {
        val items = readAll().filter { File(imageDir, it.imageFile).isFile }
        require(items.isNotEmpty()) { "No reviewed examples to export yet." }
        require(items.any { it.classId == PHOTO } && items.any { it.classId == SIGNATURE }) {
            "Collect at least one PHOTO and one SIGNATURE example before exporting."
        }
        destination.parentFile?.mkdirs()
        ZipOutputStream(destination.outputStream().buffered()).use { zip ->
            zipText(zip, "data.yaml", """
                path: .
                train: images/train
                val: images/val
                test: images/test
                names:
                  0: PHOTO
                  1: SIGNATURE
            """.trimIndent() + "\n")
            items.forEach { item ->
                val split = when (Math.floorMod(item.id.hashCode(), 10)) {
                    0 -> "test"
                    1, 2 -> "val"
                    else -> "train"
                }
                val imagePath = "images/\${split}/\${item.id}.jpg"
                val labelPath = "labels/\${split}/\${item.id}.txt"
                File(imageDir, item.imageFile).inputStream().buffered().use { input ->
                    zip.putNextEntry(ZipEntry(imagePath)); input.copyTo(zip); zip.closeEntry()
                }
                val w = item.right - item.left; val h = item.bottom - item.top
                val label = "%d %.6f %.6f %.6f %.6f\n".format(
                    item.classId, item.left + w / 2f, item.top + h / 2f, w, h,
                )
                zipText(zip, labelPath, label)
            }
            zipText(zip, "README.txt",
                "FormSnap offline reviewed dataset. Classes: 0=PHOTO, 1=SIGNATURE. " +
                    "Each image contains one reviewed box. Check class balance and split sizes before training.\n")
        }
        return destination
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
                    Example(o.getString("id"), o.getString("imageFile"), o.getInt("classId"),
                        o.getDouble("left").toFloat(), o.getDouble("top").toFloat(),
                        o.getDouble("right").toFloat(), o.getDouble("bottom").toFloat(),
                        o.getString("source"), o.getLong("createdAt"))
                }.getOrNull()?.let(::add)
            }
        }
    }

    private fun writeAll(items: List<Example>) {
        val json = JSONArray()
        items.forEach { e ->
            json.put(JSONObject().put("id", e.id).put("imageFile", e.imageFile)
                .put("classId", e.classId).put("left", e.left.toDouble()).put("top", e.top.toDouble())
                .put("right", e.right.toDouble()).put("bottom", e.bottom.toDouble())
                .put("source", e.source).put("createdAt", e.createdAt))
        }
        val temporary = File(root, "examples.json.tmp")
        temporary.writeText(json.toString())
        if (!temporary.renameTo(index)) { index.writeText(json.toString()); temporary.delete() }
    }

    private fun zipText(zip: ZipOutputStream, path: String, content: String) {
        zip.putNextEntry(ZipEntry(path)); zip.write(content.toByteArray(Charsets.UTF_8)); zip.closeEntry()
    }

    companion object {
        const val PHOTO = 0
        const val SIGNATURE = 1
        private const val MAX_IMAGE_SIDE = 1600
        private const val JPEG_QUALITY = 92
    }
}
