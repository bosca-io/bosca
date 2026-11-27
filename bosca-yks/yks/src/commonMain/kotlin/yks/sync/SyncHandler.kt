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
 * handler.onConnect()                    // sends SyncStep1 + known awareness
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

    /** Track remote clients whose awareness clocks were accepted through this connection. */
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
            if (origin === this) {
                // Providers echo states learned from other peers. Only clocks accepted
                // through this connection establish ownership for disconnect cleanup.
                remoteClients.addAll(change.added)
                remoteClients.addAll(change.updated)
                remoteClients.removeAll(change.removed.toSet())
            } else {
                val changedClients = change.added + change.updated + change.removed
                if (changedClients.isNotEmpty()) {
                    sender.send(MessageProtocol.encodeAwareness(awareness, changedClients))
                }
            }
        }
        unsubscribers.add(unsubAwareness)

        // Send SyncStep1 (our state vector)
        sender.send(MessageProtocol.encodeSyncStep1(doc))

        // A late joiner also needs the peers already known by this handler's document.
        val clients = awareness.getStates().keys.toList()
        if (clients.isNotEmpty()) {
            sender.send(MessageProtocol.encodeAwareness(awareness, clients))
        }
    }

    /**
     * Call when a binary message arrives from the remote peer.
     * Dispatches to sync/awareness handlers and sends replies if needed.
     */
    fun onMessage(data: ByteArray) {
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
}
