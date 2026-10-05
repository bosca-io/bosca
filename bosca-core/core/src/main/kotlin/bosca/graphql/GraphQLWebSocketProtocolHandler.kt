package bosca.graphql

import bosca.server.ServerCall
import bosca.server.websocket.WebSocketFrame
import bosca.server.websocket.WebSocketSession
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds

/**
 * Implements the [graphql-transport-ws](https://github.com/enisdenjo/graphql-ws/blob/master/PROTOCOL.md)
 * protocol over a [WebSocketSession], handling connection lifecycle messages (connection_init,
 * ping/pong), subscription management (subscribe, complete), and idle timeout detection.
 *
 * Extracted from route configuration to enable isolated unit testing of
 * the protocol state machine without requiring DI or a real GraphQL schema.
 */
@OptIn(ExperimentalCoroutinesApi::class)
object GraphQLWebSocketProtocolHandler {

    private val log = LoggerFactory.getLogger(GraphQLWebSocketProtocolHandler::class.java)

    /**
     * Runs the graphql-transport-ws protocol loop on the given [session].
     * Returns when the session is closed, the incoming channel closes, or the idle timeout
     * is exceeded without a pong response.
     *
     * @param session the WebSocket session to handle
     * @param json the JSON serializer for encoding/decoding protocol messages
     * @param subscribe function that executes a GraphQL subscription and returns a result flow
     * @param timeoutCheckIntervalMs how often (ms) to check for idle connections
     * @param idleTimeoutMs how long (ms) a connection can be idle before a ping is sent
     */
    /** Maximum number of concurrent subscriptions allowed per WebSocket connection. */
    private const val MAX_SUBSCRIPTIONS_PER_CONNECTION = 100

