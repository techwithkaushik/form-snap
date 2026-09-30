package org.techwithkaushik.formSnap.pipeline

import android.content.Context
import android.util.AtomicFile
import java.io.FileOutputStream
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.max

object LearningStore {
    private const val FILE_NAME = "correction_learning.json"
    private const val SCHEMA = 3
    private const val MAX_PROFILES = 64
    private const val MIN_PROFILE_SIMILARITY = 0.35f
    private const val MAX_IMPORT_BYTES = 1_048_576
    private const val MAX_IMPORT_RECORDS = 256

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    @Synchronized
    fun record(context: Context, correction: LearnedCorrection) {
        if (!CorrectionLearning.isSafe(correction)) return
        val safeCorrection = correction.copy(appearance = AppearanceTuning.clamp(correction.appearance))
        val profiles = read(context).filter(CorrectionLearning::isSafe).toMutableList()
        val index = profiles.indexOfFirst {
            CorrectionLearning.sameConditionProfile(it, safeCorrection)
        }

        if (index >= 0) {
            profiles[index] = CorrectionLearning.blend(profiles[index], safeCorrection)
        } else {
            profiles += safeCorrection
        }

        write(
            context,
            profiles
                .sortedWith(
                    compareByDescending<LearnedCorrection> { it.sampleCount }
                        .thenByDescending { it.confidence },
                )
                .take(MAX_PROFILES),
        )
    }

    /**
     * Selects a profile using only capture features actually supplied by the caller.
     * Unknown features are not silently compared against defaults, which previously
     * penalized profiles learned from real lighting/contrast/edge measurements.
     */
    fun best(
        context: Context,
        kind: DetectionKind,
        conditionBrightness: Float? = null,
        conditionContrast: Float? = null,
        conditionSaturation: Float? = null,
        conditionEdgeDensity: Float? = null,
        aspectRatio: Float? = null,
        minimumSimilarity: Float = MIN_PROFILE_SIMILARITY,
    ): LearnedCorrection? {
        if (!minimumSimilarity.isFinite() || minimumSimilarity !in 0f..1f) return null
        val candidates = read(context).filter {
            it.kind == kind && CorrectionLearning.isSafe(it)
        }
        if (candidates.isEmpty()) return null

        return candidates
            .map { profile ->
                val similarity = CorrectionLearning.conditionSimilarity(
                    profile = profile,
                    conditionBrightness = conditionBrightness,
                    conditionContrast = conditionContrast,
                    conditionSaturation = conditionSaturation,
                    conditionEdgeDensity = conditionEdgeDensity,
                    aspectRatio = aspectRatio,
                )
                val usage = minOf(100, profile.sampleCount) / 100f
                val score = similarity * 0.70f + profile.confidence * 0.20f + usage * 0.10f
                Triple(profile, similarity, score)
            }
            .filter { (_, similarity, _) -> similarity >= minimumSimilarity }
            .maxByOrNull { (_, _, score) -> score }
            ?.first
            ?.takeIf { it.sampleCount >= 2 || it.confidence >= 0.70f }
    }

    @Synchronized
    fun clear(context: Context) {
        AtomicFile(file(context)).delete()
    }

    /** Compact export includes only validated learning metadata, never original images. */
    @Synchronized
    fun exportJson(context: Context): String =
        encode(read(context).filter(CorrectionLearning::isSafe).take(MAX_PROFILES))

    /** Validates and merges profiles imported from a portable learning bundle. */
    @Synchronized
    fun importJson(context: Context, payload: String): LearningImportSummary {
        require(payload.toByteArray(Charsets.UTF_8).size <= MAX_IMPORT_BYTES) {
            "Learning payload is too large"
        }
        val root = JSONObject(payload)
        val schema = root.optInt("schema", -1)
        require(schema in 1..SCHEMA) { "Unsupported learning schema: $schema" }
        val array = root.optJSONArray("profiles")
            ?: throw IllegalArgumentException("Learning profiles are missing")
        require(array.length() <= MAX_IMPORT_RECORDS) { "Too many learning profiles" }

        val imported = ArrayList<LearnedCorrection>()
        var rejected = 0
        for (i in 0 until array.length()) {
            val profile = parse(array.optJSONObject(i), schema)
            if (profile != null && CorrectionLearning.isSafe(profile)) imported += profile
            else rejected++
        }
        require(imported.isNotEmpty()) { "No valid learning profiles found" }

        val merged = read(context).filter(CorrectionLearning::isSafe).toMutableList()
        var mergedCount = 0
        for (incoming in imported) {
            val index = merged.indexOfFirst {
                CorrectionLearning.sameConditionProfile(it, incoming)
            }
            if (index >= 0) {
                merged[index] = CorrectionLearning.mergeWeighted(merged[index], incoming)
                mergedCount++
            } else {
                merged += incoming
            }
        }
        write(
            context,
            merged.sortedWith(
                compareByDescending<LearnedCorrection> { it.sampleCount }
                    .thenByDescending { it.confidence },
            ).take(MAX_PROFILES),
        )
        return LearningImportSummary(imported.size, mergedCount, rejected)
    }

