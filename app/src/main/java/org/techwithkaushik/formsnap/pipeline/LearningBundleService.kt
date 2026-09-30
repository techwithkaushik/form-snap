package org.techwithkaushik.formSnap.pipeline

import android.content.Context
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Portable offline backup containing validated learning profiles only.
 * Source photos, signatures, and temporary processing images are never exported.
 */
object LearningBundleService {
    private const val FORMAT = "formsnap-learning"
    private const val SCHEMA = 1
    private const val MANIFEST = "manifest.json"
    private const val PAYLOAD = "learning.json"
    private const val LAYOUT_PAYLOAD = "layout-learning.json"
    private const val MAX_ENTRY_BYTES = 1_048_576
    private const val MAX_TOTAL_BYTES = 2_097_152
    private const val MAX_ENTRIES = 4

    fun export(context: Context, output: OutputStream) {
        val payload = LearningStore.exportJson(context).toByteArray(Charsets.UTF_8)
        val layoutPayload = LayoutTopologyStore.exportJson(context).toByteArray(Charsets.UTF_8)
        val correctionCount = JSONObject(String(payload, Charsets.UTF_8))
            .optJSONArray("profiles")?.length() ?: 0
        val layoutCount = JSONObject(String(layoutPayload, Charsets.UTF_8))
            .optJSONArray("profiles")?.length() ?: 0
        require(correctionCount + layoutCount > 0) {
            "No validated learning profiles are available yet. Adjust a detected crop and tap Accept, then try again."
        }
        require(payload.size <= MAX_ENTRY_BYTES && layoutPayload.size <= MAX_ENTRY_BYTES) {
            "Learning data is too large to export"
        }
        val manifest = JSONObject()
            .put("format", FORMAT)
            .put("schema", SCHEMA)
            .put("payload", PAYLOAD)
            .put("layoutPayload", LAYOUT_PAYLOAD)
            .toString()
            .toByteArray(Charsets.UTF_8)

        val zip = ZipOutputStream(output)
        zip.putNextEntry(ZipEntry(MANIFEST))
        zip.write(manifest)
        zip.closeEntry()
        zip.putNextEntry(ZipEntry(PAYLOAD))
        zip.write(payload)
        zip.closeEntry()
        zip.putNextEntry(ZipEntry(LAYOUT_PAYLOAD))
        zip.write(layoutPayload)
        zip.closeEntry()
        zip.finish()
        zip.flush()
    }

    fun import(context: Context, input: InputStream): LearningImportSummary {
        val zip = ZipInputStream(input)
        val entries = HashMap<String, ByteArray>()
        var totalBytes = 0
        var entryCount = 0

        while (true) {
            val entry = zip.nextEntry ?: break
            entryCount++
            require(entryCount <= MAX_ENTRIES) { "Learning bundle has too many entries" }
            require(!entry.isDirectory && entry.name in setOf(MANIFEST, PAYLOAD, LAYOUT_PAYLOAD)) {
                "Unexpected learning bundle entry"
            }
            require(!entries.containsKey(entry.name)) { "Duplicate learning bundle entry" }

            val bufferOut = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var entryBytes = 0
            while (true) {
                val count = zip.read(buffer)
                if (count < 0) break
                entryBytes += count
                totalBytes += count
                require(entryBytes <= MAX_ENTRY_BYTES && totalBytes <= MAX_TOTAL_BYTES) {
                    "Learning bundle exceeds the size limit"
                }
                bufferOut.write(buffer, 0, count)
            }
            entries[entry.name] = bufferOut.toByteArray()
            zip.closeEntry()
        }

        val manifestBytes = entries[MANIFEST]
            ?: throw IllegalArgumentException("Learning bundle manifest is missing")
        val payloadBytes = entries[PAYLOAD]
            ?: throw IllegalArgumentException("Learning bundle data is missing")
        val manifest = JSONObject(String(manifestBytes, Charsets.UTF_8))
        require(manifest.optString("format") == FORMAT) { "Not a FormSnap learning bundle" }
        require(manifest.optInt("schema", -1) == SCHEMA) { "Unsupported learning bundle version" }
        require(manifest.optString("payload") == PAYLOAD) { "Invalid learning bundle payload" }
        val layoutBytes = entries[LAYOUT_PAYLOAD]
        val layoutName = manifest.optString("layoutPayload", "")
        require(layoutName.isEmpty() || layoutName == LAYOUT_PAYLOAD) {
            "Invalid layout learning payload"
        }

        val correctionCount = JSONObject(String(payloadBytes, Charsets.UTF_8))
            .optJSONArray("profiles")?.length() ?: 0
        var summary = if (correctionCount > 0) {
            LearningStore.importJson(context, String(payloadBytes, Charsets.UTF_8))
        } else {
            LearningImportSummary(importedProfiles = 0, mergedProfiles = 0, rejectedProfiles = 0)
        }
        val layoutCount = layoutBytes?.let {
            LayoutTopologyStore.importJson(context, String(it, Charsets.UTF_8))
        } ?: 0
        require(summary.importedProfiles + layoutCount > 0) {
            "This backup contains no valid learning profiles"
        }
        if (layoutCount > 0) {
            summary = summary.copy(importedProfiles = summary.importedProfiles + layoutCount)
        }
        return summary
    }
}
