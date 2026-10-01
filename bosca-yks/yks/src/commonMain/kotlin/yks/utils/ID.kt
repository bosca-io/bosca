package yks.utils

import yks.lib0.Decoder
import yks.lib0.Encoder

/**
 * Lamport timestamp identifier.
 * Each CRDT operation is uniquely identified by (client, clock).
 * Matches yjs src/utils/ID.js.
 */
data class ID(val client: Int, val clock: Int) {
    override fun toString(): String = "ID($client, $clock)"
}

fun createID(client: Int, clock: Int): ID = ID(client, clock)

fun compareIDs(a: ID?, b: ID?): Boolean {
    if (a === b) return true
    if (a == null || b == null) return false
    return a.client == b.client && a.clock == b.clock
}

fun writeID(encoder: Encoder, id: ID) {
    encoder.writeVarUint(id.client)
    encoder.writeVarUint(id.clock)
}

fun readID(decoder: Decoder): ID {
    val client = decoder.readVarUint()
    val clock = decoder.readVarUint()
    return ID(client, clock)
}

/**
 * Find the root type key for a given ID by searching the doc's share map.
 */
fun findRootTypeKey(item: yks.types.YType): String {
    val doc = item.doc ?: throw IllegalStateException("YType not integrated into doc")
    for ((key, value) in doc.share) {
        if (value === item) return key
    }
    throw IllegalStateException("YType not found in doc.share")
}
