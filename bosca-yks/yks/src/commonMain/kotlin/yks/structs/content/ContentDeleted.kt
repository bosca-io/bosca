package yks.structs.content

import yks.structs.Item
import yks.utils.Transaction
import yks.utils.UpdateDecoder
import yks.utils.UpdateEncoder

/**
 * Content representing a deleted range.
 * Ref = 1. Not countable.
 * Matches yjs ContentDeleted.
 */
class ContentDeleted(var len: Int) : AbstractContent {
    override fun getLength(): Int = len
    override fun getContent(): List<Any?> = emptyList()
    override fun isCountable(): Boolean = false
    override fun copy(): AbstractContent = ContentDeleted(len)

    override fun splice(offset: Int): AbstractContent {
        val right = ContentDeleted(len - offset)
        len = offset
        return right
    }

    override fun mergeWith(right: AbstractContent): Boolean {
        if (right !is ContentDeleted) return false
        len += right.len
        return true
    }

    override fun integrate(transaction: Transaction, item: Item) {
        transaction.addToDeleteSet(item.id.client, item.id.clock, len)
        item.markDeleted()
    }

    override fun delete(transaction: Transaction) {}
    override fun gc(transaction: Transaction) {}

    override fun write(encoder: UpdateEncoder, offset: Int) {
        encoder.writeLen(len - offset)
    }

    override fun getRef(): Int = 1

    companion object {
        fun read(decoder: UpdateDecoder): ContentDeleted {
            return ContentDeleted(decoder.readLen())
        }
    }
}
