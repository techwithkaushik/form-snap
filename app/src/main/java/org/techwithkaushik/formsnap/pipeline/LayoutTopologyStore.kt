package org.techwithkaushik.formSnap.pipeline

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max

/** Small, versioned, pixel-free store for user-confirmed layout correction profiles. */
object LayoutTopologyStore {
    private const val PREFS = "formsnap_layout_topology_v1"
    private const val KEY_PROFILES = "profiles"
    private const val MAX_PROFILES = 64

    @Synchronized
    fun best(
        context: Context,
        signature: LayoutTopologySignature,
        kind: DetectionKind,
    ): LayoutCorrectionProfile? {
        if (!signature.isValid()) return null
        return read(context)
            .asSequence()
            .filter { it.kind == kind && it.signature.isValid() && valid(it) }
            .map { profile ->
                profile to LayoutTopologyMatcher.similarity(profile.signature, signature, kind)
            }
            .filter { (_, score) -> score >= LayoutTopologyMatcher.MIN_MATCH_CONFIDENCE }
            .maxByOrNull { (_, score) -> score }
            ?.first
    }

    @Synchronized
    fun record(context: Context, profile: LayoutCorrectionProfile) {
        if (!valid(profile)) return
        val profiles = read(context).filter(::valid).toMutableList()
        val existingIndex = profiles.indexOfFirst {
            it.kind == profile.kind &&
                LayoutTopologyMatcher.similarity(it.signature, profile.signature, profile.kind) >=
                LayoutTopologyMatcher.MIN_MATCH_CONFIDENCE
        }
        if (existingIndex >= 0) {
            val previous = profiles[existingIndex]
            val oldWeight = previous.sampleCount.coerceAtLeast(1).toFloat()
            val newWeight = profile.sampleCount.coerceAtLeast(1).toFloat()
            val total = oldWeight + newWeight
            fun blend(old: Float, new: Float) = (old * oldWeight + new * newWeight) / total
            profiles[existingIndex] = previous.copy(
                deltas = NormalizedCropDeltas(
                    blend(previous.deltas.left, profile.deltas.left),
                    blend(previous.deltas.top, profile.deltas.top),
                    blend(previous.deltas.right, profile.deltas.right),
                    blend(previous.deltas.bottom, profile.deltas.bottom),
                ),
                sampleCount = (previous.sampleCount + profile.sampleCount).coerceAtMost(100),
                confidence = blend(previous.confidence, profile.confidence).coerceIn(0f, 1f),
            )
        } else {
            profiles += profile
        }
        val bounded = profiles
            .sortedWith(compareByDescending<LayoutCorrectionProfile> { it.sampleCount }
                .thenByDescending { it.confidence })
            .take(MAX_PROFILES)
        val array = JSONArray()
        bounded.forEach { array.put(encode(it)) }
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PROFILES, JSONObject().put("schema", 1).put(KEY_PROFILES, array).toString())
            .apply()
    }

    /** Portable, pixel-free backup of validated layout/topology corrections. */
    @Synchronized
    fun exportJson(context: Context): String {
        val array = JSONArray()
        read(context).filter(::valid).take(MAX_PROFILES).forEach { array.put(encode(it)) }
        return JSONObject().put("schema", 1).put(KEY_PROFILES, array).toString()
    }

    /** Validate and merge topology profiles from a portable learning backup. */
    @Synchronized
    fun importJson(context: Context, payload: String): Int {
        require(payload.toByteArray(Charsets.UTF_8).size <= 1_048_576) {
            "Layout learning payload is too large"
        }
        val root = JSONObject(payload)
        require(root.optInt("schema", -1) == 1) { "Unsupported layout learning schema" }
        val array = root.optJSONArray(KEY_PROFILES)
            ?: throw IllegalArgumentException("Layout learning profiles are missing")
        require(array.length() <= MAX_PROFILES) { "Too many layout learning profiles" }

        val imported = buildList {
            for (i in 0 until array.length()) {
                val profile = runCatching { decode(array.optJSONObject(i)) }.getOrNull()
                if (profile != null && valid(profile)) add(profile)
            }
        }
        if (imported.isEmpty()) return 0

        val profiles = read(context).filter(::valid).toMutableList()
        for (incoming in imported) {
            val index = profiles.indexOfFirst {
                it.kind == incoming.kind &&
                    LayoutTopologyMatcher.similarity(it.signature, incoming.signature, incoming.kind) >=
                    LayoutTopologyMatcher.MIN_MATCH_CONFIDENCE
            }
            if (index < 0) {
                profiles += incoming
            } else {
                val previous = profiles[index]
                val oldWeight = previous.sampleCount.coerceAtLeast(1).toFloat()
                val newWeight = incoming.sampleCount.coerceAtLeast(1).toFloat()
                val total = oldWeight + newWeight
                fun blend(old: Float, new: Float) = (old * oldWeight + new * newWeight) / total
                profiles[index] = previous.copy(
                    deltas = NormalizedCropDeltas(
                        blend(previous.deltas.left, incoming.deltas.left),
                        blend(previous.deltas.top, incoming.deltas.top),
                        blend(previous.deltas.right, incoming.deltas.right),
                        blend(previous.deltas.bottom, incoming.deltas.bottom),
                    ),
                    sampleCount = (previous.sampleCount + incoming.sampleCount).coerceAtMost(100),
                    confidence = blend(previous.confidence, incoming.confidence).coerceIn(0f, 1f),
                )
            }
        }
        val bounded = profiles.sortedWith(
            compareByDescending<LayoutCorrectionProfile> { it.sampleCount }
                .thenByDescending { it.confidence },
        ).take(MAX_PROFILES)
        val output = JSONArray()
        bounded.forEach { output.put(encode(it)) }
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PROFILES, JSONObject().put("schema", 1).put(KEY_PROFILES, output).toString())
            .apply()
        return imported.size
    }

    @Synchronized
    fun clear(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY_PROFILES).apply()
    }

    private fun read(context: Context): List<LayoutCorrectionProfile> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val root = runCatching { JSONObject(prefs.getString(KEY_PROFILES, "{}") ?: "{}") }
            .getOrElse { return emptyList() }
        if (root.optInt("schema", 0) != 1) return emptyList()
        val array = root.optJSONArray(KEY_PROFILES) ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val profile = runCatching { decode(array.optJSONObject(i)) }.getOrNull() ?: continue
                if (valid(profile)) add(profile)
                if (size >= MAX_PROFILES) break
            }
        }
    }

    private fun valid(profile: LayoutCorrectionProfile): Boolean =
        profile.signature.isValid() &&
            profile.sampleCount in 1..100 &&
            profile.confidence.isFinite() && profile.confidence in 0f..1f &&
            listOf(
                profile.deltas.left, profile.deltas.top,
                profile.deltas.right, profile.deltas.bottom,
            ).all { it.isFinite() && kotlin.math.abs(it) <= 0.35f }

    private fun encode(profile: LayoutCorrectionProfile): JSONObject = JSONObject()
        .put("kind", profile.kind.name)
        .put("aspect", profile.signature.imageAspectRatio)
        .put("photo", encodeBox(profile.signature.photo))
        .put("signature", encodeBox(profile.signature.signature))
        .put("dl", profile.deltas.left)
        .put("dt", profile.deltas.top)
        .put("dr", profile.deltas.right)
        .put("db", profile.deltas.bottom)
        .put("n", profile.sampleCount)
        .put("cf", profile.confidence)

    private fun decode(obj: JSONObject?): LayoutCorrectionProfile {
        requireNotNull(obj) { "Missing layout profile" }
        val kind = DetectionKind.valueOf(obj.getString("kind"))
        val signature = LayoutTopologySignature(
            imageAspectRatio = obj.getDouble("aspect").toFloat(),
            photo = decodeBox(obj.optJSONObject("photo")),
            signature = decodeBox(obj.optJSONObject("signature")),
        )
        val deltas = NormalizedCropDeltas(
            left = obj.getDouble("dl").toFloat(),
            top = obj.getDouble("dt").toFloat(),
            right = obj.getDouble("dr").toFloat(),
            bottom = obj.getDouble("db").toFloat(),
        )
        return LayoutCorrectionProfile(
            kind = kind,
            signature = signature,
            deltas = deltas,
            sampleCount = max(1, obj.optInt("n", 1)),
            confidence = obj.optDouble("cf", 1.0).toFloat(),
        )
    }

    private fun encodeBox(box: NormalizedLayoutBox?): Any =
        box?.let {
            JSONObject().put("l", it.left).put("t", it.top).put("r", it.right).put("b", it.bottom)
        } ?: JSONObject.NULL

    private fun decodeBox(obj: JSONObject?): NormalizedLayoutBox? = obj?.let {
        NormalizedLayoutBox(
            left = it.getDouble("l").toFloat(),
            top = it.getDouble("t").toFloat(),
            right = it.getDouble("r").toFloat(),
            bottom = it.getDouble("b").toFloat(),
        )
    }
}
