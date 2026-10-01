package yks.types

import yks.structs.Item
import yks.structs.content.*
import yks.utils.*

/**
 * Shared text type with rich text formatting support.
 * Uses a linked list of ContentString and ContentFormat items.
 * Matches yjs Y.Text.
 */
open class YText(private var initialText: String? = null) : YType() {
    override val typeName: String = "Text"

    override fun integrate(doc: Doc, item: Item?) {
        super.integrate(doc, item)
        val text = initialText
        if (text != null && text.isNotEmpty()) {
            transact(doc) { transaction ->
                val content = ContentString(text)
                val id = transaction.nextID()
                val newItem = Item(
                    id = id,
                    left = null,
                    origin = null,
                    right = null,
                    rightOrigin = null,
                    parent = this,
                    parentSub = null,
                    content = content
                )
                newItem.integrate(transaction, 0)
            }
            initialText = null
        }
    }

    /** Insert text at the given index with optional formatting attributes. */
    fun insert(index: Int, text: String, attributes: Map<String, Any?>? = null) {
        val doc = doc ?: throw IllegalStateException("YText not integrated")
        transact(doc) { transaction ->
            val pos = findPosition(transaction, index)
            val attrs = attributes ?: emptyMap()
            minimizeAttributeChanges(pos, attrs)
            val negatedAttributes = insertAttributes(transaction, pos, attrs)
            insertText(transaction, pos, text)
            insertNegatedAttributes(transaction, pos, negatedAttributes)
        }
    }

    /** Delete [length] characters starting at [index]. */
    fun delete(index: Int, length: Int) {
        val doc = doc ?: throw IllegalStateException("YText not integrated")
        transact(doc) { transaction ->
            val pos = findPosition(transaction, index)
            deleteText(transaction, pos, length)
        }
    }

    /** Apply formatting to a range. */
    fun format(index: Int, length: Int, attributes: Map<String, Any?>) {
        val doc = doc ?: throw IllegalStateException("YText not integrated")
        transact(doc) { transaction ->
            val pos = findPosition(transaction, index)
            formatText(transaction, pos, length, attributes)
        }
    }

    /** Apply a delta (quill-like format). */
    fun applyDelta(delta: List<Map<String, Any?>>) {
        val doc = doc ?: throw IllegalStateException("YText not integrated")
        transact(doc) { transaction ->
            val pos = TextPosition(null, start, 0, mutableMapOf())
            for (op in delta) {
                when {
                    op.containsKey("insert") -> {
                        val text = op["insert"]
                        @Suppress("UNCHECKED_CAST")
                        val attrs = (op["attributes"] as? Map<String, Any?>) ?: emptyMap()
                        if (text is String) {
                            minimizeAttributeChanges(pos, attrs)
                            val negated = insertAttributes(transaction, pos, attrs)
                            insertText(transaction, pos, text)
                            insertNegatedAttributes(transaction, pos, negated)
                        }
                    }
                    op.containsKey("delete") -> {
                        val len = (op["delete"] as Number).toInt()
                        deleteText(transaction, pos, len)
                    }
                    op.containsKey("retain") -> {
                        val len = (op["retain"] as Number).toInt()
                        @Suppress("UNCHECKED_CAST")
                        val attrs = (op["attributes"] as? Map<String, Any?>) ?: emptyMap()
                        if (attrs.isNotEmpty()) {
                            formatText(transaction, pos, len, attrs)
                        } else {
                            // Simple retain — just advance the cursor
                            var remaining = len
                            while (remaining > 0 && pos.right != null) {
                                if (!pos.right!!.deleted) {
                                    if (pos.right!!.content is ContentFormat) {
                                        updateCurrentAttributes(pos.currentAttributes, pos.right!!.content as ContentFormat)
                                    } else {
                                        val itemLen = pos.right!!.length
                                        if (remaining < itemLen) {
                                            getItemCleanStart(transaction, ID(pos.right!!.id.client, pos.right!!.id.clock + remaining))
                                        }
                                        val consumed = minOf(remaining, pos.right!!.length)
                                        remaining -= consumed
                                        pos.index += consumed
                                    }
                                }
                                pos.left = pos.right
                                pos.right = pos.right!!.right
                            }
                        }
                    }
                }
            }
        }
    }

