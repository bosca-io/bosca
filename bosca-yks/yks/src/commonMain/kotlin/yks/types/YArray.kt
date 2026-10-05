package yks.types

import yks.structs.Item
import yks.structs.content.*
import yks.utils.*

/**
 * Shared array type. Stores ordered items in a doubly-linked list.
 * Matches yjs Y.Array.
 */
class YArray : YType() {
    override val typeName: String = "Array"

    init {
        searchMarker = mutableListOf()
    }

    /** Insert content at the given index. */
    fun insert(index: Int, content: List<Any?>) {
        val doc = doc ?: throw IllegalStateException("YArray not integrated")
        transact(doc) { transaction ->
            insertAt(transaction, index, content)
        }
    }

    /** Insert a single value at the given index. */
    fun insert(index: Int, value: Any?) {
        insert(index, listOf(value))
    }

    /** Delete [length] items starting at [index]. */
    fun delete(index: Int, length: Int = 1) {
        val doc = doc ?: throw IllegalStateException("YArray not integrated")
        transact(doc) { transaction ->
            deleteAt(transaction, index, length)
        }
    }

    /** Push items to the end. */
    fun push(content: List<Any?>) {
        val doc = doc ?: throw IllegalStateException("YArray not integrated")
        transact(doc) { transaction ->
            // Walk to the rightmost item in the linked list (including deleted items),
            // matching yjs typeListPushGenerics behavior.
            var n = start
            while (n?.right != null) {
                n = n.right
            }
            insertAfter(transaction, n, n?.right, content, this)
        }
    }

    /** Remove and return the last item. */
    fun pop(): Any? {
        val lastItem = get(length - 1)
        delete(length - 1)
        return lastItem
    }

    /** Get the item at the given index. */
    fun get(index: Int): Any? {
        return typeListGet(this, index)
    }

    /** Convert to a regular list. */
    fun toArray(): List<Any?> {
        return typeListToArray(this)
    }

    override fun toJSON(): Any? = toArray().map { value ->
        when (value) {
            is YType -> value.toJSON()
            else -> value
        }
    }

    override fun copy(): YType = YArray()

    override fun callObserver(transaction: Transaction, parentSubs: Set<String?>) {
        val event = YEvent(this, transaction, parentSubs)
        eventHandler.callListeners(Pair(event, transaction))
        // Propagate to parent chain for deep observers
        propagateDeepEvent(this, transaction, event)
    }

    internal fun insertAt(transaction: Transaction, index: Int, values: List<Any?>) {
        if (index == 0) {
            insertAfter(transaction, null, start, values, this)
            return
        }
        // Find the position by scanning the list (matching yjs typeListInsertGenerics)
        var n = start
        var remaining = index
        while (n != null) {
            if (!n.deleted && n.countable) {
                if (remaining <= n.length) {
                    if (remaining < n.length) {
                        // Split the item — insertion point is in the middle
                        getItemCleanStart(transaction, ID(n.id.client, n.id.clock + remaining))
                    }
                    break
                }
                remaining -= n.length
            }
            n = n.right
        }
        // After potential split, n is the left neighbor
        insertAfter(transaction, n, n?.right, values, this)
    }

    internal fun deleteAt(transaction: Transaction, index: Int, length: Int) {
        if (length == 0) return
        var remaining = length
        var item = start
        var count = 0
        while (item != null && remaining > 0) {
            val current = item
            if (!current.deleted && current.countable) {
                if (count + current.length > index) {
                    val target = if (count < index) {
                        val diff = index - count
                        getItemCleanStart(transaction, ID(current.id.client, current.id.clock + diff)) ?: break
                    } else {
                        current
                    }
                    if (target.length > remaining) {
                        getItemCleanStart(transaction, ID(target.id.client, target.id.clock + remaining))
                    }
                    val delLen = minOf(remaining, target.length)
                    target.delete(transaction)
                    remaining -= delLen
                    item = target.right
                    count = index + (length - remaining)
                    continue
                }
                count += current.length
            }
            item = current.right
        }
    }