    private fun read(context: Context): List<LearnedCorrection> {
        val target = file(context)
        if (!target.exists()) return emptyList()

        return runCatching {
            val root = JSONObject(target.readText())
            val schema = root.optInt("schema", -1)
            if (schema !in 1..SCHEMA) return emptyList()

            val array = root.optJSONArray("profiles") ?: JSONArray()
            buildList {
                for (i in 0 until array.length()) {
                    parse(array.optJSONObject(i), schema)?.takeIf(CorrectionLearning::isSafe)?.let(::add)
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun parse(obj: JSONObject?, schema: Int): LearnedCorrection? {
        if (obj == null) return null
        val kind = runCatching { DetectionKind.valueOf(obj.optString("kind")) }.getOrNull() ?: return null
        val appearance = obj.optJSONObject("appearance") ?: JSONObject()

        return LearnedCorrection(
            kind = kind,
            boundsDeltaLeft = obj.optDouble("dl", 0.0).toFloat(),
            boundsDeltaTop = obj.optDouble("dt", 0.0).toFloat(),
            boundsDeltaRight = obj.optDouble("dr", 0.0).toFloat(),
            boundsDeltaBottom = obj.optDouble("db", 0.0).toFloat(),
            appearance = AppearanceAdjustments(
                brightness = appearance.optDouble("b", 0.0).toFloat(),
                contrast = appearance.optDouble("c", 1.0).toFloat(),
                saturation = appearance.optDouble("s", 1.0).toFloat(),
                sharpness = appearance.optDouble("sh", 0.0).toFloat(),
                denoise = appearance.optDouble("dn", 0.0).toFloat(),
                inkThreshold = appearance.optInt("it", 150),
                backgroundCleanup = appearance.optDouble("bg", 0.0).toFloat(),
            ),
            conditionBrightness = obj.optDouble("cb", 0.0).toFloat(),
            conditionContrast = obj.optDouble("cc", 1.0).toFloat(),
            conditionSaturation = obj.optDouble("cs", 1.0).toFloat(),
            conditionEdgeDensity = obj.optDouble("ce", 0.0).toFloat(),
            conditionAspectRatio = obj.optDouble("ca", 1.0).toFloat(),
            sampleCount = max(1, obj.optInt("n", 1)),
            confidence = obj.optDouble("cf", 0.5).toFloat().coerceIn(0f, 1f),
            version = max(1, obj.optInt("v", if (schema >= 2) 2 else 1)),
        )
    }

    private fun encode(profiles: List<LearnedCorrection>): String {
        val root = JSONObject().put("schema", SCHEMA)
        val array = JSONArray()
        profiles.forEach { profile ->
            array.put(
                JSONObject()
                    .put("kind", profile.kind.name)
                    .put("dl", profile.boundsDeltaLeft)
                    .put("dt", profile.boundsDeltaTop)
                    .put("dr", profile.boundsDeltaRight)
                    .put("db", profile.boundsDeltaBottom)
                    .put("n", profile.sampleCount)
                    .put("cf", profile.confidence)
                    .put("v", profile.version)
                    .put("cb", profile.conditionBrightness)
                    .put("cc", profile.conditionContrast)
                    .put("cs", profile.conditionSaturation)
                    .put("ce", profile.conditionEdgeDensity)
                    .put("ca", profile.conditionAspectRatio)
                    .put(
                        "appearance",
                        JSONObject()
                            .put("b", profile.appearance.brightness)
                            .put("c", profile.appearance.contrast)
                            .put("s", profile.appearance.saturation)
                            .put("sh", profile.appearance.sharpness)
                            .put("dn", profile.appearance.denoise)
                            .put("it", profile.appearance.inkThreshold)
                            .put("bg", profile.appearance.backgroundCleanup),
                    ),
            )
        }
        return root.put("profiles", array).toString()
    }

    private fun write(context: Context, profiles: List<LearnedCorrection>) {
        val target = file(context)
        target.parentFile?.mkdirs()
        val atomic = AtomicFile(target)
        var stream: FileOutputStream? = null
        try {
            stream = atomic.startWrite()
            stream.write(encode(profiles).toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (t: Throwable) {
            stream?.let(atomic::failWrite)
            throw t
        }
    }
}

data class LearningImportSummary(
    val importedProfiles: Int,
    val mergedProfiles: Int,
    val rejectedProfiles: Int,
)
