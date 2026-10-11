package org.techwithkaushik.formSnap

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.IOException

class AutoSaveStore(private val context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun folderUri(): Uri? =
        prefs.getString(KEY_TREE_URI, null)?.let(Uri::parse)

    fun setFolder(uri: Uri) {
        val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
            android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, flags)
        }
        prefs.edit().putString(KEY_TREE_URI, uri.toString()).apply()
    }

    fun clearFolder() {
        prefs.edit().remove(KEY_TREE_URI).apply()
    }

    fun save(
        photo: ByteArray?,
        signature: ByteArray?,
    ): Pair<String?, String?> {
        val tree = folderUri() ?: throw IOException("Choose an output folder first.")
        val resolver = context.contentResolver
        val stamp = System.currentTimeMillis()

        fun write(bytes: ByteArray, name: String): String {
            val uri = DocumentsContract.createDocument(
                resolver,
                tree,
                "image/jpeg",
                name,
            ) ?: throw IOException("Unable to create $name")
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: throw IOException("Unable to open $name for writing")
            return name
        }

        val photoName = photo?.let { write(it, "Photo_"+stamp+".jpg") }
        val signatureName = signature?.let { write(it, "Sign_"+stamp+".jpg") }
        return photoName to signatureName
    }

    fun saveDetection(modelName: String, annotationName: String, bytes: ByteArray, extension: String, mimeType: String): String {
        val tree = folderUri() ?: throw IOException("Choose an output folder first.")
        val modelFolder = ensureDirectory(tree, safeSegment(modelName.removeSuffix(".tflite")))
        val classFolder = ensureDirectory(modelFolder, safeSegment(annotationName))
        val fileName = safeSegment(annotationName) + "_" + System.currentTimeMillis() + "." + extension.lowercase()
        val uri = DocumentsContract.createDocument(context.contentResolver, classFolder, mimeType, fileName)
            ?: throw IOException("Unable to create $fileName")
        context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            ?: throw IOException("Unable to write $fileName")
        return safeSegment(modelName.removeSuffix(".tflite")) + "/" + safeSegment(annotationName) + "/" + fileName
    }

    private fun ensureDirectory(parent: Uri, name: String): Uri {
        val resolver = context.contentResolver
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(parent, DocumentsContract.getDocumentId(parent))
        resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { cursor ->
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
        .replace(Regex("[\\\\/:*?\\"<>|\\u0000-\\u001F]"), "_")
        .replace(Regex("\\s+"), "_").trim('.', '_').take(80).ifBlank { "Unknown" }

    companion object {
        private const val PREFS = "formsnap_auto_save"
        private const val KEY_TREE_URI = "output_tree_uri"
    }
}
