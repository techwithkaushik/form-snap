package org.techwithkaushik.formsnap.feature.capture

/**
 * A logical output pair. Photo and signature are paired by their own
 * selection order, not by requiring both objects to have the same index.
 *
 * Example:
 *   Photo #1, Photo #3
 *   Signature #2, Signature #4
 * becomes:
 *   Pair 1 = Photo #1 + Signature #2
 *   Pair 2 = Photo #3 + Signature #4
 *
 * This makes the pairing self-healing when an object disappears or is
 * re-selected: remaining objects keep their relative order.
 */
data class SelectionPair(
    val pairIndex: Int,
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

        val photos = selected
            .filter { it.label.equals("Photo", ignoreCase = true) }
            .sortedBy { it.selectionIndex }

        val signatures = selected
            .filter { it.label.equals("Signature", ignoreCase = true) }
            .sortedBy { it.selectionIndex }

        val pairCount = maxOf(photos.size, signatures.size)

        return (0 until pairCount).map { position ->
            SelectionPair(
                pairIndex = position + 1,
                photo = photos.getOrNull(position),
                signature = signatures.getOrNull(position),
            )
        }
    }

    fun completePairs(detections: List<LiveDetection>): List<SelectionPair> =
        build(detections).filter(SelectionPair::isComplete)

    fun standaloneItems(detections: List<LiveDetection>): List<SelectionPair> =
        build(detections).filter(SelectionPair::isStandalone)

    fun photosFirst(detections: List<LiveDetection>): List<SelectionPair> =
        build(detections).filter { it.photo != null }

    fun signaturesFirst(detections: List<LiveDetection>): List<SelectionPair> =
        build(detections).filter { it.signature != null }
}
