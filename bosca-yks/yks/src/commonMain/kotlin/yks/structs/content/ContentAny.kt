package yks.structs.content

import yks.structs.Item
import yks.utils.Transaction
import yks.utils.UpdateDecoder
import yks.utils.UpdateEncoder

/**
 * Content holding an array of arbitrary values.
 * Ref = 8. Length = array size. Countable. Mergeable.
 * Matches yjs ContentAny.
 */
class ContentAny(var values: MutableList<Any?>) : AbstractContent {
    override fun getLength(): Int = values.size
    override fun getContent(): List<Any?> = values
    override fun isCountable(): Boolean = true
    override fun copy(): AbstractContent = ContentAny(values.toMutableList())

    override fun splice(offset: Int): AbstractContent {
        val right = ContentAny(values.subList(offset, values.size).toMutableList())
        values = values.subList(0, offset).toMutableList()
        return right
    }

    override fun mergeWith(right: AbstractContent): Boolean {
        if (right !is ContentAny) return false
        values.addAll(right.values)
        return true
    }

    override fun integrate(transaction: Transaction, item: Item) {}
    override fun delete(transaction: Transaction) {}
    override fun gc(transaction: Transaction) {}

    override fun write(encoder: UpdateEncoder, offset: Int) {
        val len = values.size - offset
        encoder.writeLen(len)
        for (i in offset until values.size) {
            encoder.writeAny(values[i])
        }
    }

    override fun getRef(): Int = 8

    companion object {
        fun read(decoder: UpdateDecoder): ContentAny {
            val len = decoder.readLen()
            val values = mutableListOf<Any?>()
            repeat(len) {
                values.add(decoder.readAny())
            }
            return ContentAny(values)
        }
    }
}
