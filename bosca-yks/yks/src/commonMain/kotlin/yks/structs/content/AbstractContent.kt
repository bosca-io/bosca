package yks.structs.content

import yks.structs.Item
import yks.utils.Transaction
import yks.utils.UpdateEncoder

/**
 * Sealed interface for all content types stored in an Item.
 * Matches yjs AbstractContent with ref values 1-9.
 */
sealed interface AbstractContent {
    /** Number of "positions" this content occupies. */
    fun getLength(): Int

    /** Actual values as a list. */
    fun getContent(): List<Any?>

    /** Whether this content is counted toward the parent's length/index. */
    fun isCountable(): Boolean

    /** Deep copy of this content. */
    fun copy(): AbstractContent

    /** Split at offset, return the right portion. Modifies this to be the left portion. */
    fun splice(offset: Int): AbstractContent

    /** Attempt to merge with right neighbor. Returns true if merged (right can be discarded). */
    fun mergeWith(right: AbstractContent): Boolean

    /** Called when the containing Item is integrated into the document. */
    fun integrate(transaction: Transaction, item: Item)

    /** Called when the containing Item is deleted. */
    fun delete(transaction: Transaction)

    /** Called when the containing Item is garbage-collected. */
    fun gc(transaction: Transaction)

    /** Write this content to the encoder. */
    fun write(encoder: UpdateEncoder, offset: Int)

    /** Content type reference number (used in binary encoding). */
    fun getRef(): Int
}

/**
 * Read content from a decoder based on the ref number.
 */
fun readContent(ref: Int, decoder: yks.utils.UpdateDecoder): AbstractContent {
    return when (ref) {
        1 -> ContentDeleted.read(decoder)
        2 -> ContentJSON.read(decoder)
        3 -> ContentBinary.read(decoder)
        4 -> ContentString.read(decoder)
        5 -> ContentEmbed.read(decoder)
        6 -> ContentFormat.read(decoder)
        7 -> ContentType.read(decoder)
        8 -> ContentAny.read(decoder)
        9 -> ContentDoc.read(decoder)
        else -> throw IllegalStateException("Unknown content ref: $ref")
    }
}
