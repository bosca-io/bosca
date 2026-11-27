package bosca.workops.ui.collaboration

import bosca.server.BoscaApplication
import bosca.server.websocket.WebSocketFrame
import bosca.server.websocket.WebSocketSession
import bosca.pubsub.PubSubService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Serves Studio-compatible document collaboration from the imported WorkOps BML application. */
internal class WorkOpsCollaborationServer(
    private val application: BoscaApplication,
    graphqlEndpoint: String,
    private val permissionCheckIntervalMillis: Long = 30_000,
    private val pubSub: PubSubService? = null,
) {
    private val content = WorkOpsCollaborationContentClient(graphqlEndpoint)
    private val registry = Mutex()
    private val documents = mutableMapOf<WorkOpsCollaborationRoom, WorkOpsCollaborationDocument>()
    private val connections = ConcurrentHashMap<WebSocketSession, CompletableDeferred<Unit>>()
    private val stopping = AtomicBoolean(false)

    suspend fun activeDocumentCount(): Int = registry.withLock { documents.size }

    fun install() {
        application.router.webSocket("/collaboration", maximumMessageSize = 100 * 1024 * 1024) {
            withContext(Dispatchers.Default) { serve(this@webSocket) }
        }
        application.onShutdown {
            stopping.set(true)
            val connected = connections.entries.toList()
            connected.forEach { it.key.close(1001, "Server is stopping") }
            connected.forEach { it.value.await() }
            content.close()
        }
    }

    private suspend fun reserve(room: WorkOpsCollaborationRoom): WorkOpsCollaborationDocument = registry.withLock {
        documents.getOrPut(room) {
            WorkOpsCollaborationDocument(room, content, pubSub) { failure ->
                application.log.error("Document collaboration notifications failed", failure)
            }
        }.also { it.reservations++ }
    }

    private suspend fun release(document: WorkOpsCollaborationDocument) = withContext(NonCancellable) {
        registry.withLock {
            document.reservations--
            if (document.reservations == 0) {
                documents.remove(document.room)
                document.destroy()
            }
        }
    }

    private fun decode(bytes: ByteArray): WorkOpsCollaborationMessage = try {
        WorkOpsCollaborationProtocol.decode(bytes)
    } catch (failure: CancellationException) {
        throw failure
    } catch (failure: Exception) {
        throw WorkOpsCollaborationProtocolException(failure)
    }

    private fun token(message: WorkOpsCollaborationMessage): String = try {
        WorkOpsCollaborationProtocol.token(message)
    } catch (failure: CancellationException) {
        throw failure
    } catch (failure: Exception) {
        throw WorkOpsCollaborationProtocolException(failure)
    }

    private suspend fun serve(session: WebSocketSession) = coroutineScope {
        val finished = CompletableDeferred<Unit>()
        connections[session] = finished
        if (stopping.get()) {
            connections.remove(session)
            finished.complete(Unit)
            session.close(1001, "Server is stopping")
            return@coroutineScope
        }
        val participants = ConcurrentHashMap<String, WorkOpsCollaborationParticipant>()
        val outgoing = Channel<ByteArray>(128)
        val slowConnection = AtomicBoolean(false)
        var messageName: String? = null
        fun send(bytes: ByteArray) {
            if (outgoing.trySend(bytes).isFailure && slowConnection.compareAndSet(false, true)) {
                launch { session.close(1013, "Collaboration connection is not keeping up") }
            }
        }
        val writer = launch {
            try {
                for (bytes in outgoing) session.sendBinary(bytes)
            } catch (failure: IOException) {
                session.close(1011, "Collaboration connection closed")
            }
        }
        val permissions = launch {
            while (isActive) {
                delay(permissionCheckIntervalMillis)
                for (participant in participants.values.toList()) {
                    if (participant.refreshing) continue
                    val token = participant.token
                    try {
                        content.authorize(participant.document.room, token)
                    } catch (failure: WorkOpsCollaborationDeniedException) {
                        if (!participant.refreshing && participant.token == token) {
                            session.sendBinary(WorkOpsCollaborationProtocol.denied(participant.name))
                            session.close(4403, "permission-denied")
                            return@launch
                        }
                    } catch (failure: IOException) {
                        application.log.warn("Document collaboration permission check unavailable", failure)
                        session.close(1011, "Document collaboration is unavailable")
                        return@launch
                    }
                }
            }
        }
        try {
            while (session.isActive) {
                val frame = select<WebSocketFrame?> {
                    session.incoming.onReceiveCatching { it.getOrNull() }
                    session.closeReason.onAwait { null }
                } ?: break
                try {
                    when (frame) {
                        is WebSocketFrame.Close -> break
                        is WebSocketFrame.Binary -> {
                            val message = decode(frame.data)
                            messageName = message.name
                            if (message.type == WorkOpsCollaborationProtocol.AUTH) {
                                val token = token(message)
                                val existing = participants[message.name]
                                existing?.refreshing = true
                                try {
                                    content.authorize(message.room, token)
                                    if (existing != null) {
                                        existing.token = token
                                        send(WorkOpsCollaborationProtocol.authenticated(message.name))
                                    } else {
                                        val document = reserve(message.room)
                                        var attached = false
                                        try {
                                            send(WorkOpsCollaborationProtocol.authenticated(message.name))
                                            participants[message.name] = document.connect(message.name, token, ::send) { code, reason -> session.close(code, reason) }
                                            attached = true
                                        } finally { if (!attached) release(document) }
                                    }
                                } finally { existing?.refreshing = false }
                            } else {
                                val participant = participants[message.name] ?: throw WorkOpsCollaborationDeniedException()
                                when (message.type) {
                                    WorkOpsCollaborationProtocol.CLOSE -> {
                                        participants.remove(message.name)
                                        participant.document.disconnect(participant)
                                        release(participant.document)
                                    }
                                    WorkOpsCollaborationProtocol.SYNC -> {
                                        val token = participant.token
                                        content.authorize(message.room, token)
                                        participant.document.accept(participant, message, token)
                                    }
                                    WorkOpsCollaborationProtocol.AWARENESS, WorkOpsCollaborationProtocol.QUERY_AWARENESS ->
                                        participant.document.accept(participant, message, participant.token)
                                    else -> { session.close(1003, "Unsupported collaboration message"); break }
                                }
                            }
                        }
                        is WebSocketFrame.Text -> { session.close(1003, "Binary collaboration protocol required"); break }
                        else -> Unit
                    }
                } finally { session.frameConsumed() }
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: WorkOpsCollaborationDeniedException) {
            messageName?.let { session.sendBinary(WorkOpsCollaborationProtocol.denied(it)) }
            session.close(4403, "permission-denied")
        } catch (failure: WorkOpsCollaborationProtocolException) {
            session.close(1002, "Invalid collaboration message")
        } catch (failure: Exception) {
            application.log.error("Document collaboration failed", failure)
            session.close(1011, "Document collaboration is unavailable")
        } finally {
            withContext(NonCancellable) {
                try {
                    permissions.cancelAndJoin()
                    var disconnectFailure: Exception? = null
                    for (participant in participants.values) {
                        try { participant.document.disconnect(participant) }
                        catch (failure: Exception) {
                            if (disconnectFailure == null) disconnectFailure = failure
                            else disconnectFailure.addSuppressed(failure)
                        }
                        finally { release(participant.document) }
                    }
                    participants.clear()
                    disconnectFailure?.let { throw it }
                } finally {
                    outgoing.close()
                    writer.cancelAndJoin()
                    connections.remove(session)
                    finished.complete(Unit)
                }
            }
        }
    }
}
