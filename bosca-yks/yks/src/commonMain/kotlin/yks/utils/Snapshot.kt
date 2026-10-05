package yks.utils

import yks.lib0.Decoder
import yks.lib0.Encoder
import yks.structs.AbstractStruct
import yks.structs.Item

/**
 * Historical document state = delete set + state vector.
 * Matches yjs Snapshot.
 */
data class Snapshot(
    val ds: IdSet,
    val sv: Map<Int, Int>
)

/** Capture a snapshot of the current document state. */
fun snapshot(doc: Doc): Snapshot {
    return createSnapshot(
        createDeleteSetFromStructStore(doc.store),
        getStateVector(doc.store)
    )
}

fun createSnapshot(ds: IdSet, sv: Map<Int, Int>): Snapshot = Snapshot(ds, sv)

val emptySnapshot: Snapshot = Snapshot(IdSet(), emptyMap())

fun equalSnapshots(a: Snapshot, b: Snapshot): Boolean {
    if (a.sv.size != b.sv.size) return false
    for ((client, clock) in a.sv) {
        if (b.sv[client] != clock) return false
    }
    // Compare delete sets
    if (a.ds.clients.size != b.ds.clients.size) return false
    for ((client, rangesA) in a.ds.clients) {
        val rangesB = b.ds.clients[client] ?: return false
        val idsA = rangesA.getIds()
        val idsB = rangesB.getIds()
        if (idsA.size != idsB.size) return false
        for (i in idsA.indices) {
            if (idsA[i].clock != idsB[i].clock || idsA[i].len != idsB[i].len) return false
        }
    }
    return true
}

/** Encode a snapshot to bytes. */
fun encodeSnapshot(snap: Snapshot): ByteArray {
    val encoder = Encoder()
    writeIdSet(encoder, snap.ds)
    encoder.writeVarUint(snap.sv.size)
    for ((client, clock) in snap.sv) {
        encoder.writeVarUint(client)
        encoder.writeVarUint(clock)
    }
    return encoder.toByteArray()
}

/** Decode a snapshot from bytes. */
fun decodeSnapshot(data: ByteArray): Snapshot {
    val decoder = Decoder(data)
    val ds = readIdSet(decoder)
    val numClients = decoder.readVarUint()
    val sv = mutableMapOf<Int, Int>()
    repeat(numClients) {
        val client = decoder.readVarUint()
        val clock = decoder.readVarUint()
        sv[client] = clock
    }
    return Snapshot(ds, sv)
}

/**
 * Check if an item is visible in a snapshot.
 * An item is visible if:
 * 1. Its clock is within the snapshot's state vector
 * 2. It is NOT in the snapshot's delete set
 */
fun isVisible(item: Item, snap: Snapshot): Boolean {
    val clientClock = snap.sv[item.id.client] ?: return false
    if (item.id.clock >= clientClock) return false
    return !snap.ds.has(item.id.client, item.id.clock)
}

/**
 * Create a document from a snapshot.
 * The resulting document reflects the state at the time of the snapshot.
 */
fun createDocFromSnapshot(originDoc: Doc, snap: Snapshot, newDoc: Doc = Doc()): Doc {
    // Encode only structs within the snapshot's state vector
    val encoder = UpdateEncoderV1()
    val store = originDoc.store

    val clientsToWrite = mutableListOf<Pair<Int, MutableList<AbstractStruct>>>()
    for ((client, clock) in snap.sv) {
        val structs = store.clients[client]
        if (structs != null && structs.isNotEmpty()) {
            clientsToWrite.add(Pair(client, structs))
        }
    }

    encoder.restEncoder.writeVarUint(clientsToWrite.size)
    for ((client, structs) in clientsToWrite.sortedByDescending { it.first }) {
        val maxClock = snap.sv[client] ?: 0
        val count = structs.count { it.id.clock < maxClock }
        encoder.restEncoder.writeVarUint(count)
        encoder.restEncoder.writeVarUint(client)
        encoder.restEncoder.writeVarUint(0) // start clock

        for (struct in structs) {
            if (struct.id.clock >= maxClock) break
            struct.write(encoder, 0, maxOf(0, struct.id.clock + struct.length - maxClock))
        }
    }

    // Write the snapshot's delete set
    writeDeleteSet(encoder, snap.ds)

    applyUpdate(newDoc, encoder.toByteArray())
    return newDoc
}
