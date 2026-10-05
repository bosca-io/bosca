package yks.structs

import yks.utils.*

/**
 * Placeholder for missing clock ranges in the struct store.
 * Ref = 10. Never deleted.
 * Matches yjs Skip.
 */
class Skip(id: ID, length: Int) : AbstractStruct(id, length) {
    override val deleted: Boolean = false

    override fun integrate(transaction: Transaction, offset: Int) {
        // Skip doesn't actually integrate into the document tree.
        // It marks a gap in the struct sequence.
        throw UnsupportedOperationException("Skip should not be integrated normally")
    }

    override fun mergeWith(right: AbstractStruct): Boolean {
        if (right !is Skip) return false
        length += right.length
        return true
    }

    override fun write(encoder: UpdateEncoder, offset: Int, offsetEnd: Int) {
        encoder.writeInfo(10) // Skip ref = 10
        encoder.writeLen(length - offset)
    }

    override fun getMissing(transaction: Transaction, store: StructStore): Int? = null
}
