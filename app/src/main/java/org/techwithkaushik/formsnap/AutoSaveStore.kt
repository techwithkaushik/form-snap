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

    companion object {
        private const val PREFS = "formsnap_auto_save"
        private const val KEY_TREE_URI = "output_tree_uri"
    }
}
