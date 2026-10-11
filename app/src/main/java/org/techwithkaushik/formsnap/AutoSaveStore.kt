package org.techwithkaushik.formSnap

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.IOException

class AutoSaveStore(private val context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun folderUri(): Uri? = prefs.getString(KEY_TREE_URI, null)?.let(Uri::parse)

    fun setFolder(uri: Uri) {
        val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
        prefs.edit().putString(KEY_TREE_URI, uri.toString()).apply()
        ensureAppFolders()
    }

    fun clearFolder() { prefs.edit().remove(KEY_TREE_URI).apply() }

    /** Create the public app folder immediately after the user chooses a storage root. */
    fun ensureAppFolders() {
        val tree = folderUri() ?: throw IOException("Choose the SD card or shared storage folder first.")
        val appFolder = ensureDirectory(tree, APP_FOLDER)
        ensureDirectory(appFolder, SAVED_FOLDER)
    }

    fun saveDetection(modelName: String, annotationName: String, bytes: ByteArray, extension: String, mimeType: String): String {
        val tree = folderUri() ?: throw IOException("Choose an SD card or shared storage folder first.")
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
        private const val APP_FOLDER = "AI ObjectSnap"
        private const val SAVED_FOLDER = "savedImage"
    }
}
