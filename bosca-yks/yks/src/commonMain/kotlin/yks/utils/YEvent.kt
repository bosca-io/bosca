package yks.utils

import yks.structs.Item
import yks.structs.content.ContentFormat
import yks.structs.content.ContentType
import yks.types.YType

/**
 * Event emitted to type observers after a transaction.
 * Matches yjs YEvent.
 */
class YEvent(
    /** The type on which this event was emitted. */
    val target: YType,
    /** The transaction that caused this event. */
    val transaction: Transaction,
    /** Set of changed keys (null means list change). */
    val keysChanged: Set<String?>
) {
    /** Cached delta for this event. */
    private var _delta: List<Delta>? = null

    /**
     * Compute the delta (insert/delete/retain operations).
     */
    val delta: List<Delta>
        get() {
            if (_delta == null) {
                _delta = computeDelta()
            }
            return _delta!!
        }

    /**
     * Get changed keys with their change type.
     */
    fun keys(): Map<String, KeyChange> {
        val result = mutableMapOf<String, KeyChange>()
        for (key in keysChanged) {
            if (key != null) {
                val item = target.map[key]
                if (item != null) {
                    val action = if (transaction.insertSet.has(item.id.client, item.id.clock)) {
                        if (item.deleted) "delete" else "add"
                    } else {
                        "update"
                    }
                    result[key] = KeyChange(
                        action = action,
                        oldValue = if (item.left != null) item.left!!.content.getContent().lastOrNull() else null
                    )
                }
            }
        }
        return result
    }

    private fun computeDelta(): List<Delta> {
        val deltas = mutableListOf<Delta>()
        var item = target.start
        var retain = 0
        var insert = mutableListOf<Any?>()
        var deleteLen = 0

        while (item != null) {
            if (item.deleted) {
                if (transaction.deleteSet.hasId(item.id) && !transaction.insertSet.hasId(item.id)) {
                    // Deleted in this transaction (not just newly deleted remote)
                    if (insert.isNotEmpty()) {
                        deltas.add(Delta.Insert(insert))
                        insert = mutableListOf()
                    }
                    if (retain > 0) {
                        deltas.add(Delta.Retain(retain))
                        retain = 0
                    }
                    deleteLen += item.length
                }
            } else if (transaction.insertSet.hasId(item.id)) {
                // Inserted in this transaction
                if (deleteLen > 0) {
                    deltas.add(Delta.Delete(deleteLen))
                    deleteLen = 0
                }
                if (retain > 0) {
                    deltas.add(Delta.Retain(retain))
                    retain = 0
                }
                insert.addAll(item.content.getContent())
            } else {
                // Retained
                if (deleteLen > 0) {
                    deltas.add(Delta.Delete(deleteLen))
                    deleteLen = 0
                }
                if (insert.isNotEmpty()) {
                    deltas.add(Delta.Insert(insert))
                    insert = mutableListOf()
                }
                retain += item.length
            }
            item = item.right
        }

        // Flush remaining
        if (deleteLen > 0) deltas.add(Delta.Delete(deleteLen))
        if (insert.isNotEmpty()) deltas.add(Delta.Insert(insert))
        // Don't add trailing retain

        return deltas
    }

    /**
     * Get the path from root to this event's target.
     */
    fun getPath(): List<Any> {
        return getPathTo(target, transaction)
    }
}

/** Change description for a map key. */
data class KeyChange(val action: String, val oldValue: Any?)

/** Delta operations for list/text changes. */
sealed class Delta {
    data class Insert(val values: List<Any?>, val attributes: Map<String, Any?>? = null) : Delta()
    data class Delete(val length: Int) : Delta()
    data class Retain(val length: Int, val attributes: Map<String, Any?>? = null) : Delta()
}

/** Get the path from the document root to the given type. */
fun getPathTo(type: YType, transaction: Transaction): List<Any> {
    val path = mutableListOf<Any>()
    var current: YType? = type
    while (current != null) {
        val item = current.item ?: break
        val parent = item.parent as? YType ?: break
        if (item.parentSub != null) {
            path.add(0, item.parentSub!!)
        } else {
            // Find index
            var index = 0
            var n = parent.start
            while (n != null && n != item) {
                if (!n.deleted && n.countable) {
                    index += n.length
                }
                n = n.right
            }
            path.add(0, index)
        }
        current = parent
    }
    return path
}
