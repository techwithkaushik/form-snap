package org.techwithkaushik.formsnap.feature.capture

import kotlin.math.hypot

class LiveDetectionSelection {
    private val locked = LinkedHashMap<Long, Int>()

    fun toggle(detection: LiveDetection, nextIndex: Int): Boolean {
        if (locked.remove(detection.id) != null) return false
        locked[detection.id] = nextIndex
        return true
    }

    fun indexOf(id: Long): Int? = locked[id]

    fun apply(detections: List<LiveDetection>): List<LiveDetection> =
        detections.map { detection ->
            detection.copy(
                selectionIndex = locked[detection.id],
                locked = locked.containsKey(detection.id),
            )
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
            .filter { x >= it.left * width && x <= it.right * width && y >= it.top * height && y <= it.bottom * height }
            .minByOrNull { centerDistanceSquared(it, x / width, y / height) }
    }

    private fun centerDistanceSquared(d: LiveDetection, x: Float, y: Float): Float {
        val dx = ((d.left + d.right) * 0.5f) - x
        val dy = ((d.top + d.bottom) * 0.5f) - y
        return hypot(dx, dy)
    }
}
