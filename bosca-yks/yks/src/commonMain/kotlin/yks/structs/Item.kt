package yks.structs

import yks.lib0.*
import yks.structs.content.*
import yks.types.YArray
import yks.types.YMap
import yks.types.YText
import yks.types.YType
import yks.utils.*

/**
 * The fundamental CRDT atom. Every insert creates one or more Items.
 * Implements the YATA conflict resolution algorithm in integrate().
 * Matches yjs Item from src/structs/Item.js.
 */
class Item(
    id: ID,
    /** Current left neighbor. */
    var left: Item?,
    /** ID of item originally to the left (causal history). */
    var origin: ID?,
    /** Current right neighbor. */
    var right: Item?,
    /** ID of item originally to the right. */
    var rightOrigin: ID?,
    /** The containing type or parent reference. */
    var parent: Any?, // YType | ID | String | null
    /** Map key (null for array/list items). */
    var parentSub: String?,
    /** The actual data payload. */
    var content: AbstractContent
) : AbstractStruct(id, content.getLength()) {

    /** Bitmask: BIT1=keep, BIT2=countable, BIT3=deleted, BIT4=marker */
    var info: Int = if (content.isCountable()) BIT2 else 0

    /** Reference to redo item (for UndoManager). */
    var redone: ID? = null

    override val deleted: Boolean get() = info and BIT3 != 0
    val countable: Boolean get() = info and BIT2 != 0
    val keep: Boolean get() = info and BIT1 != 0
    val marker: Boolean get() = info and BIT4 != 0

    fun markDeleted() { info = info or BIT3 }
    fun markKeep() { info = info or BIT1 }

    /** Get the next non-deleted right neighbor. */
    val next: Item? get() {
        var n = right
        while (n != null && n.deleted) n = n.right
        return n
    }

    /** Get the previous non-deleted left neighbor. */
    val prev: Item? get() {
        var n = left
        while (n != null && n.deleted) n = n.left
        return n
    }

    /**
     * YATA conflict resolution algorithm.
     * This is the heart of the CRDT - determines the correct position
     * for this item among concurrent insertions.
     * Matches yjs Item.integrate().
     */
    override fun integrate(transaction: Transaction, offset: Int) {
        if (offset > 0) {
            id = ID(id.client, id.clock + offset)
            left = getItemCleanEnd(transaction, transaction.doc.store, ID(id.client, id.clock - 1))
            origin = left?.lastId
            content = content.splice(offset)
            length -= offset
        }

        // Resolve left/right from origin/rightOrigin IDs
        var originResolvedToGC = false
        val originId = origin
        if (originId != null) {
            left = getItemCleanEnd(transaction, transaction.doc.store, originId)
            if (left == null) originResolvedToGC = true
            origin = left?.lastId
        }
        var rightOriginResolvedToGC = false
        val roId = rightOrigin
        if (roId != null) {
            if (roId.clock < getState(transaction.doc.store, roId.client)) {
                right = getItemCleanStart(transaction, roId)
                if (right == null) rightOriginResolvedToGC = true
                rightOrigin = right?.id
            }
        }

        // If either origin resolved to a GC struct, this item must also be GC'd (matches Yjs behavior)
        if (originResolvedToGC || rightOriginResolvedToGC) {
            parent = null
        }

        val parentType = resolveParent(transaction)
        if (parentType == null) {
            // Parent is GC'd — integrate as GC
            GC(id, length).integrate(transaction, 0)
            return
        }

        // Conflict resolution: find correct insertion point
        if ((left == null && (right == null || right?.left != null)) ||
            (left != null && left?.right != right)
        ) {
            // There are items between left and right — need to resolve conflicts
            var searchLeft = left
            var o: Item?
            if (searchLeft != null) {
                o = searchLeft.right
            } else if (parentSub != null) {
                o = parentType.map[parentSub]
                // Walk to leftmost item
                while (o != null && o.left != null) {
                    o = o.left
                }
            } else {
                o = parentType.start
            }

            val conflictingItems = mutableSetOf<Item>()
            val itemsBeforeOrigin = mutableSetOf<Item>()

            while (o != null && o != right) {
                itemsBeforeOrigin.add(o)
                conflictingItems.add(o)

                if (compareIDs(origin, o.origin)) {
                    // Case 1: Same origin — lower client ID goes first
                    if (o.id.client < id.client) {
                        searchLeft = o
                        conflictingItems.clear()
                    } else if (compareIDs(rightOrigin, o.rightOrigin)) {
                        // Identical integration points
                        break
                    }
                } else {
                    val oOriginItem = if (o.origin != null) {
                        getItem(transaction.doc.store, o.origin!!)
                    } else null
                    if (oOriginItem != null && itemsBeforeOrigin.contains(oOriginItem)) {
                        // Case 2: o's origin is between our origin and us
                        if (!conflictingItems.contains(oOriginItem)) {
                            searchLeft = o
                            conflictingItems.clear()
                        }
                    } else {
                        break
                    }
                }
                o = o.right
            }
            left = searchLeft
        }

        // Reconnect the linked list
        if (left != null) {
            val r = left!!.right
            right = r
            left!!.right = this
        } else {
            val r: Item?
            if (parentSub != null) {
                r = parentType.map[parentSub]
                while (r != null && r.left != null) {
                    // This shouldn't happen in normal operation, but handle gracefully
                    break
                }
                right = parentType.map[parentSub]
                // Walk to the leftmost
                var leftmost = right
                while (leftmost?.left != null) {
                    leftmost = leftmost.left
                }
                right = leftmost
            } else {
                r = parentType.start
                right = r
            }
            if (parentSub == null) {
                parentType.start = this
            }
        }

        if (right != null) {
            right!!.left = this
        } else if (parentSub != null) {
            // This is the newest value for this map key
            parentType.map[parentSub!!] = this
            if (left != null) {
                // Delete previous map value
                left!!.delete(transaction)
            }
        }

        // Update parent length
        if (parentSub == null && countable && !deleted) {
            parentType.length += length
        }

        addToInsertSet(transaction, id.client, id.clock, length)
        addStruct(transaction.doc.store, this)
        content.integrate(transaction, this)
        addChangedTypeToTransaction(transaction, parentType, parentSub)

        // Auto-delete if parent is deleted or there's a newer map value
        val parentItem = parentType.item
        if ((parentItem != null && parentItem.deleted) || (parentSub != null && right != null)) {
            delete(transaction)
        }
    }

    /** Resolve parent from ID/string/YType to actual YType. */
    private fun resolveParent(transaction: Transaction): YType? {
        when (val p = parent) {
            is YType -> return p
            is ID -> {
                val item = getItem(transaction.doc.store, p) as? Item
                val content = item?.content
                if (content is ContentType) {
                    parent = content.type
                    return content.type
                }
                return null
            }
            is String -> {
                // Root type key — get or create the type.
                val existing = transaction.doc.share[p]
                if (existing != null) {
                    parent = existing
                    return existing
                }
                val type = if (parentSub != null) {
                    YMap()
                } else if (content is ContentString || content is ContentFormat) {
                    YText()
                } else {
                    YArray()
                }
                type.isAutoCreated = true
                type.integrate(transaction.doc, null)
                transaction.doc.share[p] = type
                parent = type
                return type
            }
            null -> {
                // Parent not explicitly set — derive from left or right (matching yjs)
                if (left != null) {
                    parent = left!!.parent
                    parentSub = left!!.parentSub
                    return parent as? YType
                }
                if (right != null) {
                    parent = right!!.parent
                    parentSub = right!!.parentSub
                    return parent as? YType
                }
                return null
            }
            else -> return null
        }
    }

    /** Delete this item. */
    fun delete(transaction: Transaction) {
        if (!deleted) {
            markDeleted()
            transaction.addToDeleteSet(id.client, id.clock, length)
            val parentType = parent as? YType
            if (parentType != null && countable) {
                parentType.length -= length
            }
            content.delete(transaction)
            addChangedTypeToTransaction(transaction, parentType, parentSub)
        }
    }

    /** Garbage-collect this item. */
    fun gc(transaction: Transaction, parentGCd: Boolean) {
        if (!deleted) throw IllegalStateException("Cannot GC non-deleted item")
        content.gc(transaction)
        if (parentGCd) {
            // Replace with GC struct
            replaceStruct(transaction.doc.store, this, GC(id, length))
        } else {
            content = ContentDeleted(length)
        }
    }

    override fun mergeWith(right: AbstractStruct): Boolean {
        if (right !is Item) return false
        if (this.right !== right) return false // Must be linked-list adjacent
        if (right.id.client != id.client) return false
        if (right.id.clock != id.clock + length) return false
        if (deleted != right.deleted) return false
        if (redone != null || right.redone != null) return false
        if (content::class != right.content::class) return false
        if (!content.mergeWith(right.content)) return false
        // Update linked list
        if (right.right != null) {
            right.right!!.left = this
        }
        this.right = right.right
        length += right.length
        return true
    }

    override fun write(encoder: UpdateEncoder, offset: Int, offsetEnd: Int) {
        val hasOrigin = origin != null
        val hasRightOrigin = rightOrigin != null
        val hasParentSub = parentSub != null

        val info = (content.getRef() and BITS5) or
            (if (hasOrigin) BIT8 else 0) or
            (if (hasRightOrigin) BIT7 else 0) or
            (if (hasParentSub) BIT6 else 0)

        encoder.writeInfo(info)

        if (hasOrigin) {
            encoder.writeLeftID(origin!!.client, origin!!.clock)
        }
        if (hasRightOrigin) {
            encoder.writeRightID(rightOrigin!!.client, rightOrigin!!.clock)
        }
        if (!hasOrigin && !hasRightOrigin) {
            val p = parent
            if (p is YType) {
                val pItem = p.item
                if (pItem == null) {
                    // Root type — write type key
                    encoder.writeParentInfo(true)
                    encoder.writeString(findRootTypeKey(p))
                } else {
                    encoder.writeParentInfo(false)
                    encoder.writeLeftID(pItem.id.client, pItem.id.clock)
                }
            } else if (p is ID) {
                encoder.writeParentInfo(false)
                encoder.writeLeftID(p.client, p.clock)
            } else if (p is String) {
                encoder.writeParentInfo(true)
                encoder.writeString(p)
            } else {
                throw IllegalStateException("Cannot write Item with unresolved parent")
            }
            // parentSub is written inside this block, matching yjs
            if (hasParentSub) {
                encoder.writeString(parentSub!!)
            }
        }
        content.write(encoder, offset)
    }

    override fun getMissing(transaction: Transaction, store: StructStore): Int? {
        // Check if origin is present
        if (origin != null) {
            val originClient = origin!!.client
            val originClock = origin!!.clock
            if (originClock >= getState(store, originClient)) {
                return originClient
            }
        }
        // Check if rightOrigin is present
        if (rightOrigin != null) {
            val roClient = rightOrigin!!.client
            val roClock = rightOrigin!!.clock
            if (roClock >= getState(store, roClient)) {
                return roClient
            }
        }
        // Check if parent is present (when it's an ID)
        if (parent is ID) {
            val pid = parent as ID
            if (pid.clock >= getState(store, pid.client)) {
                return pid.client
            }
        }
        return null
    }

    companion object {
        const val GC_REF = 0
        const val SKIP_REF = 10
    }
}
