package yks.sync

import yks.utils.Doc

/**
 * Transport-agnostic interface for sending binary messages to a remote peer.
 * Implement this for your specific transport (WebSocket, HTTP, etc.).
 */
fun interface MessageSender {
    fun send(message: ByteArray)
}

/**
 * Manages the sync lifecycle for one connected client.
 * Transport-agnostic — plug into any WebSocket/HTTP framework.
 *
 * Usage:
 * ```
 * val handler = SyncHandler(doc, awareness) { bytes -> ws.send(bytes) }
 * handler.onConnect()                    // sends SyncStep1 + local awareness
 * ws.onMessage { handler.onMessage(it) } // handles incoming, sends replies
 * ws.onClose { handler.onDisconnect() }  // cleans up awareness
 * ```
 */
class SyncHandler(
    val doc: Doc,
    val awareness: Awareness,
    private val sender: MessageSender
) {
    /** True after receiving SyncStep2 from the remote peer. */
    var synced: Boolean = false
        private set

    private val unsubscribers = mutableListOf<() -> Unit>()

    /** Track which remote client IDs we've seen through this connection. */
    private val remoteClients = mutableSetOf<Int>()

    /**
     * Call when the connection opens.
     * Subscribes to doc/awareness events and sends initial sync + awareness messages.
     */
    fun onConnect() {
        // Subscribe to doc updates — forward local changes to remote peer
        val unsubUpdate = doc.on2<ByteArray, Any?>("update") { update, origin ->
            if (origin !== this) {
                sender.send(MessageProtocol.encodeUpdate(update))
            }
        }
        unsubscribers.add(unsubUpdate)

        // Subscribe to awareness updates — forward awareness changes to remote peer
        val unsubAwareness = awareness.on2<AwarenessChange, Any?>("update") { change, origin ->
            if (origin !== this) {
                val changedClients = change.added + change.updated + change.removed
                if (changedClients.isNotEmpty()) {
                    sender.send(MessageProtocol.encodeAwareness(awareness, changedClients))
                }
            }
        }
        unsubscribers.add(unsubAwareness)

        // Send SyncStep1 (our state vector)
        sender.send(MessageProtocol.encodeSyncStep1(doc))

        // Send local awareness state
        val localState = awareness.getLocalState()
        if (localState != null) {
            sender.send(MessageProtocol.encodeAwareness(awareness, listOf(doc.clientID)))
        }
    }

    /**
     * Call when a binary message arrives from the remote peer.
     * Dispatches to sync/awareness handlers and sends replies if needed.
     */
    fun onMessage(data: ByteArray) {
        // Track remote awareness clients for cleanup on disconnect
        trackRemoteClients(data)

        val reply = MessageProtocol.readMessage(data, doc, awareness, origin = this)
        if (reply.isNotEmpty()) {
            sender.send(reply)
        }

        // Detect sync completion: if we received a SyncStep2, we're synced
        if (!synced && data.isNotEmpty()) {
            val msgType = data[0].toInt() and 0x7F
            if (msgType == MessageProtocol.MESSAGE_SYNC && data.size > 1) {
                val syncType = data[1].toInt() and 0x7F
                if (syncType == SyncProtocol.MESSAGE_SYNC_STEP2) {
                    synced = true
                }
            }
        }
    }

    /**
     * Call when the connection closes.
     * Removes remote awareness states and unsubscribes from events.
     */
    fun onDisconnect() {
        // Remove awareness for all remote clients that came through this connection
        val toRemove = remoteClients.filter { it != awareness.clientID }.toList()
        if (toRemove.isNotEmpty()) {
            removeAwarenessStates(awareness, toRemove, this)
        }
        remoteClients.clear()
        dispose()
    }

    /** Unsubscribe from all events. */
    fun dispose() {
        for (unsub in unsubscribers) {
            unsub()
        }
        unsubscribers.clear()
    }

    /**
     * Extract remote client IDs from awareness messages for disconnect cleanup.
     */
    private fun trackRemoteClients(data: ByteArray) {
        if (data.isEmpty()) return
        val msgType = data[0].toInt() and 0x7F
        if (msgType == MessageProtocol.MESSAGE_AWARENESS && data.size > 1) {
            // Parse the awareness update to extract client IDs
            try {
                val decoder = yks.lib0.Decoder(data)
                decoder.readVarUint() // skip message type
                val awarenessData = decoder.readVarUint8Array()
                val awarenessDecoder = yks.lib0.Decoder(awarenessData)
                val numClients = awarenessDecoder.readVarUint()
                repeat(numClients) {
                    val clientID = awarenessDecoder.readVarUint()
                    remoteClients.add(clientID)
                    awarenessDecoder.readVarUint() // clock
                    awarenessDecoder.readVarString() // state JSON
                }
            } catch (_: Exception) {
                // Ignore parse errors in tracking
            }
        }
    }
}
