package yks.utils

import yks.lib0.Decoder
import yks.lib0.Encoder
import yks.structs.Item
import yks.types.YType

/**
 * A position that remains stable under concurrent edits.
 * Encoded as a reference to a nearby item, surviving insertions and deletions.
 * Matches yjs RelativePosition.
 */
data class RelativePosition(
    /** ID of the parent YType's item (null if root type). */
    val type: ID?,
    /** Root type key (for root-level types). */
    val tname: String?,
    /** ID of the item this position is associated with. */
    val item: ID?,
    /** <0 = left-associative, >=0 = right-associative. */
    val assoc: Int = 0
)

/**
 * Resolved absolute position in a document.
 */
data class AbsolutePosition(
    val type: YType,
    val index: Int,
    val assoc: Int = 0
)

/**
 * Create a relative position from a type index.
 */
fun createRelativePositionFromTypeIndex(
    type: YType,
    index: Int,
    assoc: Int = 0
): RelativePosition {
    val typeItem = type.item
    val typeId = typeItem?.id
    val tname = if (typeItem == null) {
        findRootTypeKey(type)
    } else null

    var idx = index
    var t = type.start

    if (assoc < 0) {
        // Left-associated: we want the position after item at (index-1)
        if (idx == 0) {
            return RelativePosition(typeId, tname, null, assoc)
        }
        idx--
    }

    while (t != null) {
        if (!t.deleted && t.countable) {
            if (t.length > idx) {
                // Found position within this item
                return RelativePosition(typeId, tname, ID(t.id.client, t.id.clock + idx), assoc)
            }
            idx -= t.length
        }
        if (t.right == null && assoc < 0) {
            // Left-associated and at end of list: return last item
            return RelativePosition(typeId, tname, t.lastId, assoc)
        }
        t = t.right
    }

    // Past the end (or empty type)
    return RelativePosition(typeId, tname, null, assoc)
}

/**
 * Resolve a relative position to an absolute position in the given document.
 */
fun createAbsolutePositionFromRelativePosition(
    rpos: RelativePosition,
    doc: Doc
): AbsolutePosition? {
    val store = doc.store
    var type: YType?
    var index = 0

    if (rpos.item != null) {
        // Case 1: position references a specific item
        val itemId = rpos.item
        if (getState(store, itemId.client) <= itemId.clock) {
            return null
        }
        val right = try {
            find(store, itemId)
        } catch (_: Exception) {
            return null
        }
        if (right !is Item) return null

        // Get the type from the item's parent
        type = right.parent as? YType ?: return null
        val parentItem = type.item
        if (parentItem != null && parentItem.deleted) {
            // Parent type is deleted — still compute but type ref stays
        }

        // Compute index by counting left siblings
        // Handle the case where the item ID refers to a position within a split item
        val diff = itemId.clock - right.id.clock
        if (!right.deleted && right.countable) {
            // assoc >= 0 (right): position is before this char -> index = diff
            // assoc < 0 (left): position is after this char -> index = diff + 1
            index = diff + (if (rpos.assoc >= 0) 0 else 1)
        }
        var n = right.left
        while (n != null) {
            if (!n.deleted && n.countable) {
                index += n.length
            }
            n = n.left
        }
    } else {
        // Case 2: position at type boundary (no item)
        // assoc >= 0 (right-associated): position at end of type
        // assoc < 0 (left-associated): position at start of type
        type = resolveType(rpos, doc) ?: return null
        index = if (rpos.assoc >= 0) {
            type.length
        } else {
            0
        }
    }

    return AbsolutePosition(type, index, rpos.assoc)
}

private fun resolveType(rpos: RelativePosition, doc: Doc): YType? {
    if (rpos.tname != null) {
        return doc.share[rpos.tname]
    }
    if (rpos.type != null) {
        val struct = try {
            find(doc.store, rpos.type)
        } catch (_: Exception) {
            return null
        }
        if (struct is Item) {
            val content = struct.content
            if (content is yks.structs.content.ContentType) {
                return content.type
            }
        }
    }
    return null
}

fun compareRelativePositions(a: RelativePosition, b: RelativePosition): Boolean {
    return a == b
}

/**
 * Write a relative position to bytes.
 * Format matches yjs: single tag byte encodes item/tname/type,
 * followed by assoc as varInt.
 *
 * tag=0: item exists -> writeID(item)
 * tag=1: tname exists (position at type boundary, root type) -> writeVarString(tname)
 * tag=2: type ID exists (position at type boundary, non-root type) -> writeID(type)
 */
fun writeRelativePosition(encoder: Encoder, rpos: RelativePosition) {
    if (rpos.item != null) {
        encoder.writeVarUint(0)
        writeID(encoder, rpos.item)
    } else if (rpos.tname != null) {
        encoder.writeUint8(1)
        encoder.writeVarString(rpos.tname)
    } else if (rpos.type != null) {
        encoder.writeUint8(2)
        writeID(encoder, rpos.type)
    } else {
        throw IllegalStateException("Unexpected RelativePosition: no item, tname, or type")
    }
    encoder.writeVarInt(rpos.assoc)
}

/**
 * Read a relative position from bytes.
 * Format matches yjs: single tag determines which case.
 */
fun readRelativePosition(decoder: Decoder): RelativePosition {
    var type: ID? = null
    var tname: String? = null
    var item: ID? = null
    when (decoder.readVarUint()) {
        0 -> item = readID(decoder)
        1 -> tname = decoder.readVarString()
        2 -> type = readID(decoder)
    }
    val assoc = if (decoder.hasContent) decoder.readVarInt() else 0
    return RelativePosition(type, tname, item, assoc)
}

/** Encode a relative position to bytes. */
fun encodeRelativePosition(rpos: RelativePosition): ByteArray {
    val encoder = Encoder()
    writeRelativePosition(encoder, rpos)
    return encoder.toByteArray()
}

/** Decode a relative position from bytes. */
fun decodeRelativePosition(data: ByteArray): RelativePosition {
    return readRelativePosition(Decoder(data))
}
