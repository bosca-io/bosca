package yks.utils

import yks.structs.AbstractStruct
import yks.structs.GC
import yks.structs.Item
import yks.structs.Skip

/**
 * Stores all CRDT structs organized by client ID.
 * Each client's structs are in a sorted array with continuous clock values.
 * Matches yjs StructStore.
 */
class StructStore {
    /** Map from client ID to sorted array of structs. */
    val clients: MutableMap<Int, MutableList<AbstractStruct>> = mutableMapOf()

    /** Pending structs waiting for missing dependencies. */
    var pendingStructs: PendingStructs? = null

    /** Pending delete set. */
    var pendingDs: ByteArray? = null
}

data class PendingStructs(
    val missing: MutableMap<Int, Int>,
    val update: ByteArray
)

/** Get the next expected clock for a client. */
fun getState(store: StructStore, client: Int): Int {
    val structs = store.clients[client]
    if (structs == null || structs.isEmpty()) return 0
    val last = structs.last()
    return last.id.clock + last.length
}

/** Get the state vector (client → next expected clock). */
fun getStateVector(store: StructStore): Map<Int, Int> {
    val sv = mutableMapOf<Int, Int>()
    for ((client, structs) in store.clients) {
        if (structs.isNotEmpty()) {
            val last = structs.last()
            sv[client] = last.id.clock + last.length
        }
    }
    return sv
}

/**
 * Binary search for the struct containing the given clock value.
 * Returns the index in the client's struct array.
 * Matches yjs findIndexSS.
 */
fun findIndexSS(structs: List<AbstractStruct>, clock: Int): Int {
    var lo = 0
    var hi = structs.size - 1
    // Proportional pivot for better average performance
    val mid = structs[hi]
    val midClock = mid.id.clock + mid.length - 1
    if (midClock == 0) {
        return 0
    }
    var pivot = minOf(hi, maxOf(0, (clock.toLong() * hi / midClock).toInt()))
    var pivotStruct = structs[pivot]

    // Refine pivot
    while (pivotStruct.id.clock <= clock) {
        if (pivot == hi || structs[pivot + 1].id.clock > clock) {
            return pivot
        }
        lo = pivot + 1
        pivot = lo + ((hi - lo) / 2)
        pivotStruct = structs[pivot]
    }
    hi = pivot - 1
    // Standard binary search
    while (lo <= hi) {
        val m = (lo + hi) / 2
        val s = structs[m]
        if (s.id.clock + s.length <= clock) {
            lo = m + 1
        } else if (s.id.clock > clock) {
            hi = m - 1
        } else {
            return m
        }
    }
    throw IllegalStateException("StructStore: clock $clock not found")
}

/** Find a struct by its ID. */
fun find(store: StructStore, id: ID): AbstractStruct {
    val structs = store.clients[id.client]
        ?: throw IllegalStateException("StructStore: client ${id.client} not found")
    return structs[findIndexSS(structs, id.clock)]
}

/** Get an item by ID (convenience alias). */
fun getItem(store: StructStore, id: ID): AbstractStruct = find(store, id)

/**
 * Get the item that starts at exactly the given clock.
 * If the clock falls in the middle of an item, split it first.
 */
fun getItemCleanStart(transaction: Transaction, id: ID): Item? {
    val structs = transaction.doc.store.clients[id.client]
        ?: throw IllegalStateException("Client ${id.client} not found")
    val index = findIndexSS(structs, id.clock)
    val struct = structs[index]
    if (struct.id.clock < id.clock && struct is Item) {
        // Need to split
        val diff = id.clock - struct.id.clock
        val right = Item(
            id = ID(struct.id.client, struct.id.clock + diff),
            left = struct,
            origin = ID(struct.id.client, struct.id.clock + diff - 1),
            right = struct.right,
            rightOrigin = struct.rightOrigin,
            parent = struct.parent,
            parentSub = struct.parentSub,
            content = struct.content.splice(diff)
        )
        right.info = struct.info
        struct.right = right
        if (right.right != null) {
            right.right!!.left = right
        }
        struct.length = diff
        structs.add(index + 1, right)
        // Add to merge set for cleanup
        transaction.mergeStructs.add(right)
        return right
    }
    return struct as? Item
}

/**
 * Get the item that ends at exactly the given clock.
 * If the clock falls in the middle, split and return the left portion.
 */
fun getItemCleanEnd(transaction: Transaction, store: StructStore, id: ID): Item? {
    val structs = store.clients[id.client]
        ?: throw IllegalStateException("Client ${id.client} not found")
    val index = findIndexSS(structs, id.clock)
    val struct = structs[index]
    if (struct is Item && id.clock != struct.id.clock + struct.length - 1) {
        val diff = id.clock - struct.id.clock + 1
        val right = Item(
            id = ID(struct.id.client, struct.id.clock + diff),
            left = struct,
            origin = ID(struct.id.client, id.clock),
            right = struct.right,
            rightOrigin = struct.rightOrigin,
            parent = struct.parent,
            parentSub = struct.parentSub,
            content = struct.content.splice(diff)
        )
        right.info = struct.info
        struct.right = right
        if (right.right != null) {
            right.right!!.left = right
        }
        struct.length = diff
        structs.add(index + 1, right)
        transaction.mergeStructs.add(right)
    }
    return struct as? Item
}

/** Add a struct to the store. Appends to the client's array. */
fun addStruct(store: StructStore, struct: AbstractStruct) {
    val structs = store.clients.getOrPut(struct.id.client) { mutableListOf() }
    if (structs.isEmpty() || structs.last().id.clock + structs.last().length == struct.id.clock) {
        structs.add(struct)
    } else {
        // Insert in correct position (for Skip replacement)
        val index = findIndexSS(structs, struct.id.clock)
        val existing = structs[index]
        if (existing is Skip) {
            val diffStart = struct.id.clock - existing.id.clock
            val diffEnd = existing.id.clock + existing.length - struct.id.clock - struct.length
            if (diffStart > 0) {
                structs.add(index, Skip(existing.id, diffStart))
            }
            if (diffEnd > 0) {
                structs.add(
                    index + (if (diffStart > 0) 2 else 1),
                    Skip(ID(existing.id.client, struct.id.clock + struct.length), diffEnd)
                )
            }
            structs[index + (if (diffStart > 0) 1 else 0)] = struct
        } else {
            structs.add(struct)
        }
    }
}

/** Replace a struct in the store. */
fun replaceStruct(store: StructStore, oldStruct: AbstractStruct, newStruct: AbstractStruct) {
    val structs = store.clients[oldStruct.id.client] ?: return
    val index = findIndexSS(structs, oldStruct.id.clock)
    structs[index] = newStruct
}

/** Iterate structs in a clock range. */
fun iterateStructs(
    transaction: Transaction,
    structs: List<AbstractStruct>,
    clockStart: Int,
    length: Int,
    action: (AbstractStruct) -> Unit
) {
    if (length == 0 || structs.isEmpty()) return
    val clockEnd = clockStart + length
    var index = findIndexSS(structs, clockStart)
    var struct = structs[index]
    if (struct.id.clock < clockStart) {
        index++
        if (index >= structs.size) return
        struct = structs[index]
    }
    while (index < structs.size) {
        struct = structs[index]
        if (struct.id.clock >= clockEnd) break
        action(struct)
        index++
    }
}
