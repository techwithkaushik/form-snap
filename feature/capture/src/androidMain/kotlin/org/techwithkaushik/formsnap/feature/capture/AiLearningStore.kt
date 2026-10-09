package org.techwithkaushik.formsnap.feature.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.FileInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale
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

    private data class ImageGroup(val key: String, val file: File, val examples: List<Example>)

    @Synchronized fun examples(): List<Example> = readAll()
    @Synchronized fun count(): Int = readAll().size
    @Synchronized fun count(classId: Int): Int = readAll().count { it.classId == classId }

    /** Store the complete form image, since object detection requires its original context. */
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
        fun openSource(): InputStream = when (uri.scheme?.lowercase(Locale.US)) {
            "file" -> {
                val path = uri.path ?: error("The local image path is empty.")
                FileInputStream(File(path))
            }
            "content", "android.resource" ->
                app.contentResolver.openInputStream(uri)
                    ?: error("The selected image provider returned no readable stream.")
            else -> error("Unsupported image source scheme: ${uri.scheme ?: "missing"}. Re-import the image.")
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openSource().use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) {
            "Image source opened, but Android could not decode it (unsupported or damaged image)."
        }

        val maxSide = maxOf(bounds.outWidth, bounds.outHeight)
        val sample = Integer.highestOneBit((maxSide / MAX_IMAGE_SIDE).coerceAtLeast(1))
        val decoded = openSource().use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: error("Image source opened but decoding failed.")
        val bitmap = try {
            val decodedMax = maxOf(decoded.width, decoded.height)
            if (decodedMax > MAX_IMAGE_SIDE) {
                val scale = MAX_IMAGE_SIDE.toFloat() / decodedMax.toFloat()
                Bitmap.createScaledBitmap(
                    decoded,
                    (decoded.width * scale).toInt().coerceAtLeast(1),
                    (decoded.height * scale).toInt().coerceAtLeast(1),
                    true,
                ).also { if (it !== decoded) decoded.recycle() }
            } else decoded
        } catch (failure: Throwable) {
            if (!decoded.isRecycled) decoded.recycle()
            throw failure
        }

        val id = UUID.randomUUID().toString()
        val image = File(imageDir, "$id.jpg")
        try {
            FileOutputStream(image).use {
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it))
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

    /**
     * Exports one image and all of its boxes together. Identical full-form images are deduplicated
     * by content hash, preventing a photo box and signature box from leaking across dataset splits.
     */
    @Synchronized
    fun exportYoloZip(destination: File): File {
        val groups = readAll()
            .mapNotNull { example ->
                val file = File(imageDir, example.imageFile)
                if (file.isFile && file.length() > 0L) example to file else null
            }
            .groupBy { (_, file) -> sha256(file) }
            .map { (hash, pairs) -> ImageGroup(hash, pairs.first().second, pairs.map { it.first }) }
            .sortedBy { it.key }

        require(groups.isNotEmpty()) { "No reviewed examples to export yet." }
        val boxCounts = groups.flatMap { it.examples }.groupingBy { it.classId }.eachCount()
        require(boxCounts.getOrDefault(PHOTO, 0) >= MIN_BOXES_PER_CLASS_FOR_EXPORT &&
            boxCounts.getOrDefault(SIGNATURE, 0) >= MIN_BOXES_PER_CLASS_FOR_EXPORT) {
            "Add at least 10 PHOTO and 10 SIGNATURE boxes before exporting a dataset."
        }
        val splits = assignSplits(groups)
        val splitGroups = groups.groupBy { splits.getValue(it.key) }
        require(splitGroups["train"].orEmpty().isNotEmpty() &&
            splitGroups["val"].orEmpty().isNotEmpty() &&
            splitGroups["test"].orEmpty().isNotEmpty()) {
            "Not enough distinct form images for train/validation/test splits. Add more different forms."
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
            groups.forEach { group ->
                val split = splits.getValue(group.key)
                val imageName = "${group.key.take(24)}.jpg"
                group.file.inputStream().buffered().use { input ->
                    zip.putNextEntry(ZipEntry("images/$split/$imageName"))
                    input.copyTo(zip)
                    zip.closeEntry()
                }
                val labels = group.examples.joinToString(separator = "") { item ->
                    val w = item.right - item.left
                    val h = item.bottom - item.top
                    String.format(
                        Locale.US, "%d %.6f %.6f %.6f %.6f\n",
                        item.classId, item.left + w / 2f, item.top + h / 2f, w, h,
                    )
                }
                zipText(zip, "labels/$split/${group.key.take(24)}.txt", labels)
            }
            val splitSummary = listOf("train", "val", "test").joinToString("\n") { split ->
                "$split: ${splitGroups[split].orEmpty().size} unique form images"
            }
            zipText(zip, "README.txt",
                "FormSnap local reviewed dataset. Classes: 0=PHOTO, 1=SIGNATURE. " +
                    "Boxes from identical images stay together to avoid split leakage.\n$splitSummary\n" +
                    "Exporting this ZIP does not train a model.\n")
        }
        return destination
    }

    private fun assignSplits(groups: List<ImageGroup>): Map<String, String> {
        val targets = mapOf("train" to 0.70, "val" to 0.20, "test" to 0.10)
        val totalByClass = groups.flatMap { it.examples }.groupingBy { it.classId }.eachCount()
        val assignedBySplit = mutableMapOf<String, MutableMap<Int, Int>>()
        val groupCountBySplit = mutableMapOf<String, Int>()
        val result = mutableMapOf<String, String>()
        groups.sortedByDescending { it.examples.map { e -> e.classId }.distinct().size }
            .forEach { group ->
                val groupClasses = group.examples.groupingBy { it.classId }.eachCount()
                val chosen = targets.keys.maxBy { split ->
                    val counts = assignedBySplit.getOrPut(split) { mutableMapOf() }
                    groupClasses.entries.sumOf { (classId, count) ->
                        val total = totalByClass.getOrDefault(classId, 0).coerceAtLeast(1)
                        val desired = total * targets.getValue(split)
                        (desired - counts.getOrDefault(classId, 0)) * count.toDouble() / total
                    } - groupCountBySplit.getOrDefault(split, 0) * 0.0001
                }
                result[group.key] = chosen
                groupClasses.forEach { (classId, count) ->
                    val counts = assignedBySplit.getValue(chosen)
                    counts[classId] = counts.getOrDefault(classId, 0) + count
                }
                groupCountBySplit[chosen] = groupCountBySplit.getOrDefault(chosen, 0) + 1
            }
        return result
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte ->
            "%02x".format(Locale.US, byte.toInt() and 0xff)
        }
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
                }.getOrNull()?.takeIf { it.classId in PHOTO..SIGNATURE }?.let(::add)
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
        private const val MIN_BOXES_PER_CLASS_FOR_EXPORT = 10
    }
}
