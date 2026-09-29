package org.techwithkaushik.formSnap.pipeline

import android.content.Context
import android.util.AtomicFile
import java.io.FileOutputStream
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object LearningStore {
    private const val FILE_NAME = "correction_learning.json"
    private const val SCHEMA = 2
    private const val MAX_PROFILES = 64

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    @Synchronized
    fun record(context: Context, correction: LearnedCorrection) {
        val profiles = read(context).toMutableList()
        val index = profiles.indexOfFirst {
            it.kind == correction.kind &&
                close(it.conditionAspectRatio, correction.conditionAspectRatio, 0.15f) &&
                close(it.conditionBrightness, correction.conditionBrightness, 0.15f) &&
                close(it.conditionEdgeDensity, correction.conditionEdgeDensity, 0.15f)
        }

        if (index >= 0) {
            profiles[index] = CorrectionLearning.blend(profiles[index], correction)
        } else {
            profiles += correction
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

    fun best(
        context: Context,
        kind: DetectionKind,
        conditionBrightness: Float = 0f,
        conditionContrast: Float = 1f,
        conditionSaturation: Float = 1f,
        conditionEdgeDensity: Float = 0f,
        aspectRatio: Float = 1f,
    ): LearnedCorrection? {
        val candidates = read(context).filter { it.kind == kind }
        if (candidates.isEmpty()) return null

        return candidates
            .maxByOrNull {
                val conditionDistance =
                    abs(it.conditionBrightness - conditionBrightness) +
                        abs(it.conditionContrast - conditionContrast) +
                        abs(it.conditionSaturation - conditionSaturation) +
                        abs(it.conditionEdgeDensity - conditionEdgeDensity) +
                        abs(it.conditionAspectRatio - aspectRatio)

                val similarity = (1f - conditionDistance / 4f).coerceIn(0f, 1f)
                val usage = min(100, it.sampleCount) / 100f
                similarity * 0.70f + it.confidence * 0.20f + usage * 0.10f
            }
            ?.takeIf { it.sampleCount >= 2 || it.confidence >= 0.70f }
    }

    @Synchronized
    fun clear(context: Context) {
        AtomicFile(file(context)).delete()
    }

    private fun close(a: Float, b: Float, tolerance: Float): Boolean =
        abs(a - b) <= tolerance

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
                    parse(array.optJSONObject(i), schema)?.let(::add)
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

    private fun write(context: Context, profiles: List<LearnedCorrection>) {
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

        root.put("profiles", array)
        val target = file(context)
        target.parentFile?.mkdirs()

        // AtomicFile preserves the previous valid learning store if the app is
        // killed or storage fails while a correction profile is being written.
        val atomic = AtomicFile(target)
        var stream: FileOutputStream? = null
        try {
            stream = atomic.startWrite()
            stream.write(root.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (t: Throwable) {
            stream?.let(atomic::failWrite)
            throw t
        }
    }
}
