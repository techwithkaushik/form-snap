package org.techwithkaushik.formsnap.feature.capture

/**
 * Builds deterministic photo/signature pairs from the user's selection order.
 *
 * The selection index is the pairing key. A missing partner is kept as a
 * standalone item instead of shifting another object into its place.
 */
data class SelectionPair(
    val selectionIndex: Int,
    val photo: LiveDetection? = null,
    val signature: LiveDetection? = null,
) {
    val isComplete: Boolean
        get() = photo != null && signature != null

    val isStandalone: Boolean
        get() = photo == null || signature == null
}

object SelectionPairing {
    fun build(detections: List<LiveDetection>): List<SelectionPair> {
        val selected = detections
            .filter { it.locked && it.selectionIndex != null }
            .groupBy { it.selectionIndex!! }

        return selected.keys
            .sorted()
            .map { index ->
                val group = selected[index].orEmpty()
                SelectionPair(
                    selectionIndex = index,
                    photo = group.firstOrNull { it.label.equals("Photo", ignoreCase = true) },
                    signature = group.firstOrNull { it.label.equals("Signature", ignoreCase = true) },
                )
            }
    }

    fun photosFirst(detections: List<LiveDetection>): List<SelectionPair> =
        build(detections).filter { it.photo != null }

    fun signaturesFirst(detections: List<LiveDetection>): List<SelectionPair> =
        build(detections).filter { it.signature != null }
}