    suspend fun handle(
        session: WebSocketSession,
        json: Json,
        subscribe: suspend (ServerCall, GraphQLRequest) -> Flow<JsonElement>,
        timeoutCheckIntervalMs: Long = 3_000,
        idleTimeoutMs: Long = 15_000,
        connectionInitTimeoutMs: Long = 15_000,
        maxSubscriptions: Int = MAX_SUBSCRIPTIONS_PER_CONNECTION,
        timeSource: () -> Long = System::currentTimeMillis,
        onConnectionInit: (suspend (ServerCall, JsonElement?) -> Boolean)? = null,
    ) {
        var closed = false
        var needsPing = false
        var connectionInitialized = false
        var lastMessage = timeSource()
        // The init deadline runs from the connection's start: counted from the last message, a
        // client could postpone initialising (authenticating) forever by sending anything else.
        val connectedAt = lastMessage
        val subscriptions = ConcurrentHashMap<String, Job>()
        try {
            while (session.isActive && !closed) {
                // Checked every round, not only when the connection goes quiet, so steady traffic
                // cannot keep an uninitialised connection alive past its deadline either.
                if (!connectionInitialized && timeSource() - connectedAt > connectionInitTimeoutMs) {
                    log.debug("Connection init timeout exceeded, closing connection")
                    session.close(4408, "Connection initialisation timeout")
                    break
                }
                select {
                    session.incoming.onReceiveCatching { frame ->
                        if (frame.isClosed) {
                            closed = true
                            return@onReceiveCatching
                        }

                        if (frame.isFailure) {
                            closed = true
                            return@onReceiveCatching
                        }

                        val wsFrame = frame.getOrNull()
                        if (wsFrame == null || wsFrame !is WebSocketFrame.Text) {
                            session.frameConsumed()
                            return@onReceiveCatching
                        }

                        session.frameConsumed()
                        lastMessage = timeSource()
                        needsPing = false

                        val data = wsFrame.readText()
                        try {
                            val request = json.decodeFromString(GraphQLSubscriptionRequest.serializer(), data)
                            log.debug("WS received message type={} id={}", request.type, request.id)
                            when (request.type) {
                                "ping" -> {
                                    session.send(json.encodeToString(GraphQLSubscriptionResponse.serializer(), GraphQLSubscriptionResponse(type = "pong")))
                                }

                                "pong" -> {}

                                "connection_init" -> {
                                    if (connectionInitialized) {
                                        log.warn("Duplicate connection_init received, closing with 4429")
                                        session.close(4429, "Too many initialisation requests")
                                        closed = true
                                        return@onReceiveCatching
                                    }
                                    // Invoke the optional auth callback with the connection_init payload.
                                    // SPA clients (e.g. Apollo Client) pass tokens via connectionParams.
                                    if (onConnectionInit != null) {
                                        val authorized = try {
                                            onConnectionInit(session.call, request.payload)
                                        } catch (e: Exception) {
                                            log.warn("connection_init auth callback failed: {}", e.message)
                                            false
                                        }
                                        if (!authorized) {
                                            log.warn("connection_init auth rejected, closing with 4401")
                                            session.send(json.encodeToString(
                                                GraphQLSubscriptionResponse.serializer(),
                                                GraphQLSubscriptionResponse(type = "connection_error", payload = JsonObject(mapOf("message" to JsonPrimitive("Unauthorized"))))
                                            ))
                                            session.close(4401, "Unauthorized")
                                            closed = true
                                            return@onReceiveCatching
                                        }
                                    }
                                    connectionInitialized = true
                                    log.debug("WS sending connection_ack")
                                    session.send(json.encodeToString(GraphQLSubscriptionResponse.serializer(), GraphQLSubscriptionResponse(type = "connection_ack")))
                                    log.debug("WS connection_ack sent successfully")
                                }

                                "subscribe" -> {
                                    if (!connectionInitialized) {
                                        log.warn("Subscribe received before connection_init, closing with 4401")
                                        session.send(json.encodeToString(
                                            GraphQLSubscriptionResponse.serializer(),
                                            GraphQLSubscriptionResponse(type = "connection_error", payload = JsonObject(mapOf("message" to JsonPrimitive("Unauthorized"))))
                                        ))
                                        session.close(4401, "Unauthorized")
                                        closed = true
                                        return@onReceiveCatching
                                    }
                                    if (subscriptions.size >= maxSubscriptions) {
                                        log.warn("Max subscriptions ({}) exceeded, closing connection", maxSubscriptions)
                                        session.close(4409, "Too many subscriptions")
                                        closed = true
                                        return@onReceiveCatching
                                    }
                                    val id = request.id
                                    val payload = request.payload
                                    if (id == null || payload == null) {
                                        log.warn("Subscribe message missing id or payload")
                                        return@onReceiveCatching
                                    }
                                    val gqlRequest = json.decodeFromJsonElement<GraphQLRequest>(payload)
                                    if (subscriptions.containsKey(id)) {
                                        log.warn("Duplicate subscription id={}, closing with 4409", id)
                                        session.close(4409, "Subscriber for $id already exists")
                                        closed = true
                                        return@onReceiveCatching
                                    }
                                    // Registered before it starts: a subscription that ends at once (a failed or
                                    // empty subscribe) must find its entry to remove, not leave a dead one behind.
                                    val job = session.launch(start = CoroutineStart.LAZY) {
                                        try {
                                            log.debug("WS subscription id={} calling subscribe", id)
                                            val flow = subscribe(session.call, gqlRequest)
                                            log.debug("WS subscription id={} subscribe returned, starting collect", id)
                                            flow
                                                .onCompletion { cause ->
                                                    log.debug("WS subscription id={} completed, cause={}", id, cause?.message)
                                                    if (cause == null) {
                                                        try {
                                                            session.send(
                                                                JsonObject(
                                                                    mapOf(
                                                                        "id" to JsonPrimitive(id),
                                                                        "type" to JsonPrimitive("complete"),
                                                                    )
                                                                ).toString()
                                                            )
                                                        } catch (e: Exception) {
                                                            log.debug("Failed to send completion for id={}: {}", id, e.message)
                                                        }
                                                    }
                                                }
                                                .collect {
                                                    log.debug("WS subscription id={} emitting next event", id)
                                                    session.send(
                                                        JsonObject(
                                                            mapOf(
                                                                "id" to JsonPrimitive(id),
                                                                "type" to JsonPrimitive("next"),
                                                                "payload" to it
                                                            )
                                                        ).toString()
                                                    )
                                                }
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (e: Exception) {
                                            log.error("Subscription error for id={}", id, e)
                                            try {
                                                session.send(
                                                    JsonObject(
                                                        mapOf(
                                                            "id" to JsonPrimitive(id),
                                                            "type" to JsonPrimitive("error"),
                                                            "payload" to JsonArray(
                                                                listOf(
                                                                    JsonObject(
                                                                        mapOf("message" to JsonPrimitive("Subscription error"))
                                                                    )
                                                                )
                                                            )
                                                        )
                                                    ).toString()
                                                )
                                            } catch (sendError: Exception) {
                                                log.debug("Failed to send error for id={}: {}", id, sendError.message)
                                            }
                                        } finally {
                                            // Only this subscription's own entry: the id may since have been reused.
                                            subscriptions.remove(id, coroutineContext.job)
                                        }
                                    }
                                    subscriptions[id] = job
                                    job.start()
                                }

                                "complete" -> {
                                    val id = request.id
                                    if (id != null) {
                                        val removed = subscriptions.remove(id)
                                        if (removed != null) {
                                            removed.cancel()
                                        } else {
                                            log.debug("Received complete for non-existent subscription id={}", id)
                                        }
                                    }
                                }

                                else -> {
                                    log.warn("Unknown subscription type: {}", request.type)
                                }
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            log.warn("Error processing WebSocket message: {}", e.message)
                        }
                    }
                    onTimeout(timeoutCheckIntervalMs.milliseconds) {
                        if (timeSource() - lastMessage > idleTimeoutMs) {
                            if (!needsPing) {
                                needsPing = true
                                session.send(json.encodeToString(GraphQLSubscriptionResponse.serializer(), GraphQLSubscriptionResponse(type = "ping")))
                            } else {
                                log.debug("WebSocket idle timeout exceeded, closing connection")
                                session.close(4408, "Connection idle timeout")
                                closed = true
                            }
                        }
                    }
                    session.closeReason.onAwait {
                        closed = true
                    }
                }
            }
        } finally {
            // Cancel all active subscription jobs on disconnect
            subscriptions.values.forEach { it.cancel(CancellationException("WebSocket connection closed")) }
            subscriptions.clear()
        }
    }
}
