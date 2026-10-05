package yks.structs

import yks.utils.ID
import yks.utils.StructStore
import yks.utils.Transaction
import yks.utils.UpdateEncoder

/**
 * Base class for all CRDT structs (Item, GC, Skip).
 * Matches yjs AbstractStruct.
 */
sealed class AbstractStruct(
    id: ID,
    length: Int
) {
    var id: ID = id
        internal set

    var length: Int = length
        internal set

    abstract val deleted: Boolean

    /** Integrate this struct into the document. */
    abstract fun integrate(transaction: Transaction, offset: Int)

    /** Try to merge with the right neighbor. Returns true if successful. */
    abstract fun mergeWith(right: AbstractStruct): Boolean

    /** Write this struct to the encoder. */
    abstract fun write(encoder: UpdateEncoder, offset: Int, offsetEnd: Int)

    /**
     * Check for missing dependencies.
     * Returns the client ID of a missing dependency, or null if all deps are present.
     */
    abstract fun getMissing(transaction: Transaction, store: StructStore): Int?

    /** The last ID covered by this struct. */
    val lastId: ID get() = ID(id.client, id.clock + length - 1)
}
