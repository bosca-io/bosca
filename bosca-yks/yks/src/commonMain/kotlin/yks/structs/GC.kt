package yks.structs

import yks.utils.*

/**
 * Garbage-collected tombstone.
 * Replaces deleted Items after GC runs. Has no content, just an ID range.
 * Ref = 0. Always deleted.
 * Matches yjs GC.
 */
class GC(id: ID, length: Int) : AbstractStruct(id, length) {
    override val deleted: Boolean = true

    override fun integrate(transaction: Transaction, offset: Int) {
        if (offset > 0) {
            id = ID(id.client, id.clock + offset)
            length -= offset
        }
        transaction.addToDeleteSet(id.client, id.clock, length)
        addStruct(transaction.doc.store, this)
    }

    override fun mergeWith(right: AbstractStruct): Boolean {
        if (right !is GC) return false
        length += right.length
        return true
    }

    override fun write(encoder: UpdateEncoder, offset: Int, offsetEnd: Int) {
        encoder.writeInfo(0) // GC ref = 0
        encoder.writeLen(length - offset - offsetEnd)
    }

    override fun getMissing(transaction: Transaction, store: StructStore): Int? = null

    /** Split: keep [0..diff), return [diff..length). */
    fun splice(diff: Int): GC {
        val right = GC(ID(id.client, id.clock + diff), length - diff)
        length = diff
        return right
    }
}
