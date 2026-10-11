package org.techwithkaushik.formSnap

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.io.FileOutputStream
import java.io.IOException


data class SavedImageEntry(
    val modelName: String,
    val labelName: String,
    val fileName: String,
    val relativePath: String,
    val uri: String,
    val byteCount: Long,
    val width: Int = 0,
    val height: Int = 0,
)

class AutoSaveStore(private val context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun folderUri(): Uri? = prefs.getString(KEY_DIRECT_PATH, null)?.let { Uri.fromFile(File(it)) }
        ?: prefs.getString(KEY_TREE_URI, null)?.let(Uri::parse)

    fun isConfigured(): Boolean = folderUri() != null

    /** Configure Android 10 legacy shared storage after runtime permission is granted. */
    fun setDirectFolder(folder: File) {
        require(folder.isAbsolute) { "Output folder path must be absolute." }
        check(folder.exists() || folder.mkdirs()) { "Unable to create AI ObjectSnap folder." }
        check(folder.isDirectory && folder.canWrite()) { "AI ObjectSnap folder is not writable." }
        check(prefs.edit().putString(KEY_DIRECT_PATH, folder.absolutePath).remove(KEY_TREE_URI).commit()) {
            "Unable to remember the output folder."
        }
        ensureAppFolders()
    }

    fun setFolder(uri: Uri) {
        val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        context.contentResolver.takePersistableUriPermission(uri, flags)

        // Save Settings may point either at the storage root (e.g. SD card)
        // or directly at AI ObjectSnap. Normalize the persisted URI to the
        // app folder so choosing AI ObjectSnap never creates AI ObjectSnap/AI ObjectSnap.
        val selectedIsAppFolder = runCatching {
            val id = DocumentsContract.getTreeDocumentId(uri)
            id.substringAfterLast(':').substringAfterLast('/')
                .equals(APP_FOLDER, ignoreCase = true)
        }.getOrDefault(false)
        val appFolder = if (selectedIsAppFolder) uri else ensureDirectory(uri, APP_FOLDER)
        ensureDirectory(appFolder, SAVED_FOLDER)
        check(prefs.edit().putString(KEY_TREE_URI, appFolder.toString()).remove(KEY_DIRECT_PATH).commit()) {
            "Unable to remember the output folder."
        }
    }

    fun clearFolder() { prefs.edit().remove(KEY_TREE_URI).remove(KEY_DIRECT_PATH).apply() }

    /** Create the public app folder immediately after the user chooses a storage root. */
    fun ensureAppFolders() {
        val directPath = prefs.getString(KEY_DIRECT_PATH, null)
        if (directPath != null) {
            val appFolder = File(directPath)
            check(appFolder.isDirectory || appFolder.mkdirs()) { "Unable to create AI ObjectSnap folder." }
            val savedFolder = File(appFolder, SAVED_FOLDER)
            check(savedFolder.isDirectory || savedFolder.mkdirs()) { "Unable to create savedImage folder." }
            return
        }
        val appFolder = prefs.getString(KEY_TREE_URI, null)?.let(Uri::parse)
            ?: throw IOException("Open Save Settings and choose the SD card or shared-storage folder first.")
        ensureDirectory(appFolder, SAVED_FOLDER)
    }

    fun saveDetection(modelName: String, annotationName: String, bytes: ByteArray, extension: String, mimeType: String): String {
        val directPath = prefs.getString(KEY_DIRECT_PATH, null)
        if (directPath != null) {
            val target = File(File(File(directPath, SAVED_FOLDER), safeSegment(modelName.replace(Regex("(?i)\\.tflite$"), ""))), safeSegment(annotationName))
            check(target.isDirectory || target.mkdirs()) { "Unable to create output folder " + target.path }
            val fileName = nextImageName(target, extension)
            val output = File(target, fileName)
            FileOutputStream(output).use { stream -> stream.write(bytes); stream.flush(); stream.fd.sync() }
            return "$APP_FOLDER/$SAVED_FOLDER/${safeSegment(modelName.replace(Regex("(?i)\\.tflite$"), ""))}/${safeSegment(annotationName)}/$fileName"
        }
        val appFolder = prefs.getString(KEY_TREE_URI, null)?.let(Uri::parse)
            ?: throw IOException("Open Save Settings and choose the SD card or shared-storage folder first.")
        val savedRoot = ensureDirectory(appFolder, SAVED_FOLDER)
        val modelFolder = ensureDirectory(savedRoot, safeSegment(modelName.replace(Regex("(?i)\\.tflite$"), "")))
        val annotationFolder = ensureDirectory(modelFolder, safeSegment(annotationName))
        val fileName = nextImageName(annotationFolder, extension)
        val uri = DocumentsContract.createDocument(context.contentResolver, annotationFolder, mimeType, fileName)
            ?: throw IOException("Unable to create $fileName")
        context.contentResolver.openOutputStream(uri, "w")?.use { output ->
            output.write(bytes)
            output.flush()
        } ?: throw IOException("Unable to write $fileName")
        return "$APP_FOLDER/$SAVED_FOLDER/${safeSegment(modelName.replace(Regex("(?i)\\.tflite$"), ""))}/${safeSegment(annotationName)}/$fileName"
    }

    /** List saved crops grouped by their model and annotation folders. */
    fun listSavedImages(): List<SavedImageEntry> {
        val directPath = prefs.getString(KEY_DIRECT_PATH, null)
        if (directPath != null) {
            val root = File(directPath, SAVED_FOLDER)
            if (!root.isDirectory) return emptyList()
            return root.listFiles()?.filter { it.isDirectory }?.flatMap { model ->
                model.listFiles()?.filter { it.isDirectory }?.flatMap { label ->
                    label.listFiles()?.filter { it.isFile && it.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp") }
                        ?.map { file ->
                            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            android.graphics.BitmapFactory.decodeFile(file.absolutePath, bounds)
                            SavedImageEntry(model.name, label.name, file.name,
                                APP_FOLDER + "/" + SAVED_FOLDER + "/" + model.name + "/" + label.name + "/" + file.name,
                                Uri.fromFile(file).toString(), file.length(), bounds.outWidth, bounds.outHeight)
                        }.orEmpty()
                }.orEmpty()
            }.orEmpty().sortedByDescending { it.fileName }
        }
        val appFolder = prefs.getString(KEY_TREE_URI, null)?.let(Uri::parse) ?: return emptyList()
        val savedRoot = findDirectory(appFolder, SAVED_FOLDER) ?: return emptyList()
        return listDirectories(savedRoot).flatMap { modelPair ->
            listDirectories(modelPair.second).flatMap { labelPair ->
                listDocuments(labelPair.second).mapNotNull { doc ->
                    val name = doc.first
                    if (name.substringAfterLast('.', "").lowercase() !in setOf("jpg", "jpeg", "png", "webp")) return@mapNotNull null
                    val uri = doc.second
                    val bounds = runCatching {
                        val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        context.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, options) }
                        options
                    }.getOrNull()
                    val size = runCatching { context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: 0L }.getOrDefault(0L)
                    SavedImageEntry(modelPair.first, labelPair.first, name,
                        APP_FOLDER + "/" + SAVED_FOLDER + "/" + modelPair.first + "/" + labelPair.first + "/" + name,
                        uri.toString(), size, bounds?.outWidth ?: 0, bounds?.outHeight ?: 0)
                }
            }
        }.sortedByDescending { it.fileName }
    }

    fun deleteImage(entry: SavedImageEntry) {
        val uri = Uri.parse(entry.uri)
        if (uri.scheme == "file") {
            val file = File(uri.path ?: throw IOException("Invalid saved image path."))
            check(file.isFile && file.delete()) { "Unable to delete " + entry.fileName + "." }
        } else {
            check(DocumentsContract.deleteDocument(context.contentResolver, uri)) {
                "Unable to delete " + entry.fileName + "."
            }
        }
    }

    /** Perceptual hash prevents near-identical repeat captures within the same label folder. */
    fun isDuplicate(modelName: String, labelName: String, bitmap: android.graphics.Bitmap): Boolean {
        val currentHash = differenceHash(bitmap)
        val currentModel = safeSegment(modelName.replace(Regex("(?i)\\.tflite$"), ""))
        val currentLabel = safeSegment(labelName)
        val currentSharpness = sharpnessScore(bitmap)
        val duplicate = listSavedImages().asSequence()
            .filter { it.modelName == currentModel && it.labelName == currentLabel }
            .mapNotNull { entry ->
                runCatching {
                    val uri = Uri.parse(entry.uri)
                    val saved = if (uri.scheme == "file") {
                        android.graphics.BitmapFactory.decodeFile(uri.path)
                    } else {
                        context.contentResolver.openInputStream(uri)?.use {
                            android.graphics.BitmapFactory.decodeStream(it)
                        }
                    } ?: return@runCatching null
                    try {
                        if (java.lang.Long.bitCount(currentHash xor differenceHash(saved)) <= 5) entry to sharpnessScore(saved)
                        else null
                    } finally { saved.recycle() }
                }.getOrNull()
            }.firstOrNull()
        if (duplicate == null) return false

        // Keep only the better version: replace an existing near-duplicate when
        // the new crop is measurably sharper; otherwise leave the old file untouched.
        if (currentSharpness > duplicate.second * 1.08f) {
            runCatching { deleteImage(duplicate.first) }.getOrElse { return true }
            return false
        }
        return true
    }

    private fun differenceHash(bitmap: android.graphics.Bitmap): Long {
        val small = android.graphics.Bitmap.createScaledBitmap(bitmap, 9, 8, true)
        try {
            var hash = 0L
            var bit = 0
            for (y in 0 until 8) for (x in 0 until 8) {
                val left = small.getPixel(x, y)
                val right = small.getPixel(x + 1, y)
                val leftGray = (android.graphics.Color.red(left) * 299 + android.graphics.Color.green(left) * 587 + android.graphics.Color.blue(left) * 114) / 1000
                val rightGray = (android.graphics.Color.red(right) * 299 + android.graphics.Color.green(right) * 587 + android.graphics.Color.blue(right) * 114) / 1000
                if (leftGray > rightGray) hash = hash or (1L shl bit)
                bit++
            }
            return hash
        } finally { if (small !== bitmap) small.recycle() }
    }

    private fun sharpnessScore(bitmap: android.graphics.Bitmap): Float {
        val small = android.graphics.Bitmap.createScaledBitmap(bitmap, 64, 64, true)
        try {
            val gray = IntArray(64 * 64)
            for (y in 0 until 64) for (x in 0 until 64) {
                val pixel = small.getPixel(x, y)
                gray[y * 64 + x] = (android.graphics.Color.red(pixel) * 299 +
                    android.graphics.Color.green(pixel) * 587 + android.graphics.Color.blue(pixel) * 114) / 1000
            }
            var sum = 0.0
            var sumSquares = 0.0
            var count = 0
            for (y in 1 until 63) for (x in 1 until 63) {
                val i = y * 64 + x
                val laplacian = 4 * gray[i] - gray[i - 1] - gray[i + 1] - gray[i - 64] - gray[i + 64]
                sum += laplacian
                sumSquares += laplacian.toDouble() * laplacian
                count++
            }
            val mean = sum / count
            return ((sumSquares / count) - mean * mean).toFloat()
        } finally { if (small !== bitmap) small.recycle() }
    }

    private fun listDirectories(parent: Uri): List<Pair<String, Uri>> = listDocuments(parent, directoriesOnly = true)
    private fun listDocuments(parent: Uri, directoriesOnly: Boolean = false): List<Pair<String, Uri>> {
        val resolver = context.contentResolver
        val parentId = runCatching { DocumentsContract.getDocumentId(parent) }.getOrElse { DocumentsContract.getTreeDocumentId(parent) }
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(parent, parentId)
        val result = mutableListOf<Pair<String, Uri>>()
        resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (cursor.moveToNext()) {
                val isDir = cursor.getString(mimeCol) == DocumentsContract.Document.MIME_TYPE_DIR
                if (!directoriesOnly || isDir) {
                    result += cursor.getString(nameCol) to DocumentsContract.buildDocumentUriUsingTree(parent, cursor.getString(idCol))
                }
            }
        }
        return result
    }

    private fun findDirectory(parent: Uri, name: String): Uri? =
        listDirectories(parent).firstOrNull { it.first == name }?.second

    private fun nextImageName(parent: File, extension: String): String {
        val max = parent.listFiles()?.mapNotNull { file ->
            Regex("^image(\\d+)\\.[^.]+$", RegexOption.IGNORE_CASE).matchEntire(file.name)?.groupValues?.getOrNull(1)?.toIntOrNull()
        }?.maxOrNull() ?: 0
        return "image${max + 1}.${extension.lowercase()}"
    }

    private fun nextImageName(parent: Uri, extension: String): String {
        val resolver = context.contentResolver
        val parentId = runCatching { DocumentsContract.getDocumentId(parent) }.getOrElse { DocumentsContract.getTreeDocumentId(parent) }
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(parent, parentId)
        var max = 0
        resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
            val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameCol) ?: continue
                Regex("^image(\\d+)\\.[^.]+$", RegexOption.IGNORE_CASE).matchEntire(name)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { max = maxOf(max, it) }
            }
        }
        return "image${max + 1}.${extension.lowercase()}"
    }

    private fun ensureDirectory(parent: Uri, name: String): Uri {
        val resolver = context.contentResolver
        val parentId = runCatching { DocumentsContract.getDocumentId(parent) }.getOrElse { DocumentsContract.getTreeDocumentId(parent) }
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(parent, parentId)
        resolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE),
            null, null, null,
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (cursor.moveToNext()) {
                if (cursor.getString(nameCol) == name && cursor.getString(mimeCol) == DocumentsContract.Document.MIME_TYPE_DIR) {
                    return DocumentsContract.buildDocumentUriUsingTree(parent, cursor.getString(idCol))
                }
            }
        }
        return DocumentsContract.createDocument(resolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, name)
            ?: throw IOException("Unable to create output folder $name")
    }

    private fun safeSegment(value: String): String = value.trim()
        .map { if (it.isLetterOrDigit() || it == '-' || it == '_' || it == '.') it else '_' }
        .joinToString("").trim('.', '_').take(80).ifBlank { "Unknown" }

    companion object {
        private const val PREFS = "formsnap_auto_save"
        private const val KEY_TREE_URI = "output_tree_uri"
        private const val KEY_DIRECT_PATH = "output_direct_path"
        private const val APP_FOLDER = "AI ObjectSnap"
        private const val SAVED_FOLDER = "savedImage"
    }
}
