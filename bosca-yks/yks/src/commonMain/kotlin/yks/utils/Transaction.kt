package yks.utils

import yks.structs.AbstractStruct
import yks.structs.GC
import yks.structs.Item
import yks.types.YType

/**
 * A transaction batches CRDT mutations and fires events on commit.
 * All document modifications must happen inside a transaction.
 * Matches yjs Transaction.
 */
class Transaction(
    val doc: Doc,
    val origin: Any? = null,
    val local: Boolean = true
) {
    /** Items deleted in this transaction. */
    val deleteSet = IdSet()

    /** Items inserted in this transaction. */
    val insertSet = IdSet()

    /** Subset of deleteSet for formatting cleanup. */
    val cleanUps = IdSet()

    /** Types and their changed keys (null = list change). */
    val changed = mutableMapOf<YType, MutableSet<String?>>()

    /** For deep observers: types → events. */
    val changedParentTypes = mutableMapOf<YType, MutableList<YEvent>>()

    /** Structs to try merging after this transaction. */
    val mergeStructs = mutableListOf<AbstractStruct>()

    /** User-defined metadata. */
    val meta = mutableMapOf<Any, Any?>()

    /** Subdocs lifecycle tracking. */
    val subdocsAdded = mutableSetOf<Doc>()
    val subdocsRemoved = mutableSetOf<Doc>()
    val subdocsLoaded = mutableSetOf<Doc>()

    /** Whether text formatting cleanup is needed. */
    var needFormattingCleanup = false

    /** Whether this transaction is done processing. */
    var done = false

    /** State vector captured at transaction start. */
    val beforeState: Map<Int, Int> = getStateVector(doc.store)

    /** State vector after transaction (computed lazily, accessed during cleanup). */
    private var _afterState: Map<Int, Int>? = null
    val afterState: Map<Int, Int>
        get() {
            if (_afterState == null) {
                _afterState = getStateVector(doc.store)
            }
            return _afterState!!
        }

    /** Get next available ID for this doc. */
    fun nextID(): ID {
        val clock = getState(doc.store, doc.clientID)
        return ID(doc.clientID, clock)
    }

    /** Add to delete set. */
    fun addToDeleteSet(client: Int, clock: Int, length: Int) {
        deleteSet.add(client, clock, length)
    }
}

/** Add a struct's ID range to the transaction's insert set. */
fun addToInsertSet(transaction: Transaction, client: Int, clock: Int, length: Int) {
    transaction.insertSet.add(client, clock, length)
}

/** Record that a type has changed. */
fun addChangedTypeToTransaction(transaction: Transaction, type: YType?, key: String?) {
    if (type == null) return
    val subs = transaction.changed.getOrPut(type) { mutableSetOf() }
    subs.add(key)
}

/**
 * Execute a function inside a transaction.
 * Matches yjs transact().
 */
fun <T> transact(doc: Doc, origin: Any? = null, local: Boolean = true, body: (Transaction) -> T): T {
    val initialTransaction = doc.transaction == null
    val transaction: Transaction
    if (initialTransaction) {
        transaction = Transaction(doc, origin, local)
        doc.transaction = transaction
        doc.transactionCleanups.add(transaction)
        if (doc.transactionCleanups.size == 1) {
            doc.emit("beforeAllTransactions", doc)
        }
        doc.emit("beforeTransaction", transaction)
    } else {
        transaction = doc.transaction!!
    }

    val result = body(transaction)

    if (initialTransaction) {
        doc.transaction = null
        // Process cleanups
        cleanupTransactions(doc.transactionCleanups, 0)
    }

    return result
}

/**
 * Process transaction cleanup queue.
 * Fires observers, runs GC, merges structs, emits updates.
 */
internal fun cleanupTransactions(cleanups: MutableList<Transaction>, index: Int) {
    if (index >= cleanups.size) return
    val transaction = cleanups[index]
    val doc = transaction.doc

    transaction.done = true

    doc.emit("beforeObserverCalls", transaction)

    // Fire type observers
    for ((type, subs) in transaction.changed) {
        if (type.item == null || !type.item!!.deleted) {
            type.callObserver(transaction, subs)
        }
    }

    // Fire deep observers (propagate up parent chain)
    for ((type, events) in transaction.changedParentTypes) {
        if (type.item == null || !type.item!!.deleted) {
            type.deepEventHandler.callListeners(Pair(events, transaction))
        }
    }

    doc.emit("afterTransaction", transaction)

    // GC deleted items
    if (doc.gc) {
        tryGcDeleteSet(transaction, doc.gcFilter)
    }

    // Merge adjacent structs
    tryMergeDeleteSet(transaction)

    // Try to merge inserted structs
    for (struct in transaction.mergeStructs) {
        val structs = doc.store.clients[struct.id.client] ?: continue
        val idx = findIndexSS(structs, struct.id.clock)
        tryToMergeWithLefts(structs, idx)
    }

    // Detect client ID collision
    if (!transaction.local && transaction.insertSet.clients.containsKey(doc.clientID)) {
        doc.clientID = yks.lib0.randomUint32()
    }

    doc.emit("afterTransactionCleanup", transaction)

    // Emit update events
    if (doc.hasListeners("update")) {
        val update = encodeUpdateFromTransaction(transaction, false)
        if (update != null) {
            doc.emit2("update", update, transaction.origin)
        }
    }
    if (doc.hasListeners("updateV2")) {
        val update = encodeUpdateFromTransaction(transaction, true)
        if (update != null) {
            doc.emit2("updateV2", update, transaction.origin)
        }
    }

    // Handle subdocs
    if (transaction.subdocsAdded.isNotEmpty() || transaction.subdocsRemoved.isNotEmpty() || transaction.subdocsLoaded.isNotEmpty()) {
        doc.subdocs.addAll(transaction.subdocsAdded)
        doc.subdocs.removeAll(transaction.subdocsRemoved)
        doc.emit("subdocs", SubdocsEvent(
            added = transaction.subdocsAdded,
            removed = transaction.subdocsRemoved,
            loaded = transaction.subdocsLoaded
        ))
        transaction.subdocsRemoved.forEach { it.destroy() }
    }

    // Process next queued transaction
    if (index + 1 < cleanups.size) {
        cleanupTransactions(cleanups, index + 1)
    } else {
        cleanups.clear()
        doc.emit("afterAllTransactions", doc)
    }
}

