package bosca.git.ui

import bosca.server.BoscaApplication
import bosca.server.websocket.CloseReason
import bosca.server.websocket.WebSocketFrame
import bosca.server.websocket.WebSocketSession
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select

/** Derives the established GraphQL subscription endpoint from the configured HTTP API. */
internal fun subscriptionEndpoint(graphqlEndpoint: String): URI {
    val endpoint = URI(graphqlEndpoint)
    val path = endpoint.path.trimEnd('/').substringBeforeLast('/', "") + "/graphqlws"
    return URI(
        if (endpoint.scheme == "https") "wss" else "ws",
        endpoint.userInfo,
        endpoint.host,
        endpoint.port,
        path,
        endpoint.query,
        null,
    )
}

/**
 * Relays the existing subscription protocol on the UI origin. The upstream authenticates the SDK's
 * connection_init token and authorizes each subscription; the relay does not mint or inspect
 * tokens.
 */
fun installGitSubscriptions(application: BoscaApplication, graphqlEndpoint: String) {
    val endpoint = subscriptionEndpoint(graphqlEndpoint)
    application.router.webSocket("/_git/subscriptions", "graphql-transport-ws") {
        relaySubscriptions(endpoint)
    }
}

private val subscriptionHttpClient =
    HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

private suspend fun WebSocketSession.relaySubscriptions(endpoint: URI) = coroutineScope {
    val session = this@relaySubscriptions
    val closed = CompletableDeferred<Unit>()
    val listener =
        object : WebSocket.Listener {
            private val message = StringBuilder()

            override fun onOpen(socket: WebSocket) {
                socket.request(1)
            }

            override fun onText(
                socket: WebSocket,
                data: CharSequence,
                last: Boolean,
            ): CompletionStage<*> {
                message.append(data)
                if (!last) {
                    socket.request(1)
                    return CompletableFuture.completedFuture(null)
                }
                val text = message.toString()
                message.setLength(0)
                val sent = CompletableFuture<Void>()
                launch {
                    try {
                        session.send(text)
                        sent.complete(null)
                        socket.request(1)
                    } catch (e: CancellationException) {
                        sent.completeExceptionally(e)
                        throw e
                    } catch (e: Exception) {
                        sent.completeExceptionally(e)
                        socket.abort()
                        closed.complete(Unit)
                    }
                }
                return sent
            }

            override fun onClose(
                socket: WebSocket,
                statusCode: Int,
                reason: String,
            ): CompletionStage<*> {
                launch {
                    try {
                        session.close(statusCode, reason)
                    } finally {
                        closed.complete(Unit)
                    }
                }
                return CompletableFuture.completedFuture(null)
            }

            override fun onError(socket: WebSocket, error: Throwable) {
                launch {
                    try {
                        session.close(1011, "Subscription upstream disconnected")
                    } finally {
                        closed.complete(Unit)
                    }
                }
            }
        }
    val upstream =
        try {
            subscriptionHttpClient
                .newWebSocketBuilder()
                .subprotocols("graphql-transport-ws")
                .buildAsync(endpoint, listener)
                .await()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            session.close(1011, "Subscription upstream unavailable")
            return@coroutineScope
        }
    val forwarding = launch {
        for (frame in session.incoming) {
            try {
                when (frame) {
                    is WebSocketFrame.Text -> upstream.sendText(frame.text, true).await()
                    is WebSocketFrame.Close -> {
                        // An empty browser close is reported as 1005, which is never a wire status.
                        // Java's client requires a status, so close the upstream normally in that case.
                        val code = if (frame.code == CloseReason.Codes.NO_STATUS_RECEIVED) {
                            WebSocket.NORMAL_CLOSURE
                        } else {
                            frame.code
                        }
                        upstream.sendClose(code, frame.reason).await()
                        break
                    }
                    is WebSocketFrame.Binary -> {
                        session.close(1003, "Text protocol required")
                        break
                    }
                    else -> Unit
                }
            } finally {
                session.frameConsumed()
            }
        }
    }
    try {
        select<Unit> {
            closed.onAwait {}
            forwarding.onJoin {}
        }
    } finally {
        forwarding.cancelAndJoin()
        upstream.abort()
    }
}