    /** Get the delta representation including formatting attributes. */
    fun toDelta(): List<Map<String, Any?>> {
        val deltas = mutableListOf<Map<String, Any?>>()
        val currentAttributes = mutableMapOf<String, Any?>()
        var item = start
        while (item != null) {
            if (!item.deleted) {
                when (val content = item.content) {
                    is ContentFormat -> {
                        updateCurrentAttributes(currentAttributes, content)
                    }
                    is ContentString -> {
                        val delta = mutableMapOf<String, Any?>("insert" to content.str)
                        if (currentAttributes.isNotEmpty()) {
                            delta["attributes"] = currentAttributes.toMap()
                        }
                        deltas.add(delta)
                    }
                    is ContentEmbed -> {
                        val delta = mutableMapOf<String, Any?>("insert" to content.embed)
                        if (currentAttributes.isNotEmpty()) {
                            delta["attributes"] = currentAttributes.toMap()
                        }
                        deltas.add(delta)
                    }
                    else -> {}
                }
            }
            item = item.right
        }
        return deltas
    }

    /** Get the text content as a plain string. */
    override fun toString(): String {
        val sb = StringBuilder()
        var item = start
        while (item != null) {
            if (!item.deleted && item.content is ContentString) {
                sb.append((item.content as ContentString).str)
            }
            item = item.right
        }
        return sb.toString()
    }

    override fun toJSON(): Any? = toString()

    override fun copy(): YType = YText()

    override fun callObserver(transaction: Transaction, parentSubs: Set<String?>) {
        val event = YEvent(this, transaction, parentSubs)
        eventHandler.callListeners(Pair(event, transaction))
        propagateDeepEvent(this, transaction, event)
    }

    // ── Internal cursor and formatting engine ──

    /** Mutable cursor position for text operations. */
    private class TextPosition(
        var left: Item?,
        var right: Item?,
        var index: Int,
        val currentAttributes: MutableMap<String, Any?>
    ) {
        fun forward() {
            val r = right ?: return
            if (!r.deleted && r.content is ContentFormat) {
                updateCurrentAttributes(currentAttributes, r.content as ContentFormat)
            } else if (!r.deleted && r.countable) {
                index += r.length
            }
            left = r
            right = r.right
        }
    }

    /** Create a cursor positioned at the given character index. */
    private fun findPosition(transaction: Transaction, index: Int): TextPosition {
        val pos = TextPosition(null, start, 0, mutableMapOf())
        var remaining = index
        while (remaining > 0 && pos.right != null) {
            val r = pos.right!!
            if (!r.deleted && r.countable) {
                if (remaining < r.length) {
                    // Split the item at the boundary
                    getItemCleanStart(transaction, ID(r.id.client, r.id.clock + remaining))
                    // After split, r.length is now == remaining
                }
                val consumed = minOf(remaining, r.length)
                remaining -= consumed
                pos.index += consumed
            } else if (!r.deleted && r.content is ContentFormat) {
                updateCurrentAttributes(pos.currentAttributes, r.content as ContentFormat)
            }
            pos.left = r
            pos.right = r.right
        }
        return pos
    }

    /**
     * Advance cursor past deleted items and format markers that already match
     * the desired attributes. Avoids inserting redundant format markers.
     * Matches yjs behavior: skips all deleted items and matching format markers.
     */
    private fun minimizeAttributeChanges(pos: TextPosition, attrs: Map<String, Any?>) {
        while (pos.right != null) {
            val r = pos.right!!
            if (r.deleted) {
                // Skip all deleted items
            } else if (r.content is ContentFormat) {
                val fmt = r.content as ContentFormat
                val desired = if (attrs.containsKey(fmt.key)) attrs[fmt.key] else null
                if (!equalAttrs(desired, fmt.value)) break
            } else {
                break
            }
            pos.forward()
        }
    }

    /**
     * Insert opening format markers for attributes that differ from current.
     * Returns the negated attributes map (old values to restore after the range).
     */
    private fun insertAttributes(
        transaction: Transaction,
        pos: TextPosition,
        attrs: Map<String, Any?>
    ): MutableMap<String, Any?> {
        val negated = mutableMapOf<String, Any?>()
        for ((key, value) in attrs) {
            val currentVal = pos.currentAttributes[key]
            if (!equalAttrs(currentVal, value)) {
                negated[key] = currentVal
                insertFormatItem(transaction, pos, key, value)
            }
        }
        return negated
    }

    /**
     * Insert closing format markers to undo the formatting applied at the start.
     * Skips insertion if existing markers already provide the negation.
     */
    private fun insertNegatedAttributes(
        transaction: Transaction,
        pos: TextPosition,
        negated: MutableMap<String, Any?>
    ) {
        // Skip past existing items that already negate what we need
        while (pos.right != null && (
                (pos.right!!.deleted && !pos.right!!.countable) ||
                (pos.right!!.content is ContentFormat &&
                    equalAttrs(negated[((pos.right!!.content) as ContentFormat).key],
                        (pos.right!!.content as ContentFormat).value))
            )) {
            if (!pos.right!!.deleted) {
                negated.remove((pos.right!!.content as ContentFormat).key)
            }
            pos.forward()
        }
        for ((key, value) in negated) {
            insertFormatItem(transaction, pos, key, value)
        }
    }

