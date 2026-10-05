package yks.sync

import yks.lib0.*
import yks.utils.Doc

/**
 * Awareness protocol matching y-protocols/awareness.js.
 *
 * Manages ephemeral shared state (cursor positions, user presence, etc.)
 * using a state-based CRDT with clock-based conflict resolution.
 *
 * Each client has a unique entry that only they can modify. States are
 * considered outdated after [OUTDATED_TIMEOUT] milliseconds without update.
 */
class Awareness(val doc: Doc) : Observable() {
    val clientID: Int = doc.clientID

    /** Maps client ID to their current state (JSON-like map). */
    internal val states: MutableMap<Int, Map<String, Any?>> = mutableMapOf()

    /** Maps client ID to their metadata (clock + last updated timestamp). */
    internal val meta: MutableMap<Int, MetaClientState> = mutableMapOf()

    init {
        doc.on<Doc>("destroy") { destroy() }
        setLocalState(emptyMap())
    }

    /** Get this client's awareness state, or null if removed. */
    fun getLocalState(): Map<String, Any?>? = states[clientID]

    /**
     * Set this client's awareness state. Pass null to indicate offline/disconnect.
     * Increments the clock and emits "update" and "change" events.
     */
    fun setLocalState(state: Map<String, Any?>?) {
        val currMeta = meta[clientID]
        val clock = if (currMeta == null) 0 else currMeta.clock + 1
        val prevState = states[clientID]

        if (state == null) {
            states.remove(clientID)
        } else {
            states[clientID] = state
        }
        meta[clientID] = MetaClientState(clock, currentTimeMillis())

        val added = mutableListOf<Int>()
        val updated = mutableListOf<Int>()
        val filteredUpdated = mutableListOf<Int>()
        val removed = mutableListOf<Int>()

        if (state == null) {
            removed.add(clientID)
        } else if (prevState == null) {
            added.add(clientID)
        } else {
            updated.add(clientID)
            if (!deepEquals(prevState, state)) {
                filteredUpdated.add(clientID)
            }
        }

        if (added.isNotEmpty() || filteredUpdated.isNotEmpty() || removed.isNotEmpty()) {
            emit2("change", AwarenessChange(added, filteredUpdated, removed), "local")
        }
        emit2("update", AwarenessChange(added, updated, removed), "local")
    }

    /** Update a single field on the local state. */
    fun setLocalStateField(field: String, value: Any?) {
        val state = getLocalState() ?: return
        setLocalState(state + (field to value))
    }

    /** Get all awareness states. */
    fun getStates(): Map<Int, Map<String, Any?>> = states

    /**
     * Check for outdated clients and remove them.
     * Call this periodically from the transport layer (e.g. every 3 seconds).
     */
    fun checkTimeouts() {
        val now = currentTimeMillis()
        // Renew local state if half the timeout has elapsed
        val localState = getLocalState()
        val localMeta = meta[clientID]
        if (localState != null && localMeta != null &&
            OUTDATED_TIMEOUT / 2 <= now - localMeta.lastUpdated
        ) {
            setLocalState(localState)
        }
        // Remove outdated remote clients
        val toRemove = mutableListOf<Int>()
        for ((cid, m) in meta) {
            if (cid != clientID && OUTDATED_TIMEOUT <= now - m.lastUpdated && states.containsKey(cid)) {
                toRemove.add(cid)
            }
        }
        if (toRemove.isNotEmpty()) {
            removeAwarenessStates(this, toRemove, "timeout")
        }
    }

    override fun destroy() {
        emit("destroy", this)
        setLocalState(null)
        super.destroy()
    }

    companion object {
        /** Awareness states older than this (ms) are considered outdated. Matches yjs. */
        const val OUTDATED_TIMEOUT = 30_000L
    }
}

data class MetaClientState(val clock: Int, val lastUpdated: Long)

data class AwarenessChange(
    val added: List<Int>,
    val updated: List<Int>,
    val removed: List<Int>
)

/**
 * Encode awareness states for the given client IDs.
 * Format: varUint(numClients) • for each: varUint(clientId) • varUint(clock) • varString(JSON(state))
 */
