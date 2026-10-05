package yks.structs.content

import yks.structs.Item
import yks.utils.Transaction
import yks.utils.UpdateDecoder
import yks.utils.UpdateEncoder

/**
 * Content holding a binary byte array.
 * Ref = 3. Length = 1. Countable.
 * Matches yjs ContentBinary.
 */
class ContentBinary(val data: ByteArray) : AbstractContent {
    override fun getLength(): Int = 1
    override fun getContent(): List<Any?> = listOf(data)
    override fun isCountable(): Boolean = true
    override fun copy(): AbstractContent = ContentBinary(data.copyOf())

    override fun splice(offset: Int): AbstractContent {
        throw UnsupportedOperationException("ContentBinary cannot be split")
    }

    override fun mergeWith(right: AbstractContent): Boolean = false

    override fun integrate(transaction: Transaction, item: Item) {}
    override fun delete(transaction: Transaction) {}
    override fun gc(transaction: Transaction) {}

    override fun write(encoder: UpdateEncoder, offset: Int) {
        encoder.writeBuf(data)
    }

    override fun getRef(): Int = 3

    companion object {
        fun read(decoder: UpdateDecoder): ContentBinary {
            return ContentBinary(decoder.readBuf())
        }
    }
}
