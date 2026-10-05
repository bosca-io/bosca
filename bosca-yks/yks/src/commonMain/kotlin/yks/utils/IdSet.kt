package yks.utils

import yks.lib0.Decoder
import yks.lib0.Encoder

/**
 * A range of IDs for a single client: [clock, clock + len).
 */
data class IdRange(var clock: Int, var len: Int)

/**
 * Set of ID ranges for a single client.
 * Ranges may overlap; they are sorted and merged on read via getIds().
 */
class IdRanges {
    private val ids = mutableListOf<IdRange>()
    private var sorted = true

    fun add(clock: Int, length: Int) {
        if (ids.isNotEmpty()) {
            val last = ids.last()
            if (last.clock + last.len == clock) {
                // Extend last range
                last.len += length
                return
            }
            if (last.clock + last.len > clock) {
                sorted = false
            }
        }
        ids.add(IdRange(clock, length))
    }

    /** Get sorted, merged ranges. */
    fun getIds(): List<IdRange> {
        if (!sorted) {
            ids.sortBy { it.clock }
            // Merge overlapping
            var i = 1
            while (i < ids.size) {
                val prev = ids[i - 1]
                val curr = ids[i]
                if (prev.clock + prev.len >= curr.clock) {
                    prev.len = maxOf(prev.len, curr.clock + curr.len - prev.clock)
                    ids.removeAt(i)
                } else {
                    i++
                }
            }
            sorted = true
        }
        return ids
    }

    fun has(clock: Int): Boolean {
        val ranges = getIds()
        // Binary search
        var lo = 0
        var hi = ranges.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            val r = ranges[mid]
            when {
                r.clock > clock -> hi = mid - 1
                r.clock + r.len <= clock -> lo = mid + 1
                else -> return true
            }
        }
        return false
    }

    fun isEmpty(): Boolean = ids.isEmpty()

    fun forEach(action: (IdRange) -> Unit) {
        for (r in getIds()) action(r)
    }
}

/**
 * Set of ID ranges across all clients.
 * Used as delete set, insert set, and skip set.
 * Matches yjs IdSet.
 */
class IdSet {
    val clients: MutableMap<Int, IdRanges> = mutableMapOf()

    fun add(client: Int, clock: Int, length: Int) {
        clients.getOrPut(client) { IdRanges() }.add(clock, length)
    }

    fun has(client: Int, clock: Int): Boolean {
        return clients[client]?.has(clock) == true
    }

    fun hasId(id: ID): Boolean = has(id.client, id.clock)

    fun forEach(action: (client: Int, ranges: IdRanges) -> Unit) {
        for ((client, ranges) in clients) {
            action(client, ranges)
        }
    }

    fun isEmpty(): Boolean = clients.isEmpty() || clients.all { it.value.isEmpty() }
}

/** Add a struct's ID range to the IdSet. */
fun addStructToIdSet(set: IdSet, struct: yks.structs.AbstractStruct) {
    set.add(struct.id.client, struct.id.clock, struct.length)
}

/** Write an IdSet using V1 encoding. */
fun writeIdSet(encoder: Encoder, set: IdSet) {
    val clients = set.clients.entries.toList()
    encoder.writeVarUint(clients.size)
    for ((client, ranges) in clients) {
        encoder.writeVarUint(client)
        val ids = ranges.getIds()
        encoder.writeVarUint(ids.size)
        for (r in ids) {
            encoder.writeVarUint(r.clock)
            encoder.writeVarUint(r.len)
        }
    }
}

/** Read an IdSet using V1 encoding. */
fun readIdSet(decoder: Decoder): IdSet {
    val set = IdSet()
    val numClients = decoder.readVarUint()
    repeat(numClients) {
        val client = decoder.readVarUint()
        val numRanges = decoder.readVarUint()
        repeat(numRanges) {
            val clock = decoder.readVarUint()
            val len = decoder.readVarUint()
            set.add(client, clock, len)
        }
    }
    return set
}

/** Create a delete set from a StructStore (all deleted items). */
fun createDeleteSetFromStructStore(store: StructStore): IdSet {
    val ds = IdSet()
    for ((client, structs) in store.clients) {
        for (struct in structs) {
            if (struct.deleted) {
                ds.add(client, struct.id.clock, struct.length)
            }
        }
    }
    return ds
}

/** Merge multiple IdSets into one. */
fun mergeIdSets(sets: List<IdSet>): IdSet {
    val result = IdSet()
    for (set in sets) {
        set.forEach { client, ranges ->
            ranges.forEach { range ->
                result.add(client, range.clock, range.len)
            }
        }
    }
    return result
}
