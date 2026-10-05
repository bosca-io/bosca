package yks.types

import yks.utils.*

/**
 * Shared XML fragment type. Root of an XML tree.
 * Matches yjs Y.XmlFragment.
 */
open class YXmlFragment : YType() {
    override val typeName: String = "XmlFragment"

    /** Insert a child type at the given index. */
    fun insert(index: Int, content: List<YType>) {
        val doc = doc ?: throw IllegalStateException("YXmlFragment not integrated")
        transact(doc) { transaction ->
            insertAt(transaction, index, content.map { it as Any? })
        }
    }

    /** Delete [length] children starting at [index]. */
    fun delete(index: Int, length: Int = 1) {
        val doc = doc ?: throw IllegalStateException("YXmlFragment not integrated")
        transact(doc) { transaction ->
            deleteAt(transaction, index, length)
        }
    }

    /** Get child at index. */
    fun get(index: Int): YType? = typeListGet(this, index) as? YType

    /** Get all children. */
    fun toArray(): List<YType> = typeListToArray(this).filterIsInstance<YType>()

    override fun toJSON(): Any? {
        return toArray().map { it.toJSON() }
    }

    override fun copy(): YType = YXmlFragment()

    override fun callObserver(transaction: Transaction, parentSubs: Set<String?>) {
        val event = YEvent(this, transaction, parentSubs)
        eventHandler.callListeners(Pair(event, transaction))
        propagateDeepEvent(this, transaction, event)
    }

    private fun insertAt(transaction: Transaction, index: Int, values: List<Any?>) {
        val (left, right) = findPosition(index)
        insertAfter(transaction, left, right, values, this)
    }

    private fun deleteAt(transaction: Transaction, index: Int, length: Int) {
        var remaining = length
        var item = findItemAtIndex(index)
        while (remaining > 0 && item != null) {
            if (!item.deleted && item.countable) {
                item.delete(transaction)
                remaining--
            }
            item = item.right
        }
    }

    private fun findPosition(index: Int): Pair<yks.structs.Item?, yks.structs.Item?> {
        if (index == 0) return Pair(null, start)
        var item = start
        var remaining = index
        while (item != null) {
            if (!item.deleted && item.countable) {
                remaining--
                if (remaining == 0) return Pair(item, item.right)
            }
            item = item.right
        }
        return Pair(item, null)
    }

    private fun findItemAtIndex(index: Int): yks.structs.Item? {
        var item = start
        var remaining = index
        while (item != null) {
            if (!item.deleted && item.countable) {
                if (remaining == 0) return item
                remaining--
            }
            item = item.right
        }
        return null
    }
}
