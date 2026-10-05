package yks.structs.content

import yks.structs.Item
import yks.utils.Transaction
import yks.utils.UpdateDecoder
import yks.utils.UpdateEncoder

/**
 * Content holding a text formatting attribute (key/value).
 * Ref = 6. Length = 1. NOT countable (doesn't contribute to text index).
 * Matches yjs ContentFormat.
 */
class ContentFormat(val key: String, val value: Any?) : AbstractContent {
    override fun getLength(): Int = 1
    override fun getContent(): List<Any?> = emptyList()
    override fun isCountable(): Boolean = false
    override fun copy(): AbstractContent = ContentFormat(key, value)

    override fun splice(offset: Int): AbstractContent {
        throw UnsupportedOperationException("ContentFormat cannot be split")
    }

    override fun mergeWith(right: AbstractContent): Boolean = false

    override fun integrate(transaction: Transaction, item: Item) {
        // Mark the parent as having formatting, which disables search markers
        val parent = item.parent
        if (parent is yks.types.YType) {
            parent.hasFormatting = true
            parent.searchMarker = null
        }
    }

    override fun delete(transaction: Transaction) {}
    override fun gc(transaction: Transaction) {}

    override fun write(encoder: UpdateEncoder, offset: Int) {
        encoder.writeKey(key)
        // Match yjs: value is coerced to string via JavaScript's implicit toString
        encoder.writeJSON(value?.toString() ?: "null")
    }

    override fun getRef(): Int = 6

    companion object {
        fun read(decoder: UpdateDecoder): ContentFormat {
            val key = decoder.readKey()
            val rawValue = decoder.readJSON()
            // Parse back common types to match yjs behavior for local format comparison
            val value: Any? = when (rawValue) {
                "null" -> null
                "true" -> true
                "false" -> false
                else -> rawValue
            }
            return ContentFormat(key, value)
        }
    }
}
