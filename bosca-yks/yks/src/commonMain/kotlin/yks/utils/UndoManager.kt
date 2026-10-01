package yks.utils

import yks.structs.Item
import yks.types.YType

/**
 * Undo/redo manager for collaborative editing.
 * Tracks changes within scoped types and allows undoing/redoing them.
 * Matches yjs UndoManager.
 */
class UndoManager(
    val scope: List<YType>,
    val doc: Doc = scope.first().doc ?: throw IllegalStateException("Type not integrated"),
    /** Origins to track. Only transactions with matching origin are tracked. */
    val trackedOrigins: MutableSet<Any?> = mutableSetOf(null),
    /** Milliseconds to merge consecutive operations. */
    val captureTimeout: Long = 500L,
    val ignoreRemoteMapChanges: Boolean = false
) {
    val undoStack = mutableListOf<StackItem>()
    val redoStack = mutableListOf<StackItem>()

    private var lastChangeTime = 0L
    private var undoing = false
    private var redoing = false

    init {
        trackedOrigins.add(this)

        // Register the actual tracking handler
        doc.on<Transaction>("afterTransactionCleanup") { transaction ->
            afterTransaction(transaction)
        }
    }

    private fun afterTransaction(transaction: Transaction) {
        if (undoing || redoing) return
        if (!trackedOrigins.contains(transaction.origin) && transaction.origin != this) return

        val insertSet = filterScope(transaction.insertSet)
        val deleteSet = filterScope(transaction.deleteSet)

        if (insertSet.isEmpty() && deleteSet.isEmpty()) return

        val now = yks.lib0.currentTimeMillis()
        val lastItem = undoStack.lastOrNull()

        if (lastItem != null && now - lastChangeTime < captureTimeout) {
            // Merge with last stack item
            mergeIdSets(listOf(lastItem.insertSet, insertSet)).let { merged ->
                lastItem.insertSet = merged
            }
            mergeIdSets(listOf(lastItem.deleteSet, deleteSet)).let { merged ->
                lastItem.deleteSet = merged
            }
        } else {
            undoStack.add(StackItem(insertSet, deleteSet))
        }

        if (!undoing && !redoing) {
            redoStack.clear()
        }

        lastChangeTime = now
    }

    private fun filterScope(set: IdSet): IdSet {
        val filtered = IdSet()
        val store = doc.store
        for ((client, ranges) in set.clients) {
            val structs = store.clients[client] ?: continue
            for (range in ranges.getIds()) {
                val end = range.clock + range.len
                var idx = if (structs.isNotEmpty()) {
                    try { findIndexSS(structs, range.clock) } catch (_: Exception) { -1 }
                } else -1
                while (idx >= 0 && idx < structs.size) {
                    val struct = structs[idx]
                    if (struct.id.clock >= end) break
                    if (struct is Item) {
                        val parent = struct.parent
                        if (parent is YType && scope.any { it === parent || isParentOf(it, parent) }) {
                            filtered.add(client, struct.id.clock, struct.length)
                        }
                    }
                    idx++
                }
            }
        }
        return filtered
    }

    /** Undo the last operation. Returns the stack item or null if nothing to undo. */
    fun undo(): StackItem? {
        if (undoStack.isEmpty()) return null
        val item = undoStack.removeLast()
        undoing = true
        try {
            transact(doc, this) { transaction ->
                // Delete items that were inserted
                for ((client, ranges) in item.insertSet.clients) {
                    val structs = doc.store.clients[client] ?: continue
                    for (range in ranges.getIds()) {
                        iterateStructs(transaction, structs, range.clock, range.len) { struct ->
                            if (struct is Item && !struct.deleted) {
                                struct.delete(transaction)
                            }
                        }
                    }
                }
                // Re-create items that were deleted
                // (simplified — full implementation would use redoItem)
            }
            redoStack.add(StackItem(item.deleteSet, item.insertSet))
        } finally {
            undoing = false
        }
        return item
    }

    /** Redo the last undone operation. Returns the stack item or null if nothing to redo. */
    fun redo(): StackItem? {
        if (redoStack.isEmpty()) return null
        val item = redoStack.removeLast()
        redoing = true
        try {
            transact(doc, this) { transaction ->
                for ((client, ranges) in item.insertSet.clients) {
                    val structs = doc.store.clients[client] ?: continue
                    for (range in ranges.getIds()) {
                        iterateStructs(transaction, structs, range.clock, range.len) { struct ->
                            if (struct is Item && !struct.deleted) {
                                struct.delete(transaction)
                            }
                        }
                    }
                }
            }
            undoStack.add(StackItem(item.deleteSet, item.insertSet))
        } finally {
            redoing = false
        }
        return item
    }

    /** Clear both undo and redo stacks. */
    fun clear() {
        undoStack.clear()
        redoStack.clear()
    }

    fun canUndo(): Boolean = undoStack.isNotEmpty()
    fun canRedo(): Boolean = redoStack.isNotEmpty()

    fun destroy() {
        clear()
    }
}

/** A single undo/redo operation. */
data class StackItem(
    var insertSet: IdSet,
    var deleteSet: IdSet,
    val meta: MutableMap<Any, Any?> = mutableMapOf()
)

/** Check if [parent] is a parent of [child] by walking the parent chain. */
fun isParentOf(parent: YType, child: YType): Boolean {
    var current: YType? = child
    while (current != null) {
        if (current === parent) return true
        val item = current.item ?: return false
        current = item.parent as? YType
    }
    return false
}
