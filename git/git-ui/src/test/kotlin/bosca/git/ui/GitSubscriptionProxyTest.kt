package bosca.git.ui

import bosca.graphql.GraphQLWebSocketProtocolHandler
import bosca.server.BoscaApplication
import bosca.server.ServerCall
import bosca.server.config.ApplicationConfig
import bosca.server.middleware.CallMiddleware
import bosca.server.netty.NettyServerEngine
import bosca.server.websocket.WebSocketFrame
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.*

/**
 * Exercises real Netty upgrades and the established GraphQL protocol through the app-origin relay.
 */
class GitSubscriptionProxyTest {
    @Test
    fun `browser close without status and application close cross the relay cleanly`() {
        val upstreamPort = ServerSocket(0).use { it.localPort }
        val uiPort = ServerSocket(0).use { it.localPort }
        val closes = LinkedBlockingQueue<WebSocketFrame.Close>()
        val failures = CopyOnWriteArrayList<Throwable>()
        val finished = LinkedBlockingQueue<Unit>()
        val upstream = application(upstreamPort)
        upstream.router.webSocket("/graphqlws", "graphql-transport-ws") {
            send("ready")
            for (frame in incoming) {
                frameConsumed()
                if (frame is WebSocketFrame.Close) {
                    closes.add(frame)
                    break
                }
            }
        }
        val ui = application(uiPort)
        ui.install(object : CallMiddleware {
            override suspend fun onException(call: ServerCall, cause: Throwable) {
                failures.add(cause)
            }

            override suspend fun afterCall(call: ServerCall) {
                finished.add(Unit)
            }
        })
        installGitSubscriptions(ui, "http://localhost:$upstreamPort/graphql")
        val engines = listOf(start(upstream, upstreamPort), start(ui, uiPort))
        try {
            for (code in listOf(null, 1000, 4200)) {
                openSubscriptionSocket(uiPort).use { socket ->
                    assertEquals("ready", readPayload(socket, 1).toString(Charsets.UTF_8))
                    val reason = if (code == null) "" else "finished"
                    val payload = if (code == null) byteArrayOf() else {
                        byteArrayOf((code ushr 8).toByte(), code.toByte()) + reason.toByteArray()
                    }
                    // A masked, empty close is what a browser's WebSocket.close() sends.
                    socket.getOutputStream().apply {
                        write(byteArrayOf(0x88.toByte(), (0x80 or payload.size).toByte(), 0, 0, 0, 0) + payload)
                        flush()
                    }
                    assertTrue(payload.contentEquals(readPayload(socket, 8)), "The browser receives its close echo")
                    assertTrue(finished.poll(5, TimeUnit.SECONDS) != null, "Relay must finish")
                    assertTrue(failures.isEmpty(), "Relay failures: $failures")
                    val close = requireNotNull(closes.poll(5, TimeUnit.SECONDS)) { "Upstream did not receive a close" }
                    assertEquals(code ?: 1000, close.code)
                    assertEquals(reason, close.reason)
                }
            }
        } finally {
            engines.forEach { it.stopWithoutHalting() }
        }
    }

    @Test
    fun `SDK init subscription results completion and auth rejection cross the relay`() {
        val upstreamPort = ServerSocket(0).use { it.localPort }
        val uiPort = ServerSocket(0).use { it.localPort }
        val frames = CopyOnWriteArrayList<String>()
        val upstream = application(upstreamPort)
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
        val ui = application(uiPort)
        installGitSubscriptions(ui, "http://localhost:$upstreamPort/graphql")
        val engines = listOf(start(upstream, upstreamPort), start(ui, uiPort))
        try {
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
            engines.forEach { it.stopWithoutHalting() }
        }
    }

    @Test
    fun `endpoint retains configured host and path prefix`() {
        assertEquals(
            "wss://example.test/api/graphqlws",
            subscriptionEndpoint("https://example.test/api/graphql").toString(),
        )
    }

    private fun application(port: Int): BoscaApplication =
        BoscaApplication(
            ApplicationConfig.load(
                "bosca:\n  server:\n    port: $port\n    drain-timeout-ms: 0\n    development: true\n"
                    .byteInputStream()
            )
        )

    private fun openSubscriptionSocket(port: Int): Socket {
        val socket = Socket("localhost", port).apply { soTimeout = 5_000 }
        try {
            socket.getOutputStream().apply {
                write((
                    "GET /_git/subscriptions HTTP/1.1\r\n" +
                        "Host: localhost:$port\r\n" +
                        "Upgrade: websocket\r\nConnection: Upgrade\r\n" +
                        "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" +
                        "Sec-WebSocket-Version: 13\r\n" +
                        "Sec-WebSocket-Protocol: graphql-transport-ws\r\n\r\n"
                    ).toByteArray(Charsets.US_ASCII))
                flush()
            }
            val headers = StringBuilder()
            while (!headers.endsWith("\r\n\r\n")) {
                val byte = socket.getInputStream().read()
                check(byte >= 0) { "Connection ended during handshake" }
                headers.append(byte.toChar())
            }
            assertTrue(headers.startsWith("HTTP/1.1 101"), headers.toString())
            return socket
        } catch (cause: Throwable) {
            socket.close()
            throw cause
        }
    }

    private fun readPayload(socket: Socket, opcode: Int): ByteArray {
        val input = socket.getInputStream()
        assertEquals(0x80 or opcode, input.read())
        val length = input.read()
        check(length in 0..125) { "Expected a short, unmasked frame, got length byte $length" }
        return input.readNBytes(length).also { assertEquals(length, it.size) }
    }

    private fun start(application: BoscaApplication, port: Int): NettyServerEngine {
        application.freezeMiddleware()
        val engine = NettyServerEngine(application, port)
        thread(isDaemon = true) { engine.start() }
        repeat(100) {
            try {
                java.net.Socket("localhost", port).close()
                return engine
            } catch (_: java.io.IOException) {
                Thread.sleep(20)
            }
        }
        error("Test server did not start")
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