    /** Insert a single ContentFormat item at the cursor and advance. */
    private fun insertFormatItem(
        transaction: Transaction,
        pos: TextPosition,
        key: String,
        value: Any?
    ) {
        val id = transaction.nextID()
        val item = Item(
            id = id,
            left = pos.left,
            origin = pos.left?.lastId,
            right = pos.right,
            rightOrigin = pos.right?.id,
            parent = this,
            parentSub = null,
            content = ContentFormat(key, value)
        )
        item.integrate(transaction, 0)
        pos.right = item
        pos.forward()
    }

    /** Insert text content at the cursor position. */
    private fun insertText(transaction: Transaction, pos: TextPosition, text: String) {
        val id = transaction.nextID()
        val item = Item(
            id = id,
            left = pos.left,
            origin = pos.left?.lastId,
            right = pos.right,
            rightOrigin = pos.right?.id,
            parent = this,
            parentSub = null,
            content = ContentString(text)
        )
        item.integrate(transaction, 0)
        pos.right = item
        pos.forward()
    }

    /** Delete text content at the cursor position. */
    private fun deleteText(transaction: Transaction, pos: TextPosition, length: Int) {
        var remaining = length
        while (remaining > 0 && pos.right != null) {
            val r = pos.right!!
            if (!r.deleted) {
                if (r.content is ContentFormat) {
                    // Format markers are not counted, just skip
                    updateCurrentAttributes(pos.currentAttributes, r.content as ContentFormat)
                } else if (r.countable) {
                    if (remaining < r.length) {
                        getItemCleanStart(transaction, ID(r.id.client, r.id.clock + remaining))
                    }
                    val deleted = minOf(remaining, r.length)
                    r.delete(transaction)
                    remaining -= deleted
                    pos.index += deleted
                }
            }
            pos.left = r
            pos.right = r.right
        }
    }

    /**
     * Apply formatting attributes over [length] characters starting at cursor.
     * Matches yjs ItemTextListPosition.formatText().
     */
    private fun formatText(
        transaction: Transaction,
        pos: TextPosition,
        length: Int,
        attrs: Map<String, Any?>
    ) {
        minimizeAttributeChanges(pos, attrs)
        val negated = insertAttributes(transaction, pos, attrs)
        var remaining = length

        // Walk through content, deleting conflicting format markers
        while (pos.right != null && (remaining > 0 ||
                (negated.isNotEmpty() && (
                    (pos.right!!.deleted && !pos.right!!.countable) ||
                    pos.right!!.content is ContentFormat)))
        ) {
            val r = pos.right!!
            if (!r.deleted) {
                if (r.content is ContentFormat) {
                    val fmt = r.content as ContentFormat
                    val attr = if (attrs.containsKey(fmt.key)) attrs[fmt.key] else ATTR_ABSENT
                    if (attr !== ATTR_ABSENT) {
                        if (equalAttrs(attr, fmt.value)) {
                            negated.remove(fmt.key)
                        } else {
                            if (remaining == 0) break
                            negated[fmt.key] = fmt.value
                        }
                        r.delete(transaction)
                    } else {
                        updateCurrentAttributes(pos.currentAttributes, fmt)
                    }
                } else if (r.countable) {
                    if (remaining < r.length) {
                        getItemCleanStart(transaction, ID(r.id.client, r.id.clock + remaining))
                    }
                    val consumed = minOf(remaining, r.length)
                    remaining -= consumed
                    pos.index += consumed
                }
            }
            pos.left = r
            pos.right = r.right
        }

        insertNegatedAttributes(transaction, pos, negated)
    }

    companion object {
        /** Sentinel to distinguish "attribute not in map" from "attribute is null". */
        private val ATTR_ABSENT = Any()

        private fun updateCurrentAttributes(attrs: MutableMap<String, Any?>, fmt: ContentFormat) {
            if (fmt.value == null) {
                attrs.remove(fmt.key)
            } else {
                attrs[fmt.key] = fmt.value
            }
        }

        private fun equalAttrs(a: Any?, b: Any?): Boolean {
            if (a === b) return true
            if (a == null || b == null) return a == b
            if (a is Map<*, *> && b is Map<*, *>) {
                if (a.size != b.size) return false
                for ((k, v) in a) {
                    if (!b.containsKey(k) || v != b[k]) return false
                }
                return true
            }
            return a == b
        }
    }
}
