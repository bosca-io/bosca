package yks.utils

import yks.lib0.Observable
import yks.lib0.randomUint32
import yks.lib0.randomUuid
import yks.structs.Item
import yks.types.*

/**
 * The root shared document. Contains all shared types and the struct store.
 * Matches yjs Doc.
 */
class Doc(
    /** Garbage collection enabled. */
    val gc: Boolean = true,
    /** Custom GC filter. */
    val gcFilter: (Item) -> Boolean = { true },
    /** Globally unique document identifier. */
    val guid: String = randomUuid(),
    val collectionid: String? = null,
    val meta: Any? = null,
    val autoLoad: Boolean = false,
    val shouldLoad: Boolean = true,
    val opts: Any? = null
) : Observable() {
    /** Unique client ID (changes if collision detected). */
    var clientID: Int = randomUint32()
        internal set

    /** Named shared types. */
    val share: MutableMap<String, YType> = mutableMapOf()

    /** The struct store holding all CRDT structs. */
    val store = StructStore()

    /** Current active transaction (null if outside transaction). */
    var transaction: Transaction? = null
        internal set

    /** Queue of transactions being cleaned up. */
    val transactionCleanups = mutableListOf<Transaction>()

    /** Set of subdocuments. */
    val subdocs = mutableSetOf<Doc>()

    /** If this is a subdoc, the Item containing it. */
    var item: Item? = null
        internal set

    /** Whether this is a suggestion document. */
    val isSuggestionDoc: Boolean = false

    /** Whether to clean up formatting after transactions. */
    val cleanupFormatting: Boolean = !isSuggestionDoc

    var isLoaded: Boolean = false
        internal set

    var isSynced: Boolean = false
        internal set

    var isDestroyed: Boolean = false
        private set

    /**
     * Get or create a named shared type.
     * Matches yjs doc.get().
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : YType> get(name: String, typeClass: kotlin.reflect.KClass<T>): T {
        val existing = share[name]
        if (existing != null) {
            if (typeClass.isInstance(existing)) {
                return existing as T
            }
            // Matching yjs behavior: if the existing type was auto-created during update
            // application (e.g. as YArray for ContentDeleted items) but the caller requests
            // a different type, replace it by copying all items and updating parent refs.
            if (existing.isAutoCreated) {
                val replacement = createType(typeClass)
                replacement.map.putAll(existing.map)
                existing.map.forEach { (_, item) ->
                    var n: Item? = item
                    while (n != null) {
                        n.parent = replacement
                        n = n.left
                    }
                }
                replacement.start = existing.start
                var n = replacement.start
                while (n != null) {
                    n.parent = replacement
                    n = n.right
                }
                replacement.length = existing.length
                replacement.doc = this
                share[name] = replacement
                replacement.integrate(this, null)
                return replacement
            }
            throw IllegalStateException(
                "Type mismatch for '$name': expected ${typeClass.simpleName}, got ${existing::class.simpleName}"
            )
        }
        val type = createType(typeClass)
        type.integrate(this, null)
        share[name] = type
        return type
    }

    /** Convenience methods for getting specific shared types. */
    fun getArray(name: String = ""): YArray = get(name, YArray::class)
    fun getMap(name: String = ""): YMap = get(name, YMap::class)
    fun getText(name: String = ""): YText = get(name, YText::class)
    fun getXmlFragment(name: String = ""): YXmlFragment = get(name, YXmlFragment::class)
    fun getXmlElement(name: String, tag: String = "undefined"): YXmlElement {
        val existing = share[name]
        if (existing != null && existing is YXmlElement) return existing
        val type = YXmlElement(tag)
        type.integrate(this, null)
        share[name] = type
        return type
    }

    /**
     * Execute a function in a transaction.
     */
    fun <T> transact(origin: Any? = null, local: Boolean = true, body: (Transaction) -> T): T {
        return transact(this, origin, local, body)
    }

    /** Trigger subdoc loading. */
    fun load() {
        val parentItem = item
        if (parentItem != null && !isLoaded) {
            val parentDoc = (parentItem.parent as? YType)?.doc
            if (parentDoc != null) {
                transact(parentDoc, null, false) { transaction ->
                    transaction.subdocsLoaded.add(this)
                }
            }
        }
        isLoaded = true
        emit("load", this)
    }

    /** Convert all shared types to JSON. */
    fun toJSON(): Map<String, Any?> {
        val result = mutableMapOf<String, Any?>()
        for ((key, type) in share) {
            result[key] = type.toJSON()
        }
        return result
    }

    override fun destroy() {
        isDestroyed = true
        for (subdoc in subdocs.toList()) {
            subdoc.destroy()
        }
        val parentItem = item
        if (parentItem != null) {
            this.item = null
            val content = parentItem.content
            if (content is yks.structs.content.ContentDoc) {
                val newDoc = Doc(
                    gc = gc,
                    guid = guid,
                    collectionid = collectionid,
                    meta = meta,
                    autoLoad = autoLoad,
                    shouldLoad = false
                )
                content.doc = newDoc
                newDoc.item = parentItem
                val parentDoc = (parentItem.parent as? YType)?.doc
                if (parentDoc != null) {
                    transact(parentDoc, null, false) { transaction ->
                        if (!parentItem.deleted) {
                            transaction.subdocsAdded.add(newDoc)
                        }
                        transaction.subdocsRemoved.add(this)
                    }
                }
            }
        }
        emit("destroy", this)
        super.destroy()
    }

    companion object {
        @Suppress("UNCHECKED_CAST")
        private fun <T : YType> createType(typeClass: kotlin.reflect.KClass<T>): T {
            return when (typeClass) {
                YArray::class -> YArray() as T
                YMap::class -> YMap() as T
                YText::class -> YText() as T
                YXmlFragment::class -> YXmlFragment() as T
                YXmlElement::class -> YXmlElement("undefined") as T
                YXmlText::class -> YXmlText() as T
                YXmlHook::class -> YXmlHook("undefined") as T
                else -> throw IllegalStateException("Unknown YType: ${typeClass.simpleName}")
            }
        }
    }
}