    private fun findPosition(index: Int): Pair<Item?, Item?> {
        if (index == 0) return Pair(null, start)
        var item = start
        var remaining = index
        while (item != null) {
            if (!item.deleted && item.countable) {
                if (remaining <= item.length) {
                    return Pair(item, item.right)
                }
                remaining -= item.length
            }
            if (item.right == null) break
            item = item.right
        }
        return Pair(item, null)
    }

    private fun findItemAtIndex(index: Int): Item? {
        var item = start
        var remaining = index
        while (item != null) {
            if (!item.deleted && item.countable) {
                if (remaining < item.length) return item
                remaining -= item.length
            }
            item = item.right
        }
        return null
    }
}

/**
 * Insert values after a given position.
 * Batches consecutive primitive values into a single ContentAny to match yjs behavior.
 */
internal fun insertAfter(
    transaction: Transaction,
    left: Item?,
    right: Item?,
    values: List<Any?>,
    parent: YType? = null
) {
    val parentType = parent ?: left?.parent as? YType ?: right?.parent as? YType
        ?: throw IllegalStateException("Cannot determine parent")
    var currentLeft = left

    // Batch consecutive primitives into ContentAny (matching yjs)
    val pendingAny = mutableListOf<Any?>()

    fun flushPendingAny() {
        if (pendingAny.isEmpty()) return
        val content = ContentAny(pendingAny.toMutableList())
        val id = transaction.nextID()
        val item = Item(
            id = id,
            left = currentLeft,
            origin = currentLeft?.lastId,
            right = right,
            rightOrigin = right?.id,
            parent = parentType,
            parentSub = null,
            content = content
        )
        item.integrate(transaction, 0)
        currentLeft = item
        pendingAny.clear()
    }

    for (value in values) {
        when (value) {
            is YType -> {
                flushPendingAny()
                val content = ContentType(value)
                val id = transaction.nextID()
                val item = Item(
                    id = id,
                    left = currentLeft,
                    origin = currentLeft?.lastId,
                    right = right,
                    rightOrigin = right?.id,
                    parent = parentType,
                    parentSub = null,
                    content = content
                )
                item.integrate(transaction, 0)
                currentLeft = item
            }
            is Doc -> {
                flushPendingAny()
                val content = yks.structs.content.ContentDoc(value)
                val id = transaction.nextID()
                val item = Item(
                    id = id,
                    left = currentLeft,
                    origin = currentLeft?.lastId,
                    right = right,
                    rightOrigin = right?.id,
                    parent = parentType,
                    parentSub = null,
                    content = content
                )
                item.integrate(transaction, 0)
                currentLeft = item
            }
            is ByteArray -> {
                flushPendingAny()
                val content = ContentBinary(value)
                val id = transaction.nextID()
                val item = Item(
                    id = id,
                    left = currentLeft,
                    origin = currentLeft?.lastId,
                    right = right,
                    rightOrigin = right?.id,
                    parent = parentType,
                    parentSub = null,
                    content = content
                )
                item.integrate(transaction, 0)
                currentLeft = item
            }
            else -> {
                // Batch primitive values into ContentAny
                pendingAny.add(value)
            }
        }
    }
    flushPendingAny()
}

/** Get item at index in a list-type. */
fun typeListGet(type: YType, index: Int): Any? {
    var item = type.start
    var remaining = index
    while (item != null) {
        if (!item.deleted && item.countable) {
            if (remaining < item.length) {
                return item.content.getContent()[remaining]
            }
            remaining -= item.length
        }
        item = item.right
    }
    return null
}

/** Convert a list-type to array. */
fun typeListToArray(type: YType): List<Any?> {
    val result = mutableListOf<Any?>()
    var item = type.start
    while (item != null) {
        if (!item.deleted && item.countable) {
            result.addAll(item.content.getContent())
        }
        item = item.right
    }
    return result
}

/** Propagate a deep event up the parent chain. */
internal fun propagateDeepEvent(type: YType, transaction: Transaction, event: YEvent) {
    var current: YType? = type
    while (current != null) {
        val parentItem = current.item ?: break
        val parentType = parentItem.parent as? YType ?: break
        if (parentItem.deleted) break
        val events = transaction.changedParentTypes.getOrPut(parentType) { mutableListOf() }
        events.add(event)
        current = parentType
    }
}
