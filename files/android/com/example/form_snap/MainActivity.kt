package com.example.form_snap

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.annotation.NonNull
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import org.opencv.android.OpenCVLoader
import java.util.concurrent.Executors

class FormSnapMainActivity : FlutterActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private val storagePrefs by lazy { getSharedPreferences("formsnap_storage", MODE_PRIVATE) }
    private var pendingDirectoryResult: MethodChannel.Result? = null
    private var pendingDirectoryType = "photo"

    companion object {
        private const val DIRECTORY_REQUEST = 9017
        private const val STORAGE_CHANNEL = "formsnap/storage"
        private const val OPENCV_CHANNEL = "formsnap/opencv"
        private const val PHOTO_DIRECTORY_URI = "photo_directory_uri"
        private const val SIGNATURE_DIRECTORY_URI = "signature_directory_uri"
    }

    override fun configureFlutterEngine(@NonNull flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, OPENCV_CHANNEL)
            .setMethodCallHandler { call, result ->
                if (call.method != "process") {
                    result.notImplemented()
                    return@setMethodCallHandler
                }
                val args = call.arguments as? Map<*, *>
                if (args == null) {
                    result.error("BAD_ARGS", "Missing processing arguments", null)
                    return@setMethodCallHandler
                }
                executor.execute {
                    try {
                        if (!OpenCVLoader.initLocal()) {
                            runOnUiThread {
                                result.error("OPENCV_INIT", "OpenCV initialization failed", null)
                            }
                            return@execute
                        }
                        val output = FormSnapOpenCvProcessor.process(applicationContext, args)
                        runOnUiThread { result.success(output) }
                    } catch (t: Throwable) {
                        runOnUiThread {
                            result.error(
                                "OPENCV_PROCESS",
                                t.message ?: "Native OpenCV processing failed",
                                null,
                            )
                        }
                    }
                }
            }

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, STORAGE_CHANNEL)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "hasDirectory" ->
                        result.success(hasPersistedDirectory(call.argument<String>("type")))
                    "chooseDirectory" ->
                        chooseDirectory(call.argument<String>("type") ?: "photo", result)
                    "clearDirectory" -> {
                        clearPersistedDirectory(call.argument<String>("type"))
                        result.success(true)
                    }
                    "saveFile" -> saveFile(call, result)
                    else -> result.notImplemented()
                }
            }
    }

    private fun prefKey(type: String?): String =
        if (type.equals("signature", ignoreCase = true)) {
            SIGNATURE_DIRECTORY_URI
        } else {
            PHOTO_DIRECTORY_URI
        }

    private fun hasPersistedDirectory(type: String?): Boolean {
        val uriString = storagePrefs.getString(prefKey(type), null) ?: return false
        return try {
            val uri = Uri.parse(uriString)
            contentResolver.persistedUriPermissions.any {
                it.uri == uri && it.isWritePermission
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun chooseDirectory(type: String, result: MethodChannel.Result) {
        if (pendingDirectoryResult != null) {
            result.error("BUSY", "A folder picker is already open", null)
            return
        }
        pendingDirectoryResult = result
        pendingDirectoryType = type

        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        }

        try {
            startActivityForResult(intent, DIRECTORY_REQUEST)
        } catch (t: Throwable) {
            pendingDirectoryResult = null
            result.error("FOLDER_PICKER", t.message ?: "Unable to open folder picker", null)
        }
    }

    private fun saveFile(call: MethodCall, result: MethodChannel.Result) {
        val type = call.argument<String>("type") ?: "photo"
        val uriString = storagePrefs.getString(prefKey(type), null)

        if (uriString == null || !hasPersistedDirectory(type)) {
            result.error(
                "NO_DIRECTORY",
                "Please choose the " + type + " output folder first",
                null,
            )
            return
        }

        val fileName = call.argument<String>("fileName")
        val bytes = call.argument<ByteArray>("bytes")

        if (fileName.isNullOrBlank() || bytes == null) {
            result.error("BAD_ARGS", "fileName and bytes are required", null)
            return
        }

        try {
            val treeUri = Uri.parse(uriString)
            if (!DocumentsContract.isTreeUri(treeUri)) {
                throw IllegalArgumentException("Selected folder permission is invalid. Please choose the folder again.")
            }

            // Some Downloads/Documents providers reject a tree URI directly
            // in createDocument(). Convert the persisted tree URI to its
            // corresponding document URI first.
            val treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
            val parentDocumentUri = DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                treeDocumentId,
            )

            val mime = when {
                fileName.endsWith(".jpg", ignoreCase = true) ||
                    fileName.endsWith(".jpeg", ignoreCase = true) -> "image/jpeg"
                fileName.endsWith(".png", ignoreCase = true) -> "image/png"
                else -> "application/octet-stream"
            }

            val fileUri = DocumentsContract.createDocument(
                contentResolver,
                parentDocumentUri,
                mime,
                fileName,
            ) ?: throw IllegalStateException("Android could not create " + fileName)

            contentResolver.openOutputStream(fileUri)?.use { output ->
                output.write(bytes)
                output.flush()
            } ?: throw IllegalStateException(
                "Android could not open the selected folder for writing",
            )

            result.success(true)
        } catch (t: Throwable) {
            result.error(
                "SAVE_FAILED",
                t.message ?: "Could not save " + fileName,
                null,
            )
        }
    }

    private fun clearPersistedDirectory(type: String?) {
        val key = prefKey(type)
        val uriString = storagePrefs.getString(key, null)
        if (uriString != null) {
            try {
                contentResolver.releasePersistableUriPermission(
                    Uri.parse(uriString),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            } catch (_: Throwable) {}
        }
        storagePrefs.edit().remove(key).apply()
    }

    @Deprecated("Use Activity Result APIs when the app's minimum Android setup is modernized.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != DIRECTORY_REQUEST) return

        val callback = pendingDirectoryResult
        val type = pendingDirectoryType
        pendingDirectoryResult = null

        if (resultCode != RESULT_OK || data?.data == null) {
            callback?.success(null)
            return
        }

        val uri = data.data!!
        try {
            val takeFlags = data.flags and (
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            if (takeFlags != 0) {
                contentResolver.takePersistableUriPermission(uri, takeFlags)
            }
            storagePrefs.edit().putString(prefKey(type), uri.toString()).apply()
            callback?.success(uri.toString())
        } catch (t: Throwable) {
            callback?.error(
                "FOLDER_PERMISSION",
                t.message ?: "Could not remember the selected folder",
                null,
            )
        }
    }

    override fun onDestroy() {
        pendingDirectoryResult?.success(null)
        pendingDirectoryResult = null
        executor.shutdownNow()
        super.onDestroy()
    }
}
