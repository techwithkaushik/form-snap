package org.techwithkaushik.formsnap

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

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
        val appFolder = ensureDirectory(uri, APP_FOLDER)
        ensureDirectory(appFolder, SAVED_FOLDER)
        check(prefs.edit().putString(KEY_TREE_URI, uri.toString()).remove(KEY_DIRECT_PATH).commit()) {
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
        val tree = prefs.getString(KEY_TREE_URI, null)?.let(Uri::parse)
            ?: throw IOException("Choose the SD card or shared storage folder first.")
        val appFolder = ensureDirectory(tree, APP_FOLDER)
        ensureDirectory(appFolder, SAVED_FOLDER)
    }

    fun saveDetection(modelName: String, annotationName: String, bytes: ByteArray, extension: String, mimeType: String): String {
        val directPath = prefs.getString(KEY_DIRECT_PATH, null)
        if (directPath != null) {
            val target = File(File(File(directPath, SAVED_FOLDER), safeSegment(modelName.removeSuffix(".tflite"))), safeSegment(annotationName))
            check(target.isDirectory || target.mkdirs()) { "Unable to create output folder " + target.path }
            val fileName = nextImageName(target, extension)
            val output = File(target, fileName)
            FileOutputStream(output).use { stream -> stream.write(bytes); stream.flush(); stream.fd.sync() }
            return "$APP_FOLDER/$SAVED_FOLDER/${safeSegment(modelName.removeSuffix(".tflite"))}/${safeSegment(annotationName)}/$fileName"
        }
        val tree = prefs.getString(KEY_TREE_URI, null)?.let(Uri::parse)
            ?: throw IOException("Choose an SD card or shared storage folder first.")
        val appFolder = ensureDirectory(tree, APP_FOLDER)
        val savedRoot = ensureDirectory(appFolder, SAVED_FOLDER)
        val modelFolder = ensureDirectory(savedRoot, safeSegment(modelName.removeSuffix(".tflite")))
        val annotationFolder = ensureDirectory(modelFolder, safeSegment(annotationName))
        val fileName = nextImageName(annotationFolder, extension)
        val uri = DocumentsContract.createDocument(context.contentResolver, annotationFolder, mimeType, fileName)
            ?: throw IOException("Unable to create $fileName")
        context.contentResolver.openOutputStream(uri, "w")?.use { output ->
            output.write(bytes)
            output.flush()
        } ?: throw IOException("Unable to write $fileName")
        return "$APP_FOLDER/$SAVED_FOLDER/${safeSegment(modelName.removeSuffix(".tflite"))}/${safeSegment(annotationName)}/$fileName"
    }

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
