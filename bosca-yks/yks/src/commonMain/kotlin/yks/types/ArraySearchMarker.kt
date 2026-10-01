package yks.types

import yks.structs.Item

/**
 * Position cache marker for O(1)-amortized index access in linked lists.
 * Matches yjs ArraySearchMarker.
 *
 * YType maintains up to [MAX_SEARCH_MARKERS] markers.
 * When finding an index, the nearest marker is used as a starting point.
 * Markers use LRU eviction when the limit is exceeded.
 */
data class ArraySearchMarker(
    var item: Item,
    var index: Int,
    var timestamp: Long = globalTimestamp++
) {
    companion object {
        const val MAX_SEARCH_MARKERS = 80
        internal var globalTimestamp = 0L
    }
}

/**
 * Find or create a search marker closest to the given index.
 * Updates the marker to point to the item at [index].
 */
fun findMarker(type: YType, index: Int): ArraySearchMarker? {
    if (type.start == null || index == 0) return null
    val markers = type.searchMarker ?: return null
    if (markers.isEmpty()) return null

    var bestMarker: ArraySearchMarker? = null
    var bestDist = Int.MAX_VALUE

    for (marker in markers) {
        val dist = kotlin.math.abs(index - marker.index)
        if (dist < bestDist) {
            bestDist = dist
            bestMarker = marker
        }
    }

    return bestMarker
}

/**
 * Update a search marker by walking from its current position to the target index.
 */
fun updateMarker(marker: ArraySearchMarker, type: YType, index: Int) {
    var item = marker.item
    var idx = marker.index
    if (index > idx) {
        // Walk right
        while (item.right != null && idx < index) {
            val right = item.right!!
            if (!right.deleted && right.countable) {
                if (idx + right.length > index) break
                idx += right.length
            }
            item = right
        }
    } else {
        // Walk left
        while (idx > index) {
            val left = item.left ?: break
            if (!left.deleted && left.countable) {
                idx -= left.length
            }
            item = left
        }
    }
    marker.item = item
    marker.index = idx
    marker.timestamp = ArraySearchMarker.globalTimestamp++
}

/**
 * Mark a search marker as stale when its item is deleted or modified.
 */
fun refreshMarker(marker: ArraySearchMarker, type: YType) {
    // Walk right to find first non-deleted item
    var item: Item? = marker.item
    while (item != null && item.deleted) {
        item = item.right
    }
    if (item == null) {
        // Walk left from original
        item = marker.item
        while (item != null && item.deleted) {
            item = item.left
        }
    }
    if (item != null) {
        marker.item = item
    }
}
