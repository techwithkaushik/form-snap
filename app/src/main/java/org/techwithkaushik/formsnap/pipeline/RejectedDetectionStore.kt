package org.techwithkaushik.formSnap.pipeline

import android.content.Context
import android.graphics.RectF
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Persists user-rejected candidates for the same source file identity.
 *
 * Coordinates are normalized so a source file can be decoded at a different
 * working resolution without moving the rejection region. The file identity
 * includes canonical path, byte length and last-modified time; replacing the
 * source at the same path therefore does not silently reuse stale feedback.
 *
 * This is conservative rejection memory, not a trained detector or a global
 * layout model. Only explicit user rejection is recorded.
 */
internal object RejectedDetectionStore {
    private const val PREFS = "formsnap_rejected_detection_v1"
    private const val MAX_REJECTIONS_PER_KIND = 10

    fun record(
        context: Context,
        source: File,
        kind: DetectionKind,
        bounds: RectF,
        sourceWidth: Int,
        sourceHeight: Int,
    ) {
        if (!source.isFile || sourceWidth <= 0 || sourceHeight <= 0 || !valid(bounds)) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = keyFor(source)
        val root = runCatching { JSONObject(prefs.getString(key, "{}") ?: "{}") }
            .getOrElse { JSONObject() }
        val records = runCatching { root.optJSONArray(kind.name) ?: JSONArray() }
            .getOrElse { JSONArray() }
        val normalized = JSONObject()
            .put("l", (bounds.left / sourceWidth).coerceIn(0f, 1f).toDouble())
            .put("t", (bounds.top / sourceHeight).coerceIn(0f, 1f).toDouble())
            .put("r", (bounds.right / sourceWidth).coerceIn(0f, 1f).toDouble())
            .put("b", (bounds.bottom / sourceHeight).coerceIn(0f, 1f).toDouble())

        // De-duplicate near-identical rejection regions, then keep the newest
        // bounded set so long-running sessions cannot grow persistent storage.
        val retained = JSONArray()
        for (index in 0 until records.length()) {
            val old = records.optJSONObject(index) ?: continue
            if (overlapRatio(old, normalized) < 0.80) retained.put(old)
        }
        retained.put(normalized)
        val bounded = JSONArray()
        val first = (retained.length() - MAX_REJECTIONS_PER_KIND).coerceAtLeast(0)
        for (index in first until retained.length()) bounded.put(retained.getJSONObject(index))
        root.put(kind.name, bounded)
        prefs.edit().putString(key, root.toString()).apply()
    }

    fun clear(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }

    fun load(
        context: Context,
        source: File,
        kind: DetectionKind,
        sourceWidth: Int,
        sourceHeight: Int,
    ): Set<RectF> {
        if (!source.isFile || sourceWidth <= 0 || sourceHeight <= 0) return emptySet()
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val root = runCatching {
            JSONObject(prefs.getString(keyFor(source), "{}") ?: "{}")
        }.getOrElse { return emptySet() }
        val records = root.optJSONArray(kind.name) ?: return emptySet()
        return buildSet {
            for (index in 0 until records.length()) {
                val item = records.optJSONObject(index) ?: continue
                val left = item.optDouble("l", Double.NaN)
                val top = item.optDouble("t", Double.NaN)
                val right = item.optDouble("r", Double.NaN)
                val bottom = item.optDouble("b", Double.NaN)
                if (!left.isFinite() || !top.isFinite() || !right.isFinite() || !bottom.isFinite()) continue
                val bounds = RectF(
                    (left * sourceWidth).toFloat(),
                    (top * sourceHeight).toFloat(),
                    (right * sourceWidth).toFloat(),
                    (bottom * sourceHeight).toFloat(),
                )
                if (valid(bounds)) add(bounds)
            }
        }
    }

    private fun keyFor(source: File): String {
        val identity = runCatching { source.canonicalPath }.getOrDefault(source.absolutePath) +
            "|" + source.length() + "|" + source.lastModified()
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "source_$digest"
    }

    private fun valid(bounds: RectF): Boolean =
        bounds.left.isFinite() && bounds.top.isFinite() &&
            bounds.right.isFinite() && bounds.bottom.isFinite() &&
            bounds.left >= 0f && bounds.top >= 0f &&
            bounds.right > bounds.left && bounds.bottom > bounds.top

    private fun overlapRatio(first: JSONObject, second: JSONObject): Double {
        val left = maxOf(first.optDouble("l"), second.optDouble("l"))
        val top = maxOf(first.optDouble("t"), second.optDouble("t"))
        val right = minOf(first.optDouble("r"), second.optDouble("r"))
        val bottom = minOf(first.optDouble("b"), second.optDouble("b"))
        if (right <= left || bottom <= top) return 0.0
        val intersection = (right - left) * (bottom - top)
        val firstArea = (first.optDouble("r") - first.optDouble("l")) *
            (first.optDouble("b") - first.optDouble("t"))
        val secondArea = (second.optDouble("r") - second.optDouble("l")) *
            (second.optDouble("b") - second.optDouble("t"))
        return intersection / minOf(firstArea, secondArea).coerceAtLeast(1e-12)
    }
}
