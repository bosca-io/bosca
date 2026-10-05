package yks.types

import yks.structs.Item
import yks.structs.content.*
import yks.utils.*

/**
 * Shared map type. Key-value store with last-writer-wins semantics.
 * Matches yjs Y.Map.
 */
open class YMap : YType() {
    override val typeName: String = "Map"

    /** Set a key-value pair. */
    fun set(key: String, value: Any?) {
        val doc = doc ?: throw IllegalStateException("YMap not integrated")
        transact(doc) { transaction ->
            typeMapSet(transaction, this, key, value)
        }
    }

    /** Get the value for a key. */
    fun get(key: String): Any? = typeMapGet(this, key)

    /** Delete a key. */
    fun delete(key: String) {
        val doc = doc ?: throw IllegalStateException("YMap not integrated")
        transact(doc) { transaction ->
            typeMapDelete(transaction, this, key)
        }
    }

    /** Check if key exists and is not deleted. */
    fun has(key: String): Boolean = typeMapHas(this, key)

    /** Get all non-deleted entries. */
    fun entries(): Map<String, Any?> = typeMapGetAll(this)

    /** Get all non-deleted keys. */
    fun keys(): Set<String> = entries().keys

    /** Get all non-deleted values. */
    fun values(): Collection<Any?> = entries().values

    /** Number of non-deleted entries. */
    val size: Int get() = entries().size

    fun forEach(action: (value: Any?, key: String) -> Unit) {
        for ((k, v) in entries()) action(v, k)
    }

    override fun toJSON(): Any? {
        val result = mutableMapOf<String, Any?>()
        for ((key, value) in entries()) {
            result[key] = when (value) {
                is YType -> value.toJSON()
                else -> value
            }
        }
        return result
    }

    override fun copy(): YType = YMap()

    override fun callObserver(transaction: Transaction, parentSubs: Set<String?>) {
        val event = YEvent(this, transaction, parentSubs)
        eventHandler.callListeners(Pair(event, transaction))
        propagateDeepEvent(this, transaction, event)
    }
}

/** Set a map entry. */
fun typeMapSet(transaction: Transaction, parent: YType, key: String, value: Any?) {
    val content: AbstractContent = when (value) {
        is YType -> ContentType(value)
        is Doc -> ContentDoc(value)
        is ByteArray -> ContentBinary(value)
        else -> ContentAny(mutableListOf(value))
    }
    val left = parent.map[key]
    val id = transaction.nextID()
    val item = Item(
        id = id,
        left = left,
        origin = left?.lastId,
        right = null,
        rightOrigin = null,
        parent = parent,
        parentSub = key,
        content = content
    )
    item.integrate(transaction, 0)
}

/** Get a map value. */
fun typeMapGet(parent: YType, key: String): Any? {
    val item = parent.map[key]
    if (item != null && !item.deleted) {
        return item.content.getContent().lastOrNull()
    }
    return null
}

/** Delete a map entry. */
fun typeMapDelete(transaction: Transaction, parent: YType, key: String) {
    val item = parent.map[key]
    if (item != null && !item.deleted) {
        item.delete(transaction)
    }
}

/** Check if map has a non-deleted entry. */
fun typeMapHas(parent: YType, key: String): Boolean {
    val item = parent.map[key]
    return item != null && !item.deleted
}

/** Get all non-deleted map entries. */
fun typeMapGetAll(parent: YType): Map<String, Any?> {
    val result = mutableMapOf<String, Any?>()
    for ((key, item) in parent.map) {
        if (!item.deleted) {
            result[key] = item.content.getContent().lastOrNull()
        }
    }
    return result
}
