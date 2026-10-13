package org.techwithkaushik.formsnap.ai

/**
 * Pure parser for common TFLite class-label sidecars and embedded metadata.
 * Class indices are preserved exactly, including gaps in numbered maps.
 */
internal object ClassLabelParser {
    fun parse(raw: String): List<String> {
        // MetadataExtractor may expose a non-text associated payload on some model
        // exports. Never decode arbitrary binary bytes as class names.
        if (raw.isEmpty() || raw.contains('\uFFFD')) return emptyList()
        val unexpectedControls = raw.count { it.isISOControl() && it != '\n' && it != '\r' && it != '\t' }
        if (unexpectedControls > maxOf(1, raw.length / 100)) return emptyList()

        val text = raw.removePrefix("\uFEFF").replace("\\n", "\n").trim()
        if (text.isEmpty()) return emptyList()

        // Older Ultralytics YOLOv10 exports may store Python's dict repr in
        // an associated temp_meta.txt, for example:
        // {'task': 'detect', 'names': {0: 'person', 1: 'bicycle'}}.
        // This is not valid JSON (single quotes and unquoted integer keys), so
        // JSONObject cannot read it. Extract only the numeric names map safely.
        val pythonMetadataLabels = parsePythonNamesMap(text)
        if (pythonMetadataLabels.isNotEmpty()) return pythonMetadataLabels

        val jsonLabels = runCatching {
            fun strings(value: org.json.JSONArray): List<String> = buildList {
                for (i in 0 until value.length()) {
                    when (val item = value.opt(i)) {
                        is String -> add(item)
                        is org.json.JSONObject -> {
                            val name = item.optString("name").ifBlank { item.optString("display_name") }
                            if (name.isNotBlank()) add(name)
                        }
                    }
                }
            }
            when {
                text.startsWith("[") -> strings(org.json.JSONArray(text))
                text.startsWith("{") -> {
                    val obj = org.json.JSONObject(text)
                    val known = listOf("names", "labels", "classes", "class_names", "categories", "annotations")
                    fun numericMap(map: org.json.JSONObject): List<String> {
                        val keys = map.keys().asSequence().toList()
                        if (keys.isEmpty() || !keys.all { it.toIntOrNull() != null }) return emptyList()
                        val ids = keys.map { it.toInt() }.sorted()
                        if (ids.first() < 0 || ids.last() > 999) return emptyList()
                        val byId = ids.associateWith { id -> map.optString(id.toString()).trim() }
                        // Preserve class IDs even if a nonstandard export omits an index.
                        return (0..ids.last()).map { id ->
                            byId[id]?.takeIf { it.isNotBlank() } ?: "Class $id"
                        }
                    }
                    val array = known.firstNotNullOfOrNull { key -> obj.optJSONArray(key) }
                    if (array != null) {
                        strings(array)
                    } else {
                        // Ultralytics metadata uses a nested object, e.g.
                        // {"names":{"0":"person","1":"bicycle"}} rather than a JSON array.
                        val nestedMap = known.firstNotNullOfOrNull { key ->
                            obj.optJSONObject(key)?.let { nested -> numericMap(nested) }?.takeIf { it.isNotEmpty() }
                        }
                        nestedMap ?: numericMap(obj)
                    }
                }
                else -> emptyList()
            }
        }.getOrDefault(emptyList())
        if (jsonLabels.isNotEmpty()) {
            val cleaned = jsonLabels.map { it.trim() }
            return cleaned.takeIf { labels -> labels.isNotEmpty() && labels.all(::isPlausibleLabel) }.orEmpty()
        }

        val namePattern = Regex("""(?i)\bname\s*:\s*["']([^"']+)["']""")
        val metadataNames = namePattern.findAll(text).map { it.groupValues[1].trim() }.filter { it.isNotEmpty() }.toList()
        if (metadataNames.isNotEmpty() && text.contains("item", ignoreCase = true) &&
            metadataNames.all(::isPlausibleLabel)
        ) return metadataNames

        val lines = text.lineSequence()
            .map { it.trim().removePrefix("\uFEFF") }
            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("//") }
            .toList()
        if (lines.isEmpty()) return emptyList()

        // Numbered label files encode class IDs, not just display order. Preserve
        // sparse IDs (e.g. "0 person", "2 car") so class 2 never becomes class 1.
        val numbered = parseNumberedLabels(lines)
        if (numbered.isNotEmpty()) return numbered

        return lines.map { line ->
            line.replace(Regex("""^\s*["']|["']\s*$"""), "").trim()
        }
            .filter(::isPlausibleLabel)
            // Do not deduplicate: label order and duplicate names are both valid
            // in a model's class map, and indices must remain stable.
            .take(1000)
    }


    /**
     * Parses fully numbered label files such as "0 person", "2: car", or
     * "5, traffic light". Missing IDs are retained as placeholders because the
     * detector indexes this list by the model's numeric class ID.
     */
    private fun parseNumberedLabels(lines: List<String>): List<String> {
        val pattern = Regex("""^\s*(\d{1,3})\s*(?:[,;:\t]+\s*|\s+)(.+?)\s*$""")
        val entries = lines.map { line ->
            val match = pattern.matchEntire(line) ?: return emptyList()
            val id = match.groupValues[1].toIntOrNull() ?: return emptyList()
            val label = match.groupValues[2].trim().trim('"', '\'')
            if (id !in 0..999 || !isPlausibleLabel(label)) return emptyList()
            id to label
        }
        if (entries.map { it.first }.toSet().size != entries.size) return emptyList()
        val byId = entries.toMap()
        val maxId = byId.keys.maxOrNull() ?: return emptyList()
        return (0..maxId).map { id -> byId[id] ?: "Class $id" }
    }

    /**
     * Reject decoded FlatBuffer/binary metadata and other corrupt payloads while
     * allowing normal labels such as "PHOTO", "SIGNATURE", "traffic light", and
     * Unicode class names.
     */
    private fun isPlausibleLabel(label: String): Boolean {
        val value = label.trim()
        if (value.isEmpty() || value.length > 160 || value.contains('\uFFFD')) return false
        if (value.any { it.isISOControl() }) return false
        val useful = value.count { it.isLetterOrDigit() }
        val odd = value.count { !it.isLetterOrDigit() && !it.isWhitespace() && it !in "._-/()&+'#" }
        return useful > 0 && odd <= maxOf(2, useful / 3)
    }
}
    private fun parsePythonNamesMap(text: String): List<String> {
        val namesBlock = Regex("""(?s)['"]?names['"]?\s*:\s*\{([^}]*)\}""")
            .find(text)?.groupValues?.getOrNull(1) ?: return emptyList()
        val entryPattern = Regex("""(\d{1,3})\s*:\s*(['"])(.*?)\\2""")
        val entries = entryPattern.findAll(namesBlock).mapNotNull { match ->
            val id = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            val label = match.groupValues[3].trim()
            if (id !in 0..999 || !isPlausibleLabel(label)) null else id to label
        }.toList()
        if (entries.isEmpty() || entries.map { it.first }.toSet().size != entries.size) return emptyList()
        val byId = entries.toMap()
        val maxId = byId.keys.maxOrNull() ?: return emptyList()
        return (0..maxId).map { id -> byId[id] ?: "Class $id" }
    }


