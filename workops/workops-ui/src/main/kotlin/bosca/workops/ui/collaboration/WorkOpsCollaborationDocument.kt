package bosca.workops.ui.collaboration

import bosca.pubsub.PubSubService
import bosca.serialization.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import yks.sync.Awareness
import yks.sync.MessageProtocol
import yks.sync.SyncHandler
import yks.utils.Doc
import yks.utils.applyUpdate
import yks.utils.encodeStateAsUpdate
import java.io.IOException

/** Serializes the existing YKS document and Content writes for its active participants. */
internal class WorkOpsCollaborationDocument(
    val room: WorkOpsCollaborationRoom,
    private val content: WorkOpsCollaborationContentClient,
    private val pubSub: PubSubService?,
    private val onFailure: (Exception) -> Unit,
) {
    private val mutex = Mutex()
    private val document = Doc()
    private val awareness = Awareness(document).apply { setLocalState(null) }
    private val participants = mutableSetOf<WorkOpsCollaborationParticipant>()
    private var loaded = false
    private var initializationFailure: IOException? = null
    private val lifetime = SupervisorJob()
    private val scope = CoroutineScope(lifetime + Dispatchers.Default)
    private val changes = Channel<Unit>(Channel.CONFLATED)
    private val presence = Channel<WorkOpsCollaborationEvent>(128, BufferOverflow.DROP_OLDEST)
    private val source = UUID.random().toString()
    private val channel = "workops.collaboration.${room.metadataId}.${room.version}"
    // The server registry owns reservations, including connections still loading their snapshot.
    var reservations = 0

    suspend fun connect(name: String, token: String, send: (ByteArray) -> Unit, close: suspend (Int, String) -> Unit): WorkOpsCollaborationParticipant = mutex.withLock {
        initializationFailure?.let { throw it }
        if (!loaded) {
            try {
                startNotifications()
                applyUpdate(document, content.initialize(room, token))
                loaded = true
            } catch (failure: Exception) {
                initializationFailure = IOException("Document collaboration initialization failed", failure)
                throw failure
            }
        }
        publish("query")
        val handler = SyncHandler(document, awareness) { bytes -> send(WorkOpsCollaborationProtocol.frame(name, bytes)) }
        val participant = WorkOpsCollaborationParticipant(name, this, token, handler, send, close)
        participants += participant
        handler.onConnect()
        participant
    }

    private fun worker(block: suspend () -> Unit) = scope.launch {
        try { block() }
        catch (failure: CancellationException) { throw failure }
        catch (failure: Exception) {
            onFailure(failure)
            mutex.withLock { participants.toList() }.forEach { it.close(1011, "Document collaboration is unavailable") }
        }
    }

    private suspend fun startNotifications() {
        if (pubSub != null) {
            val ready = CompletableDeferred<Unit>()
            worker {
                pubSub.subscribe(channel, WorkOpsCollaborationEvent.serializer()).collect { incoming ->
                    val event = incoming.message
                    if (event.source == source) {
                        if (event.kind == "ready") ready.complete(Unit)
                    } else when (event.kind) {
                        "state" -> changes.trySend(Unit)
                        "presence", "query" -> presence.trySend(event)
                    }
                }
            }
            // The common PubSub contract is cold. A self-message confirms actual broker
            // delivery before the initial snapshot, closing the subscribe/load race.
            val subscribed = withTimeoutOrNull(10_000) {
                while (!ready.isCompleted) { publish("ready"); delay(50) }
                true
            } ?: false
            if (!subscribed) throw IOException("Document collaboration notifications did not become ready")
            worker {
                for (event in presence) mutex.withLock {
                    if (event.kind == "query") publish("presence", MessageProtocol.encodeAwareness(awareness, awareness.getStates().keys.toList()))
                    else MessageProtocol.readMessage(event.presence, document, awareness, origin = source)
                }
            }
        }
        worker { for (change in changes) reconcile() }
        // Also repairs missed notifications after broker reconnect and observes Content
        // changes made by non-editor callers through the existing MERGE operation.
        worker { while (kotlinx.coroutines.currentCoroutineContext().isActive) { delay(30_000); changes.trySend(Unit) } }
        worker {
            while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                delay(3_000)
                mutex.withLock {
                    val before = awareness.getStates().keys.toList()
                    awareness.checkTimeouts()
                    val removed = before.filter { it !in awareness.getStates() }
                    if (removed.isNotEmpty()) publish("presence", MessageProtocol.encodeAwareness(awareness, removed))
                }
            }
        }
    }

    private suspend fun publish(kind: String, presence: ByteArray = ByteArray(0)) {
        pubSub?.publish(channel, WorkOpsCollaborationEvent.serializer(), WorkOpsCollaborationEvent(source, kind, presence))
    }

    private suspend fun reconcile() = mutex.withLock {
        for (participant in participants.toList()) {
            if (participant.refreshing) continue
            val token = participant.token
            try {
                val state = content.load(room, token) ?: throw WorkOpsCollaborationDeniedException()
                applyUpdate(document, state)
                return@withLock
            } catch (failure: WorkOpsCollaborationDeniedException) {
                if (!participant.refreshing && participant.token == token) {
                    participant.send(WorkOpsCollaborationProtocol.denied(participant.name))
                    participant.close(4403, "permission-denied")
                }
            }
        }
    }

    suspend fun accept(participant: WorkOpsCollaborationParticipant, message: WorkOpsCollaborationMessage, token: String) = mutex.withLock {
        check(participant in participants) { "Collaboration participant is no longer attached" }
        try {
            participant.handler.onMessage(message.payload)
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            throw WorkOpsCollaborationProtocolException(failure)
        }
        if (message.type == WorkOpsCollaborationProtocol.SYNC && WorkOpsCollaborationProtocol.syncType(message) in 1..2) {
            applyUpdate(document, content.store(room, token, encodeStateAsUpdate(document)))
            publish("state")
            participant.send(WorkOpsCollaborationProtocol.acknowledged(participant.name))
        } else if (message.type == WorkOpsCollaborationProtocol.AWARENESS) {
            publish("presence", message.payload)
            // Echoing the accepted clock gives a lone Studio provider a receive heartbeat.
            // Awareness is idempotent and does not acknowledge document persistence.
            participant.send(WorkOpsCollaborationProtocol.frame(participant.name, message.payload))
        } else if (message.type == WorkOpsCollaborationProtocol.QUERY_AWARENESS) {
            publish("query")
        }
    }

    suspend fun disconnect(participant: WorkOpsCollaborationParticipant) = mutex.withLock {
        if (participants.remove(participant)) {
            val before = awareness.getStates().keys.toList()
            participant.handler.onDisconnect()
            val removed = before.filter { it !in awareness.getStates() }
            if (removed.isNotEmpty()) publish("presence", MessageProtocol.encodeAwareness(awareness, removed))
        }
    }

    suspend fun destroy() {
        lifetime.cancelAndJoin()
        changes.close()
        presence.close()
        document.destroy()
    }
}