fun encodeAwarenessUpdate(
    awareness: Awareness,
    clients: List<Int>,
    states: Map<Int, Map<String, Any?>?> = awareness.states
): ByteArray {
    val encoder = Encoder()
    encoder.writeVarUint(clients.size)
    for (clientID in clients) {
        val state = states[clientID]
        val clock = awareness.meta[clientID]?.clock ?: 0
        encoder.writeVarUint(clientID)
        encoder.writeVarUint(clock)
        encoder.writeVarString(jsonStringify(state))
    }
    return encoder.toByteArray()
}

/**
 * Decode and apply an awareness update.
 * Respects clock ordering: only applies if received clock > known clock.
 */
fun applyAwarenessUpdate(awareness: Awareness, update: ByteArray, origin: Any?) {
    val decoder = Decoder(update)
    val timestamp = currentTimeMillis()
    val added = mutableListOf<Int>()
    val updated = mutableListOf<Int>()
    val filteredUpdated = mutableListOf<Int>()
    val removed = mutableListOf<Int>()
    val len = decoder.readVarUint()

    repeat(len) {
        val clientID = decoder.readVarUint()
        var clock = decoder.readVarUint()
        val stateJson = decoder.readVarString()
        @Suppress("UNCHECKED_CAST")
        val state = jsonParse(stateJson) as? Map<String, Any?>

        val clientMeta = awareness.meta[clientID]
        val prevState = awareness.states[clientID]
        val currClock = clientMeta?.clock ?: 0

        if (currClock < clock || (currClock == clock && state == null && awareness.states.containsKey(clientID))) {
            if (state == null) {
                // Never let a remote client remove our own local state
                if (clientID == awareness.clientID && awareness.getLocalState() != null) {
                    clock++
                } else {
                    awareness.states.remove(clientID)
                }
            } else {
                awareness.states[clientID] = state
            }
            awareness.meta[clientID] = MetaClientState(clock, timestamp)

            if (clientMeta == null && state != null) {
                added.add(clientID)
            } else if (clientMeta != null && state == null) {
                removed.add(clientID)
            } else if (state != null) {
                if (!deepEquals(state, prevState)) {
                    filteredUpdated.add(clientID)
                }
                updated.add(clientID)
            }
        }
    }

    if (added.isNotEmpty() || filteredUpdated.isNotEmpty() || removed.isNotEmpty()) {
        awareness.emit2("change", AwarenessChange(added, filteredUpdated, removed), origin)
    }
    if (added.isNotEmpty() || updated.isNotEmpty() || removed.isNotEmpty()) {
        awareness.emit2("update", AwarenessChange(added, updated, removed), origin)
    }
}

/**
 * Remove clients from awareness (mark as offline).
 * Increments the clock for the local client if it's being removed.
 */
fun removeAwarenessStates(awareness: Awareness, clients: List<Int>, origin: Any?) {
    val removed = mutableListOf<Int>()
    for (clientID in clients) {
        if (awareness.states.containsKey(clientID)) {
            awareness.states.remove(clientID)
            if (clientID == awareness.clientID) {
                val curMeta = awareness.meta[clientID]!!
                awareness.meta[clientID] = MetaClientState(
                    curMeta.clock + 1,
                    currentTimeMillis()
                )
            }
            removed.add(clientID)
        }
    }
    if (removed.isNotEmpty()) {
        awareness.emit2("change", AwarenessChange(emptyList(), emptyList(), removed), origin)
        awareness.emit2("update", AwarenessChange(emptyList(), emptyList(), removed), origin)
    }
}

/** Deep equality check for awareness states. */
private fun deepEquals(a: Any?, b: Any?): Boolean {
    if (a === b) return true
    if (a == null || b == null) return a == b
    if (a is Map<*, *> && b is Map<*, *>) {
        if (a.size != b.size) return false
        for ((k, v) in a) {
            if (!b.containsKey(k) || !deepEquals(v, b[k])) return false
        }
        return true
    }
    if (a is List<*> && b is List<*>) {
        if (a.size != b.size) return false
        for (i in a.indices) {
            if (!deepEquals(a[i], b[i])) return false
        }
        return true
    }
    return a == b
}
