package yks.utils

import yks.lib0.*
import yks.structs.*
import yks.structs.content.*
import yks.types.YType

/**
 * Core encoding/decoding functions for yjs updates.
 * Handles state vectors, update encoding/decoding, and struct integration.
 * Matches yjs src/utils/encoding.js.
 */

// --- State Vector ---

/** Encode a state vector to bytes (V1). */
fun encodeStateVector(doc: Doc): ByteArray {
    return encodeStateVectorFromMap(getStateVector(doc.store))
}

/** Encode a state vector from a map. */
fun encodeStateVectorFromMap(sv: Map<Int, Int>): ByteArray {
    val encoder = Encoder()
    encoder.writeVarUint(sv.size)
    for ((client, clock) in sv) {
        encoder.writeVarUint(client)
        encoder.writeVarUint(clock)
    }
    return encoder.toByteArray()
}

/** Decode a state vector from bytes. */
fun decodeStateVector(data: ByteArray): Map<Int, Int> {
    val decoder = Decoder(data)
    val sv = mutableMapOf<Int, Int>()
    val numClients = decoder.readVarUint()
    repeat(numClients) {
        val client = decoder.readVarUint()
        val clock = decoder.readVarUint()
        sv[client] = clock
    }
    return sv
}

// --- Update Encoding ---

/**
 * Encode the document state as an update relative to a target state vector.
 * This produces a V1 update that, when applied, brings the target up to date.
 */
fun encodeStateAsUpdate(doc: Doc, targetStateVector: ByteArray? = null): ByteArray {
    val sv = if (targetStateVector != null) decodeStateVector(targetStateVector) else emptyMap()
    return encodeStateAsUpdateFromSV(doc, sv, false)
}

/** Encode state as V2 update. */
fun encodeStateAsUpdateV2(doc: Doc, targetStateVector: ByteArray? = null): ByteArray {
    val sv = if (targetStateVector != null) decodeStateVector(targetStateVector) else emptyMap()
    return encodeStateAsUpdateFromSV(doc, sv, true)
}

internal fun encodeStateAsUpdateFromSV(doc: Doc, targetSV: Map<Int, Int>, v2: Boolean): ByteArray {
    val encoder = if (v2) UpdateEncoderV2() else UpdateEncoderV1()
    val store = doc.store

    // Collect clients with structs beyond the target's state vector
    val clientsToWrite = mutableListOf<Pair<Int, MutableList<AbstractStruct>>>()
    for ((client, structs) in store.clients) {
        val targetClock = targetSV[client] ?: 0
        if (structs.isNotEmpty() && structs.last().id.clock + structs.last().length > targetClock) {
            clientsToWrite.add(Pair(client, structs))
        }
    }

    // Write struct count and structs
    encoder.restEncoder.writeVarUint(clientsToWrite.size)
    for ((client, structs) in clientsToWrite.sortedByDescending { it.first }) {
        val targetClock = targetSV[client] ?: 0
        val startIndex = if (targetClock > 0) findIndexSS(structs, targetClock) else 0
        val firstStruct = structs[startIndex]
        val offset = maxOf(0, targetClock - firstStruct.id.clock)

        // Count total structs to write
        val count = structs.size - startIndex
        encoder.restEncoder.writeVarUint(count)
        encoder.writeClient(client)
        encoder.restEncoder.writeVarUint(firstStruct.id.clock + offset)

        // Write each struct
        for (i in startIndex until structs.size) {
            val struct = structs[i]
            val off = if (i == startIndex) offset else 0
            struct.write(encoder, off, 0)
        }
    }

    // Write delete set
    val ds = createDeleteSetFromStructStore(store)
    writeDeleteSet(encoder, ds)

    return encoder.toByteArray()
}

// --- Update Decoding & Application ---

/**
 * Apply a V1-encoded update to a document.
 */
fun applyUpdate(doc: Doc, update: ByteArray, origin: Any? = null) {
    transact(doc, origin, false) { transaction ->
        val decoder = UpdateDecoderV1(update)
        readAndApplyUpdate(transaction, decoder)
    }
}

/**
 * Apply a V2-encoded update to a document.
 */
fun applyUpdateV2(doc: Doc, update: ByteArray, origin: Any? = null) {
    transact(doc, origin, false) { transaction ->
        val decoder = UpdateDecoderV2(update)
        readAndApplyUpdate(transaction, decoder)
    }
}

/**
 * Read structs and delete set from a decoder and integrate them.
 */
