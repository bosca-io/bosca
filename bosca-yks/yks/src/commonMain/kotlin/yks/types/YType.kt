package yks.types

import yks.lib0.EventHandler
import yks.structs.Item
import yks.utils.Doc
import yks.utils.ID
import yks.utils.Transaction
import yks.utils.YEvent

/**
 * Abstract base class for all shared types (YArray, YMap, YText, etc.).
 * Matches yjs YType from src/ytype.js.
 *
 * Internally, a YType has two data structures:
 * - A doubly-linked list (via _start) for sequential/array content
 * - A map (via _map) for key-value content
 */
abstract class YType {
    /** The Item containing this type (null if root-level type in doc.share). */
    var item: Item? = null
        internal set

    /** Head of the doubly-linked list of items. */
    var start: Item? = null
        internal set

    /** Map from key to the most recent Item for that key. */
    val map: MutableMap<String, Item> = mutableMapOf()

    /** Cached count of non-deleted countable items. */
    var length: Int = 0
        internal set

    /** The document this type belongs to. */
    var doc: Doc? = null
        internal set

    /** Whether this type contains formatting items (ContentFormat). */
    var hasFormatting: Boolean = false
        internal set

    /** Search marker array for O(1)-amortized index access. */
    var searchMarker: MutableList<ArraySearchMarker>? = null
        internal set

    /**
     * True when this type was auto-created during update application (resolveParent)
     * rather than explicitly created by the user via doc.getArray/getMap/getText.
     * Auto-created types can be replaced by a different type class, matching yjs behavior
     * where a base AbstractType is replaced when the user requests a specific subclass.
     */
    var isAutoCreated: Boolean = false
        internal set

    /** Shallow event handler. */
    val eventHandler = EventHandler<Pair<YEvent, Transaction>>()

    /** Deep event handler. */
    val deepEventHandler = EventHandler<Pair<List<YEvent>, Transaction>>()

    /** Integrate this type into a document. */
    open fun integrate(doc: Doc, item: Item?) {
        this.doc = doc
        this.item = item
    }

    /** Returns the type name identifier used in binary encoding. */
    abstract val typeName: String

    /**
     * Called by the transaction system to create and fire events.
     */
    open fun callObserver(transaction: Transaction, parentSubs: Set<String?>) {
        // Default implementation — subclasses override to create specific events
    }

    /** Observe changes to this type (shallow). */
    fun observe(handler: (YEvent, Transaction) -> Unit): () -> Unit {
        return eventHandler.addListener { (event, tr) -> handler(event, tr) }
    }

    /** Observe changes to this type and all nested types (deep). */
    fun observeDeep(handler: (List<YEvent>, Transaction) -> Unit): () -> Unit {
        return deepEventHandler.addListener { (events, tr) -> handler(events, tr) }
    }

    /** Convert to JSON-serializable value. */
    abstract fun toJSON(): Any?

    /** Copy this type (for snapshot/clone operations). */
    abstract fun copy(): YType
}
