package org.techwithkaushik.formSnap

import org.techwithkaushik.formSnap.pipeline.DetectionKind

/** Stable, filesystem-safe output naming shared by both independently saved results. */
internal object OutputFileNaming {
    fun baseName(personName: String): String {
        val normalized = personName.trim()
            .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "")
            .replace(Regex("\\s+"), "-")
            .trim('-', '.', ' ')
            .take(80)
        return normalized.ifBlank { "FormSnap" }
    }

    fun desiredName(kind: DetectionKind, personName: String): String =
        when (kind) {
            DetectionKind.PHOTO -> "${baseName(personName)}-photo.jpg"
            DetectionKind.SIGNATURE -> "${baseName(personName)}-sign.png"
        }

    fun withSuffix(fileName: String, suffix: Int): String {
        require(suffix >= 1)
        val dot = fileName.lastIndexOf('.')
        return if (dot > 0) "${fileName.substring(0, dot)}_${suffix}${fileName.substring(dot)}"
        else "${fileName}_${suffix}"
    }
}