data class SubdocsEvent(
    val added: Set<Doc>,
    val removed: Set<Doc>,
    val loaded: Set<Doc>
)

/** Try to GC deleted items. */
internal fun tryGcDeleteSet(transaction: Transaction, gcFilter: (Item) -> Boolean) {
    val store = transaction.doc.store
    for ((client, ranges) in transaction.deleteSet.clients) {
        val structs = store.clients[client] ?: continue
        for (range in ranges.getIds()) {
            val start = range.clock
            val end = start + range.len
            var i = findIndexSS(structs, start)
            while (i < structs.size) {
                val struct = structs[i]
                if (struct.id.clock >= end) break
                if (struct is Item && struct.deleted && !struct.keep && gcFilter(struct)) {
                    struct.gc(transaction, false)
                }
                i++
            }
        }
    }
}

/** Try to merge adjacent deleted structs. */
internal fun tryMergeDeleteSet(transaction: Transaction) {
    val store = transaction.doc.store
    for ((client, ranges) in transaction.deleteSet.clients) {
        val structs = store.clients[client] ?: continue
        for (range in ranges.getIds()) {
            val idx = findIndexSS(structs, range.clock + range.len - 1)
            tryToMergeWithLefts(structs, idx)
        }
    }
}

/** Try to merge structs[pos] with its left neighbors. */
fun tryToMergeWithLefts(structs: MutableList<AbstractStruct>, pos: Int) {
    var i = pos
    while (i > 0) {
        val left = structs[i - 1]
        val right = structs[i]
        if (left.deleted == right.deleted && left::class == right::class && left.mergeWith(right)) {
            structs.removeAt(i)
        } else {
            break
        }
        i--
    }
}

/**
 * Encode the changes from a transaction as an update.
 * Uses the beforeState as the target SV, matching yjs writeUpdateMessageFromTransaction.
 * Returns null if there are no changes.
 */
internal fun encodeUpdateFromTransaction(transaction: Transaction, v2: Boolean): ByteArray? {
    val beforeState = transaction.beforeState
    val afterState = transaction.afterState

    // Check if there are any new structs
    val hasNewStructs = afterState.any { (client, clock) ->
        (beforeState[client] ?: 0) != clock
    }
    if (!hasNewStructs && transaction.deleteSet.isEmpty()) return null

    val encoder = if (v2) UpdateEncoderV2() else UpdateEncoderV1()
    val store = transaction.doc.store

    // Use the same encoding as encodeStateAsUpdate with beforeState as target SV
    val clientsToWrite = mutableListOf<Pair<Int, MutableList<AbstractStruct>>>()
    for ((client, structs) in store.clients) {
        val targetClock = beforeState[client] ?: 0
        if (structs.isNotEmpty() && structs.last().id.clock + structs.last().length > targetClock) {
            clientsToWrite.add(Pair(client, structs))
        }
    }

    encoder.restEncoder.writeVarUint(clientsToWrite.size)
    for ((client, structs) in clientsToWrite) {
        val targetClock = beforeState[client] ?: 0
        val startIndex = if (targetClock > 0) findIndexSS(structs, targetClock) else 0
        val firstStruct = structs[startIndex]
        val offset = maxOf(0, targetClock - firstStruct.id.clock)
        val count = structs.size - startIndex

        encoder.restEncoder.writeVarUint(count)
        encoder.writeClient(client)
        encoder.restEncoder.writeVarUint(firstStruct.id.clock + offset)

        for (i in startIndex until structs.size) {
            val struct = structs[i]
            val off = if (i == startIndex) offset else 0
            struct.write(encoder, off, 0)
        }
    }

    // Write delete set
    writeDeleteSet(encoder, transaction.deleteSet)

    return encoder.toByteArray()
}

/** Write a delete set to an encoder. Uses V2 delta encoding when appropriate. */
fun writeDeleteSet(encoder: UpdateEncoder, ds: IdSet) {
    val clients = ds.clients.entries.toList()
    encoder.restEncoder.writeVarUint(clients.size)
    for ((client, ranges) in clients) {
        encoder.resetDsCurVal()
        encoder.restEncoder.writeVarUint(client)
        val ids = ranges.getIds()
        encoder.restEncoder.writeVarUint(ids.size)
        for (r in ids) {
            encoder.writeDsClock(r.clock)
            encoder.writeDsLen(r.len)
        }
    }
}