internal fun readAndApplyUpdate(transaction: Transaction, decoder: UpdateDecoder) {
    val store = transaction.doc.store

    // Read structs
    val numClients = decoder.restDecoder.readVarUint()
    val clientStructRefs = mutableMapOf<Int, MutableList<AbstractStruct>>()

    repeat(numClients) {
        val numStructs = decoder.restDecoder.readVarUint()
        val client = decoder.readClient()
        var clock = decoder.restDecoder.readVarUint()
        val structs = mutableListOf<AbstractStruct>()

        repeat(numStructs) {
            val struct = readStruct(decoder, ID(client, clock))
            structs.add(struct)
            clock += struct.length
        }

        clientStructRefs[client] = structs
    }

    // Integrate structs
    integrateStructs(transaction, store, clientStructRefs)

    // Read and apply delete set
    readAndApplyDeleteSet(transaction, decoder)
}

/**
 * Read a single struct from the decoder.
 */
internal fun readStruct(decoder: UpdateDecoder, id: ID): AbstractStruct {
    val info = decoder.readInfo()
    val contentRef = info and BITS5

    // GC
    if (contentRef == 0) {
        val len = decoder.readLen()
        return GC(id, len)
    }
    // Skip
    if (contentRef == 10) {
        val len = decoder.readLen()
        return Skip(id, len)
    }

    // Item
    val hasOrigin = info and BIT8 != 0
    val hasRightOrigin = info and BIT7 != 0
    val hasParentSub = info and BIT6 != 0

    val origin = if (hasOrigin) decoder.readLeftID() else null
    val rightOrigin = if (hasRightOrigin) decoder.readRightID() else null

    val parent: Any?
    val parentSub: String?
    if (!hasOrigin && !hasRightOrigin) {
        val isYKey = decoder.readParentInfo()
        parent = if (isYKey) {
            decoder.readString() // root type key
        } else {
            decoder.readLeftID() // parent item ID
        }
        // parentSub is read inside this block, matching yjs
        parentSub = if (hasParentSub) decoder.readString() else null
    } else {
        parent = null
        parentSub = null
    }
    val content = readContent(contentRef, decoder)

    return Item(
        id = id,
        left = null,
        origin = origin,
        right = null,
        rightOrigin = rightOrigin,
        parent = parent, // null when origin/rightOrigin present; resolved during integration
        parentSub = parentSub,
        content = content
    )
}

/**
 * Per-client struct reference tracker for integration.
 */
internal class ClientStructRef(
    val refs: MutableList<AbstractStruct>,
    var i: Int = 0
)

/**
 * Integrate decoded structs into the document.
 * Uses a stack-based approach matching yjs integrateStructs for proper
 * dependency resolution with out-of-order struct delivery.
 */
