package yks.structs.content

import yks.structs.Item
import yks.utils.Transaction
import yks.utils.UpdateDecoder
import yks.utils.UpdateEncoder

/**
 * Content holding a string.
 * Ref = 4. Length = string length (in UTF-16 code points, matching JS).
 * Countable. Mergeable with adjacent ContentString items.
 * Matches yjs ContentString.
 */
class ContentString(var str: String) : AbstractContent {
    override fun getLength(): Int = str.length
    override fun getContent(): List<Any?> = str.map { it.toString() }
    override fun isCountable(): Boolean = true
    override fun copy(): AbstractContent = ContentString(str)

    override fun splice(offset: Int): AbstractContent {
        val right = ContentString(str.substring(offset))
        str = str.substring(0, offset)
        // Handle surrogate pair splitting
        if (str.isNotEmpty() && str.last().isHighSurrogate()) {
            right.str = str.last() + right.str
            str = str.substring(0, str.length - 1)
        }
        return right
    }

    override fun mergeWith(right: AbstractContent): Boolean {
        if (right !is ContentString) return false
        str += right.str
        return true
    }

    override fun integrate(transaction: Transaction, item: Item) {}
    override fun delete(transaction: Transaction) {}
    override fun gc(transaction: Transaction) {}

    override fun write(encoder: UpdateEncoder, offset: Int) {
        encoder.writeString(if (offset == 0) str else str.substring(offset))
    }

    override fun getRef(): Int = 4

    companion object {
        fun read(decoder: UpdateDecoder): ContentString {
            return ContentString(decoder.readString())
        }
    }
}
