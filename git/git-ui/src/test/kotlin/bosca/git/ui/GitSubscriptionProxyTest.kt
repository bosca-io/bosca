package bosca.git.ui

import bosca.graphql.GraphQLWebSocketProtocolHandler
import bosca.server.BoscaApplication
import bosca.server.config.ApplicationConfig
import bosca.server.netty.NettyServerEngine
import java.net.BindException
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.*

/**
 * Exercises real Netty upgrades and the established GraphQL protocol through the app-origin relay.
 */
class GitSubscriptionProxyTest {
    @Test
    fun `SDK init subscription results completion and auth rejection cross the relay`() {
        val frames = CopyOnWriteArrayList<String>()
        val upstream = application()
        upstream.router.webSocket("/graphqlws", "graphql-transport-ws") {
            GraphQLWebSocketProtocolHandler.handle(
                this,
                Json,
                subscribe = { _, request ->
                    frames.add(request.variables.toString())
                    flow {
                        emit(
                            Json.parseToJsonElement(
                                """{"data":{"pipelineStepLogs":{"lineNumber":4,"content":"first","stream":"STDOUT","timestamp":"now"}}}"""
                            )
                        )
                        emit(
                            Json.parseToJsonElement(
                                """{"data":{"pipelineStepLogs":{"lineNumber":9,"content":"<script>second</script>","stream":"STDERR","timestamp":"now"}}}"""
                            )
                        )
                    }
                },
                onConnectionInit = { _, payload ->
                    val token = payload?.jsonObject?.get("authToken")?.jsonPrimitive?.content
                    frames.add("init:$token")
                    token == "sdk-token"
                },
            )
        }
        val engines = mutableListOf<NettyServerEngine>()
        try {
            val upstreamEngine = start(upstream).also(engines::add)
            val upstreamPort = checkNotNull(upstreamEngine.boundPort)
            val ui = application()
            installGitSubscriptions(ui, "http://localhost:$upstreamPort/graphql")
            val uiEngine = start(ui).also(engines::add)
            val uiPort = checkNotNull(uiEngine.boundPort)
            val messages = Messages()
            val socket =
                HttpClient.newHttpClient()
                    .newWebSocketBuilder()
                    .subprotocols("graphql-transport-ws")
                    .buildAsync(URI("ws://localhost:$uiPort/_git/subscriptions"), messages)
                    .get(5, TimeUnit.SECONDS)
            assertEquals("graphql-transport-ws", socket.subprotocol)
            socket
                .sendText(
                    """{"type":"connection_init","payload":{"authToken":"sdk-token"}}""",
                    true,
                )
                .get(5, TimeUnit.SECONDS)
            assertEquals("connection_ack", messages.next()["type"]?.jsonPrimitive?.content)
            socket
                .sendText(
                    """{"id":"step","type":"subscribe","payload":{"query":"subscription Logs { pipelineStepLogs }","variables":{"repositoryId":"repo","stepId":"step"}}}""",
                    true,
                )
                .get(5, TimeUnit.SECONDS)
            val first = messages.next()
            val second = messages.next()
            assertEquals(
                4,
                first["payload"]
                    ?.jsonObject
                    ?.get("data")
                    ?.jsonObject
                    ?.get("pipelineStepLogs")
                    ?.jsonObject
                    ?.get("lineNumber")
                    ?.jsonPrimitive
                    ?.int,
            )
            assertEquals(
                "<script>second</script>",
                second["payload"]
                    ?.jsonObject
                    ?.get("data")
                    ?.jsonObject
                    ?.get("pipelineStepLogs")
                    ?.jsonObject
                    ?.get("content")
                    ?.jsonPrimitive
                    ?.content,
            )
            assertEquals("complete", messages.next()["type"]?.jsonPrimitive?.content)
            assertEquals("init:sdk-token", frames.first())
            assertTrue(frames.last().contains("repositoryId") && frames.last().contains("stepId"))
            socket.sendClose(1000, "complete").get(5, TimeUnit.SECONDS)
            val denied = Messages()
            val deniedSocket =
                HttpClient.newHttpClient()
                    .newWebSocketBuilder()
                    .subprotocols("graphql-transport-ws")
                    .buildAsync(URI("ws://localhost:$uiPort/_git/subscriptions"), denied)
                    .get(5, TimeUnit.SECONDS)
            deniedSocket
                .sendText("""{"type":"connection_init","payload":{"authToken":"invalid"}}""", true)
                .get(5, TimeUnit.SECONDS)
            assertEquals(4401, denied.close.get(5, TimeUnit.SECONDS))
            deniedSocket.abort()
        } finally {
            engines.asReversed().forEach { it.stopWithoutHalting() }
        }
    }

    @Test
    fun `endpoint retains configured host and path prefix`() {
        assertEquals(
            "wss://example.test/api/graphqlws",
            subscriptionEndpoint("https://example.test/api/graphql").toString(),
        )
    }

    @Test
    fun `occupied listener cannot satisfy failed server startup`() {
        ServerSocket(0).use { occupied ->
            var engine: NettyServerEngine? = null
            try {
                assertFailsWith<BindException> {
                    engine = start(application(occupied.localPort), occupied.localPort)
                }
            } finally {
                engine?.stopWithoutHalting()
            }
        }
    }

    private fun application(port: Int = 0): BoscaApplication =
        BoscaApplication(
            ApplicationConfig.load(
                "bosca:\n  server:\n    port: $port\n    drain-timeout-ms: 0\n    development: true\n"
                    .byteInputStream()
            )
        )

    private fun start(application: BoscaApplication, port: Int = 0): NettyServerEngine {
        application.freezeMiddleware()
        val engine = NettyServerEngine(application, port)
        val finished = CompletableFuture<Unit>()
        thread(isDaemon = true, name = "git-subscription-test-server") {
            try {
                engine.start()
                finished.complete(Unit)
            } catch (failure: Exception) {
                finished.completeExceptionally(failure)
                if (failure is CancellationException) throw failure
            }
        }
        var ready = false
        try {
            repeat(100) {
                if (finished.isDone) {
                    try {
                        finished.get()
                    } catch (failure: ExecutionException) {
                        throw checkNotNull(failure.cause)
                    }
                    error("Test server stopped before becoming ready")
                }
                if (engine.boundPort != null) {
                    ready = true
                    return engine
                }
                Thread.sleep(20)
            }
            error("Test server did not start")
        } finally {
            if (!ready) engine.stopWithoutHalting()
        }
    }

    private class Messages : WebSocket.Listener {
        val close = CompletableFuture<Int>()
        private val messages = LinkedBlockingQueue<String>()
        private val text = StringBuilder()

        override fun onOpen(socket: WebSocket) {
            socket.request(1)
        }

        override fun onText(
            socket: WebSocket,
            data: CharSequence,
            last: Boolean,
        ): CompletionStage<*> {
            text.append(data)
            if (last) {
                messages.add(text.toString())
                text.setLength(0)
            }
            socket.request(1)
            return CompletableFuture.completedFuture(null)
        }

        override fun onClose(
            socket: WebSocket,
            statusCode: Int,
            reason: String,
        ): CompletionStage<*> {
            close.complete(statusCode)
            return CompletableFuture.completedFuture(null)
        }

        override fun onError(socket: WebSocket, error: Throwable) {
            close.completeExceptionally(error)
        }

        fun next(): JsonObject =
            Json.parseToJsonElement(
                    requireNotNull(messages.poll(5, TimeUnit.SECONDS)) {
                        "No subscription message received"
                    }
                )
                .jsonObject
    }
}