internal fun integrateStructs(
    transaction: Transaction,
    store: StructStore,
    clientStructRefs: MutableMap<Int, MutableList<AbstractStruct>>
) {
    // Wrap refs with cursors
    val refs = mutableMapOf<Int, ClientStructRef>()
    for ((client, structs) in clientStructRefs) {
        refs[client] = ClientStructRef(structs)
    }

    // Retry pending structs from previous updates
    val pending = store.pendingStructs
    if (pending != null) {
        store.pendingStructs = null
        val pendingDecoder = UpdateDecoderV1(pending.update)
        val pendingNumClients = pendingDecoder.restDecoder.readVarUint()
        repeat(pendingNumClients) {
            val numStructs = pendingDecoder.restDecoder.readVarUint()
            val client = pendingDecoder.readClient()
            var clock = pendingDecoder.restDecoder.readVarUint()
            val pendingStructs = mutableListOf<AbstractStruct>()
            repeat(numStructs) {
                val struct = readStruct(pendingDecoder, ID(client, clock))
                pendingStructs.add(struct)
                clock += struct.length
            }
            val existing = refs[client]
            if (existing != null) {
                val merged = mutableListOf<AbstractStruct>()
                merged.addAll(pendingStructs)
                merged.addAll(existing.refs.subList(existing.i, existing.refs.size))
                merged.sortBy { it.id.clock }
                refs[client] = ClientStructRef(merged)
            } else {
                refs[client] = ClientStructRef(pendingStructs)
            }
        }
    }

    // Sort client IDs ascending (matching yjs)
    val sortedClientIds = refs.keys.sorted().toMutableList()
    if (sortedClientIds.isEmpty()) return

    // Get next client with unprocessed structs
    fun getNextStructTarget(): ClientStructRef? {
        while (sortedClientIds.isNotEmpty()) {
            val target = refs[sortedClientIds.last()]
            if (target != null && target.i < target.refs.size) return target
            sortedClientIds.removeAt(sortedClientIds.size - 1)
        }
        return null
    }

    var curStructsTarget = getNextStructTarget() ?: return

    // Cache of local state per client (avoids repeated store lookups)
    val localStateCache = mutableMapOf<Int, Int>()
    fun getLocalState(client: Int): Int {
        return localStateCache.getOrPut(client) { getState(store, client) }
    }

    // Track missing SVs for pending
    val missingSV = mutableMapOf<Int, Int>()
    fun updateMissingSV(client: Int, clock: Int) {
        val existing = missingSV[client]
        if (existing == null || existing > clock) {
            missingSV[client] = clock
        }
    }

    // Stack for dependency resolution
    val stack = mutableListOf<AbstractStruct>()

    // Separate map for unresolvable structs (NOT re-processed in the main loop)
    val restStructs = mutableMapOf<Int, MutableList<AbstractStruct>>()

    fun addStackToRestSS() {
        for (item in stack) {
            val client = item.id.client
            val existing = refs[client]
            if (existing != null) {
                val remaining = existing.refs.subList(existing.i, existing.refs.size).toMutableList()
                remaining.add(0, item)
                restStructs[client] = remaining
                refs.remove(client)
            } else {
                restStructs.getOrPut(client) { mutableListOf() }.add(item)
            }
        }
        stack.clear()
    }

    var stackHead = curStructsTarget.refs[curStructsTarget.i++]

    while (true) {
        if (stackHead !is Skip) {
            val client = stackHead.id.client
            val localClock = getLocalState(client)
            val offset = localClock - stackHead.id.clock

            if (offset < 0) {
                // Missing preceding structs from same client
                stack.add(stackHead)
                updateMissingSV(client, stackHead.id.clock - 1)
                addStackToRestSS()
            } else if (offset >= stackHead.length) {
                // Already have this struct — skip
            } else {
                // Check for missing dependencies (origin, rightOrigin, parent)
                val missing = stackHead.getMissing(transaction, store)
                if (missing != null) {
                    stack.add(stackHead)
                    // Try to process the missing client's structs first
                    val missingRefs = refs[missing]
                    if (missingRefs != null && missingRefs.i < missingRefs.refs.size) {
                        stackHead = missingRefs.refs[missingRefs.i++]
                        continue
                    } else {
                        // Can't resolve — give up on this chain
                        updateMissingSV(missing, getLocalState(missing))
                        addStackToRestSS()
                    }
                } else {
                    // Integrate!
                    stackHead.integrate(transaction, offset)
                    localStateCache[client] = stackHead.id.clock + stackHead.length
                }
            }
        }

        // Move to next struct
        if (stack.isNotEmpty()) {
            stackHead = stack.removeAt(stack.size - 1)
        } else if (curStructsTarget.i < curStructsTarget.refs.size) {
            stackHead = curStructsTarget.refs[curStructsTarget.i++]
        } else {
            curStructsTarget = getNextStructTarget() ?: break
            stackHead = curStructsTarget.refs[curStructsTarget.i++]
        }
    }

    // Store remaining unresolvable structs as pending
    if (missingSV.isNotEmpty() && restStructs.isNotEmpty()) {
        val pendingEncoder = UpdateEncoderV1()
        pendingEncoder.restEncoder.writeVarUint(restStructs.size)
        for ((client, structs) in restStructs) {
            pendingEncoder.restEncoder.writeVarUint(structs.size)
            pendingEncoder.writeClient(client)
            pendingEncoder.restEncoder.writeVarUint(structs.first().id.clock)
            for (struct in structs) {
                struct.write(pendingEncoder, 0, 0)
            }
        }
        pendingEncoder.restEncoder.writeVarUint(0)
        store.pendingStructs = PendingStructs(
            missing = missingSV.toMutableMap(),
            update = pendingEncoder.toByteArray()
        )
    }
}

/**
 * Read and apply a delete set from the decoder.
 */
internal fun readAndApplyDeleteSet(transaction: Transaction, decoder: UpdateDecoder) {
    val store = transaction.doc.store
    val numClients = decoder.restDecoder.readVarUint()
    repeat(numClients) {
        decoder.resetDsCurVal()
        val client = decoder.restDecoder.readVarUint()
        val numRanges = decoder.restDecoder.readVarUint()
        val structs = store.clients[client]
        repeat(numRanges) {
            val clock = decoder.readDsClock()
            val len = decoder.readDsLen()
            if (structs != null) {
                // Apply deletions — split items at boundaries (matching yjs)
                val end = clock + len
                if (structs.isNotEmpty() && clock < structs.last().id.clock + structs.last().length) {
                    var i = findIndexSS(structs, clock)
                    var struct = structs[i]
                    // Split at start if needed
                    if (struct.id.clock < clock && struct is Item) {
                        getItemCleanStart(transaction, ID(client, clock))
                        i++
                        struct = structs[i]
                    }
                    while (i < structs.size) {
                        struct = structs[i]
                        if (struct.id.clock >= end) break
                        // Split at end if needed
                        if (struct.id.clock + struct.length > end && struct is Item) {
                            getItemCleanStart(transaction, ID(client, end))
                        }
                        if (struct is Item && !struct.deleted) {
                            struct.delete(transaction)
                        }
                        i++
                    }
                }
            }
            transaction.addToDeleteSet(client, clock, len)
        }
    }
}
