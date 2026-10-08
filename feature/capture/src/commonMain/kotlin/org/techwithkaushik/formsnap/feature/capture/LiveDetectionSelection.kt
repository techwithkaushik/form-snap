package org.techwithkaushik.formsnap.feature.capture

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Keeps tap-lock selections stable even when a detector assigns a new id
 * after every inference frame. Exact ids are preferred; otherwise a
 * same-class spatial match is used.
 */
class LiveDetectionSelection {
    private data class LockedSelection(
        val id: Long,
        val index: Int,
        val label: String,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
    )

    private val locked = ArrayList<LockedSelection>()

    fun toggle(detection: LiveDetection, nextIndex: Int): Boolean {
        val match = findLocked(detection)
        if (match != null) {
            locked.removeAt(match)
            return false
        }

        locked += LockedSelection(
            id = detection.id,
            index = nextIndex.coerceAtLeast(1),
            label = detection.label,
            left = detection.left,
            top = detection.top,
            right = detection.right,
            bottom = detection.bottom,
        )
        return true
    }

    fun nextIndex(): Int = (locked.maxOfOrNull { it.index } ?: 0) + 1

    fun indexOf(id: Long): Int? =
        locked.firstOrNull { it.id == id }?.index

    fun apply(detections: List<LiveDetection>): List<LiveDetection> {
        val used = HashSet<Int>()
        return detections.map { detection ->
            val matchIndex = locked.indices
                .filterNot { it in used }
                .minByOrNull { distanceScore(locked[it], detection) }

            val match = matchIndex?.let { locked[it] }
            val isMatch = match != null && matches(match, detection)

            if (isMatch && matchIndex != null) {
                used += matchIndex
                detection.copy(
                    selectionIndex = match.index,
                    locked = true,
                )
            } else {
                detection.copy(
                    selectionIndex = null,
                    locked = false,
                )
            }
        }
    }

    fun clear() = locked.clear()

    fun findHit(
        detections: List<LiveDetection>,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
    ): LiveDetection? {
        if (width <= 0f || height <= 0f) return null
        return detections
            .filter {
                x >= it.left * width &&
                    x <= it.right * width &&
                    y >= it.top * height &&
                    y <= it.bottom * height
            }
            .minByOrNull { centerDistanceSquared(it, x / width, y / height) }
    }

    private fun findLocked(detection: LiveDetection): Int? {
        val exact = locked.indexOfFirst { it.id == detection.id }
        if (exact >= 0) return exact

        return locked.indices
            .filter { matches(locked[it], detection) }
            .minByOrNull { distanceScore(locked[it], detection) }
    }

    private fun matches(selection: LockedSelection, detection: LiveDetection): Boolean {
        if (selection.label != detection.label) return false
        if (selection.id == detection.id) return true

        val iou = intersectionOverUnion(selection, detection)
        val centerDistance = centerDistanceSquared(
            detection,
            (selection.left + selection.right) * 0.5f,
            (selection.top + selection.bottom) * 0.5f,
        )
        return iou >= 0.20f || centerDistance <= 0.08f
    }

    private fun distanceScore(selection: LockedSelection, detection: LiveDetection): Float {
        if (selection.id == detection.id) return 0f
        val center = centerDistanceSquared(
            detection,
            (selection.left + selection.right) * 0.5f,
            (selection.top + selection.bottom) * 0.5f,
        )
        return center + (1f - intersectionOverUnion(selection, detection))
    }

    private fun intersectionOverUnion(
        selection: LockedSelection,
        detection: LiveDetection,
    ): Float {
        val left = max(selection.left, detection.left)
        val top = max(selection.top, detection.top)
        val right = min(selection.right, detection.right)
        val bottom = min(selection.bottom, detection.bottom)

        val intersection = (right - left).coerceAtLeast(0f) *
            (bottom - top).coerceAtLeast(0f)
        val firstArea = area(selection.left, selection.top, selection.right, selection.bottom)
        val secondArea = area(detection.left, detection.top, detection.right, detection.bottom)
        val union = firstArea + secondArea - intersection
        return if (union <= 0f) 0f else intersection / union
    }

    private fun area(left: Float, top: Float, right: Float, bottom: Float): Float =
        (right - left).coerceAtLeast(0f) * (bottom - top).coerceAtLeast(0f)

    private fun centerDistanceSquared(
        d: LiveDetection,
        x: Float,
        y: Float,
    ): Float {
        val dx = ((d.left + d.right) * 0.5f) - x
        val dy = ((d.top + d.bottom) * 0.5f) - y
        return hypot(dx, dy)
    }
}
