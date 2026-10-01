package bosca.graphql

import bosca.server.BoscaApplication
import bosca.server.Parameters
import bosca.server.ServerCall
import bosca.server.ServerRequest
import bosca.server.ServerResponse
import bosca.server.config.ApplicationConfig
import bosca.server.websocket.WebSocketFrame
import bosca.server.websocket.WebSocketSession
import io.mockk.every
import io.mockk.mockk
import io.netty.buffer.UnpooledByteBufAllocator
import io.netty.channel.Channel
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelPromise
import io.netty.channel.DefaultChannelPromise
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame
import io.netty.util.AttributeKey
import io.netty.util.DefaultAttributeMap
import io.netty.util.concurrent.GenericFutureListener
import io.netty.util.concurrent.ImmediateEventExecutor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GraphQLWebSocketProtocolHandlerTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun createSuccessfulFuture(): ChannelFuture {
        val future = mockk<ChannelFuture>(relaxed = true)
        every { future.isSuccess } returns true
        every { future.cause() } returns null
        every { future.addListener(any()) } answers {
            @Suppress("UNCHECKED_CAST")
            val listener = firstArg<GenericFutureListener<ChannelFuture>>()
            listener.operationComplete(future)
            future
        }
        return future
    }

    private fun createChannel(active: Boolean = true): Channel {
        val channel = mockk<Channel>(relaxed = true)
        // Real channels always carry attributes; read backpressure keeps its state there.
        val attributes = DefaultAttributeMap()
        every { channel.attr(any<AttributeKey<Any>>()) } answers { attributes.attr(firstArg<AttributeKey<Any>>()) }
        every { channel.isActive } returns active
        every { channel.writeAndFlush(any()) } answers { createSuccessfulFuture() }
        // session.close() reserves its write with a promise so a session sends at most one close frame.
        every { channel.newPromise() } answers { DefaultChannelPromise(channel, ImmediateEventExecutor.INSTANCE) }
        every { channel.writeAndFlush(any(), any()) } answers { secondArg<ChannelPromise>().setSuccess() }
        every { channel.flush() } returns channel
        // Sends run their close check and write on the event loop; this test thread stands in for it.
        every { channel.eventLoop() } returns mockk<io.netty.channel.EventLoop> { every { inEventLoop() } returns true }
        return channel
    }

    private fun createCall(): ServerCall {
        val ctx = mockk<ChannelHandlerContext>(relaxed = true)
        every { ctx.alloc() } returns UnpooledByteBufAllocator.DEFAULT
        every { ctx.writeAndFlush(any()) } returns mockk(relaxed = true)
        val request = mockk<ServerRequest>(relaxed = true)
        val response = ServerResponse(ctx)
        val config = mockk<ApplicationConfig>(relaxed = true)
        every { config.propertyOrNull(any()) } returns null
        val app = BoscaApplication(config)
        return ServerCall(request, response, Parameters.Empty, app)
    }

    /**
     * Creates a [WebSocketSession] that captures sent text messages into [sentMessages].
     * The session's channel is backed by a mock that records writeAndFlush calls.
     */
    private fun createTestSession(
        sentMessages: CopyOnWriteArrayList<String>,
        scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    ): WebSocketSession {
        val channel = createChannel()
        every { channel.writeAndFlush(any(), any()) } answers {
            val arg = firstArg<Any>()
            if (arg is TextWebSocketFrame) {
                sentMessages.add(arg.text())
            }
            secondArg<ChannelPromise>().setSuccess()
        }
        return WebSocketSession(createCall(), channel, scope.coroutineContext)
    }

    private fun noOpSubscribe(): suspend (ServerCall, GraphQLRequest) -> Flow<JsonElement> =
        { _, _ -> flowOf() }

    // --- Connection Initialization ---

    @Test
    fun `connection_init responds with connection_ack`() {
        val sent = CopyOnWriteArrayList<String>()
        val session = createTestSession(sent)
        val done = CountDownLatch(1)

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        scope.launch {
            GraphQLWebSocketProtocolHandler.handle(session, json, noOpSubscribe())
            done.countDown()
        }

        kotlinx.coroutines.runBlocking {
            session._incoming.send(WebSocketFrame.Text("""{"type":"connection_init"}"""))
            delay(200)
            session.closeReason.complete(null)
        }

        assertTrue(done.await(5, TimeUnit.SECONDS))
        val ackMessages = sent.filter { it.contains("connection_ack") }
        assertEquals(1, ackMessages.size)
    }

    @Test
    fun `connection_init with payload still responds with connection_ack`() {
        val sent = CopyOnWriteArrayList<String>()
        val session = createTestSession(sent)
        val done = CountDownLatch(1)

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        scope.launch {
            GraphQLWebSocketProtocolHandler.handle(session, json, noOpSubscribe())
            done.countDown()
        }

        kotlinx.coroutines.runBlocking {
            session._incoming.send(WebSocketFrame.Text("""{"type":"connection_init","payload":{"token":"abc"}}"""))
            delay(200)
            session.closeReason.complete(null)
        }

        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertTrue(sent.any { it.contains("connection_ack") })
    }

    // --- Ping/Pong ---

    @Test
    fun `ping from client receives pong`() {
        val sent = CopyOnWriteArrayList<String>()
        val session = createTestSession(sent)
        val done = CountDownLatch(1)

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        scope.launch {
            GraphQLWebSocketProtocolHandler.handle(session, json, noOpSubscribe())
            done.countDown()
        }

        kotlinx.coroutines.runBlocking {
            session._incoming.send(WebSocketFrame.Text("""{"type":"ping"}"""))
            delay(200)
            session.closeReason.complete(null)
        }

        assertTrue(done.await(5, TimeUnit.SECONDS))
        val pongMessages = sent.filter { it.contains(""""type":"pong"""") }
        assertEquals(1, pongMessages.size)
    }

    @Test
    fun `pong from client is silently accepted`() {
        val sent = CopyOnWriteArrayList<String>()
        val session = createTestSession(sent)
        val done = CountDownLatch(1)

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        scope.launch {
            GraphQLWebSocketProtocolHandler.handle(session, json, noOpSubscribe())
            done.countDown()
        }

        kotlinx.coroutines.runBlocking {
            session._incoming.send(WebSocketFrame.Text("""{"type":"pong"}"""))
            delay(200)
            session.closeReason.complete(null)
        }

        assertTrue(done.await(5, TimeUnit.SECONDS))
        // No response should be sent for pong
        assertTrue(sent.isEmpty(), "No message should be sent in response to pong, but got: $sent")
    }

    // --- Subscription Lifecycle ---

    @Test
    fun `subscribe launches flow and sends next messages`() {
        val sent = CopyOnWriteArrayList<String>()
        val session = createTestSession(sent)
        val payload1 = JsonObject(mapOf("data" to JsonObject(mapOf("updates" to JsonPrimitive("event1")))))
        val payload2 = JsonObject(mapOf("data" to JsonObject(mapOf("updates" to JsonPrimitive("event2")))))

        val subscribe: suspend (ServerCall, GraphQLRequest) -> Flow<JsonElement> = { _, _ ->
            flowOf(payload1, payload2)
        }
        val done = CountDownLatch(1)

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        scope.launch {
            GraphQLWebSocketProtocolHandler.handle(session, json, subscribe)
            done.countDown()
        }

        kotlinx.coroutines.runBlocking {
            session._incoming.send(WebSocketFrame.Text("""{"type":"connection_init"}"""))
            delay(100)
            session._incoming.send(WebSocketFrame.Text(
                """{"type":"subscribe","id":"sub-1","payload":{"query":"subscription { updates }"}}"""
            ))
            delay(500)
            session.closeReason.complete(null)
        }

        assertTrue(done.await(5, TimeUnit.SECONDS))

        val nextMessages = sent.filter { it.contains(""""type":"next"""") }
        assertEquals(2, nextMessages.size, "Expected 2 next messages, got: $nextMessages")

        // Verify the messages have the correct subscription id
        assertTrue(nextMessages.all { it.contains(""""id":"sub-1"""") })
    }

    @Test
    fun `complete cancels active subscription`() {
        val sent = CopyOnWriteArrayList<String>()
        val session = createTestSession(sent)
        val subscriptionCancelled = CountDownLatch(1)

        val subscribe: suspend (ServerCall, GraphQLRequest) -> Flow<JsonElement> = { _, _ ->
            flow {
                emit(JsonPrimitive("first"))
                try {
                    delay(Long.MAX_VALUE) // Suspend forever until cancelled
                } finally {
                    subscriptionCancelled.countDown()
                }
            }
        }
        val done = CountDownLatch(1)

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        scope.launch {
            GraphQLWebSocketProtocolHandler.handle(session, json, subscribe)
            done.countDown()
        }

        kotlinx.coroutines.runBlocking {
            session._incoming.send(WebSocketFrame.Text("""{"type":"connection_init"}"""))
            delay(100)
            session._incoming.send(WebSocketFrame.Text("""{"type":"subscribe","id":"sub-1","payload":{"query":"subscription { x }"}}"""))
            delay(300) // Wait for first emission
            session._incoming.send(WebSocketFrame.Text("""{"type":"complete","id":"sub-1"}"""))
            delay(200)
            session.closeReason.complete(null)
        }

        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertTrue(subscriptionCancelled.await(5, TimeUnit.SECONDS), "Subscription should be cancelled")

        // Should have received at least one next message before cancellation
        assertTrue(sent.any { it.contains(""""type":"next"""") })
    }

    @Test
    fun `multiple concurrent subscriptions tracked independently`() {
        val sent = CopyOnWriteArrayList<String>()
        val session = createTestSession(sent)
        val sub1Started = CountDownLatch(1)
        val sub2Started = CountDownLatch(1)
        val sub1Cancelled = CountDownLatch(1)

        val subscribe: suspend (ServerCall, GraphQLRequest) -> Flow<JsonElement> = { _, req ->
            val query = req.query ?: ""
            flow {
                if (query.contains("sub1")) {
                    sub1Started.countDown()
                    emit(JsonPrimitive("from-sub1"))
                    try { delay(Long.MAX_VALUE) } finally { sub1Cancelled.countDown() }
                } else {
                    sub2Started.countDown()
                    emit(JsonPrimitive("from-sub2"))
                    try { delay(Long.MAX_VALUE) } catch (_: Exception) {}
                }
            }
        }
        val done = CountDownLatch(1)

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        scope.launch {
            GraphQLWebSocketProtocolHandler.handle(session, json, subscribe)
            done.countDown()
        }

        kotlinx.coroutines.runBlocking {
            session._incoming.send(WebSocketFrame.Text("""{"type":"connection_init"}"""))
            delay(100)
            session._incoming.send(WebSocketFrame.Text(
                """{"type":"subscribe","id":"s1","payload":{"query":"subscription { sub1 }"}}"""
            ))
            session._incoming.send(WebSocketFrame.Text(
                """{"type":"subscribe","id":"s2","payload":{"query":"subscription { sub2 }"}}"""
            ))
            assertTrue(sub1Started.await(5, TimeUnit.SECONDS))
            assertTrue(sub2Started.await(5, TimeUnit.SECONDS))
            delay(200)

            // Complete only sub1
            session._incoming.send(WebSocketFrame.Text("""{"type":"complete","id":"s1"}"""))
            assertTrue(sub1Cancelled.await(5, TimeUnit.SECONDS), "sub1 should be cancelled")

            delay(200)
            session.closeReason.complete(null)
        }

        assertTrue(done.await(5, TimeUnit.SECONDS))

        // Both subscriptions should have sent next messages
        val s1Messages = sent.filter { it.contains(""""id":"s1"""") && it.contains(""""type":"next"""") }
        val s2Messages = sent.filter { it.contains(""""id":"s2"""") && it.contains(""""type":"next"""") }
        assertTrue(s1Messages.isNotEmpty(), "sub1 should have sent messages")
        assertTrue(s2Messages.isNotEmpty(), "sub2 should have sent messages")
    }

    // --- Unknown Message Type ---

    @Test
    fun `unknown message type does not crash handler`() {
        val sent = CopyOnWriteArrayList<String>()
        val session = createTestSession(sent)
        val done = CountDownLatch(1)

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        scope.launch {
            GraphQLWebSocketProtocolHandler.handle(session, json, noOpSubscribe())
            done.countDown()
        }

        kotlinx.coroutines.runBlocking {
            session._incoming.send(WebSocketFrame.Text("""{"type":"foobar"}"""))
            delay(100)
            // Handler should still be alive — send a ping to verify
            session._incoming.send(WebSocketFrame.Text("""{"type":"ping"}"""))
            delay(200)
            session.closeReason.complete(null)
        }

        assertTrue(done.await(5, TimeUnit.SECONDS))
        // The ping should still have been processed
        assertTrue(sent.any { it.contains(""""type":"pong"""") }, "Handler should still process messages after unknown type")
    }

    // --- Timeout/Keepalive ---

    @Test
    fun `server sends ping after idle timeout then closes on second timeout`() {
        val sent = CopyOnWriteArrayList<String>()
        val session = createTestSession(sent)
        val done = CountDownLatch(1)

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        scope.launch {
            GraphQLWebSocketProtocolHandler.handle(session, json, noOpSubscribe(), timeoutCheckIntervalMs = 200, idleTimeoutMs = 500)
            done.countDown()
        }

        // Don't send any messages — let the timeout fire
        assertTrue(done.await(5, TimeUnit.SECONDS), "Handler should exit after timeout")

        // Should have sent a ping before closing
        val pingMessages = sent.filter { it.contains(""""type":"ping"""") }
        assertTrue(pingMessages.isNotEmpty(), "Server should have sent a ping before closing")
    }

    @Test
    fun `client activity resets idle timer`() {
        val sent = CopyOnWriteArrayList<String>()
        val session = createTestSession(sent)
        val done = CountDownLatch(1)

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        scope.launch {
            GraphQLWebSocketProtocolHandler.handle(session, json, noOpSubscribe(), timeoutCheckIntervalMs = 200, idleTimeoutMs = 500)
            done.countDown()
        }

        kotlinx.coroutines.runBlocking {
            // Send pings every 200ms for 2000ms (faster than 500ms idle timeout)
            repeat(10) {
                session._incoming.send(WebSocketFrame.Text("""{"type":"ping"}"""))
                delay(200)
            }
            // No server-initiated ping should have been sent during this period
            val serverPings = sent.filter { it.contains(""""type":"ping"""") }
            assertEquals(0, serverPings.size, "No server ping during active period, got: $serverPings")

            // Now stop sending — let the timeout fire
            delay(1500)
            session.closeReason.complete(null)
        }

        assertTrue(done.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun `pong response prevents immediate close after server ping`() {
        val sent = CopyOnWriteArrayList<String>()
        val session = createTestSession(sent)
        // Use longer intervals so we have time to respond
        val done = CountDownLatch(1)

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val handlerJob = scope.launch {
            GraphQLWebSocketProtocolHandler.handle(session, json, noOpSubscribe(), timeoutCheckIntervalMs = 200, idleTimeoutMs = 500)
            done.countDown()
        }

        kotlinx.coroutines.runBlocking {
            // Poll until server sends a ping (should happen around 700-1000ms)
            var serverPingSent = false
            for (i in 0..30) {
                delay(100)
                if (sent.any { it.contains(""""type":"ping"""") }) {
                    serverPingSent = true
                    break
                }
            }
            assertTrue(serverPingSent, "Server should have sent a ping")

            // Respond with pong immediately — this resets lastMessage and needsPing
            session._incoming.send(WebSocketFrame.Text("""{"type":"pong"}"""))

            // Wait a short time — handler should NOT have closed
            delay(200)
            assertTrue(handlerJob.isActive, "Handler should still be active after pong response")

            session.closeReason.complete(null)
        }

        assertTrue(done.await(5, TimeUnit.SECONDS))
    }

    // --- Close Handling ---

    @Test
    fun `closeReason completing causes handler to exit`() {
        val sent = CopyOnWriteArrayList<String>()
        val session = createTestSession(sent)
        val done = CountDownLatch(1)

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        scope.launch {
            GraphQLWebSocketProtocolHandler.handle(session, json, noOpSubscribe())
            done.countDown()
        }

        kotlinx.coroutines.runBlocking {
            delay(100)
            session.closeReason.complete(null)
        }

        assertTrue(done.await(5, TimeUnit.SECONDS), "Handler should exit when closeReason completes")
    }

    @Test
    fun `incoming channel closing causes handler to exit`() {
        val sent = CopyOnWriteArrayList<String>()
        val session = createTestSession(sent)
        val done = CountDownLatch(1)

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        scope.launch {
            GraphQLWebSocketProtocolHandler.handle(session, json, noOpSubscribe())
            done.countDown()
        }

        kotlinx.coroutines.runBlocking {
            delay(100)
            session._incoming.close()
        }

        assertTrue(done.await(5, TimeUnit.SECONDS), "Handler should exit when incoming channel closes")
    }

    // --- Non-text frames ---

    @Test
    fun `non-text frames are ignored`() {
        val sent = CopyOnWriteArrayList<String>()
        val session = createTestSession(sent)
        val done = CountDownLatch(1)

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        scope.launch {
            GraphQLWebSocketProtocolHandler.handle(session, json, noOpSubscribe())
            done.countDown()
        }

        kotlinx.coroutines.runBlocking {
            // Send a binary frame — should be ignored
            session._incoming.send(WebSocketFrame.Binary(byteArrayOf(1, 2, 3)))
            delay(100)
            // Then send a valid text frame to prove handler is still alive
            session._incoming.send(WebSocketFrame.Text("""{"type":"ping"}"""))
            delay(200)
            session.closeReason.complete(null)
        }

        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertTrue(sent.any { it.contains(""""type":"pong"""") }, "Handler should still respond after binary frame")
    }

    @Test
    fun `connection init authenticator accepts payload`() = kotlinx.coroutines.runBlocking {
        val sent = CopyOnWriteArrayList<String>()
        val session = createTestSession(sent, this)
        var authenticatedPayload: JsonElement? = null
        val job = launch {
            GraphQLWebSocketProtocolHandler.handle(
                session,
                json,
                noOpSubscribe(),
                onConnectionInit = { call, payload ->
                    assertTrue(call === session.call)
                    authenticatedPayload = payload
                    true
                },
            )
        }

        session._incoming.send(WebSocketFrame.Text("""{"type":"connection_init","payload":{"token":"accepted"}}"""))
        delay(100)
        session.closeReason.complete(null)
        withTimeout(5_000) { job.join() }

        val payload = authenticatedPayload as JsonObject
        assertEquals("accepted", (payload["token"] as JsonPrimitive).content)
        assertTrue(sent.any { it.contains("connection_ack") })
    }

    @Test
    fun `connection init authenticator rejection and failure send unauthorized error`() = kotlinx.coroutines.runBlocking {
        val callbacks: List<suspend (ServerCall, JsonElement?) -> Boolean> = listOf(
            { _, _ -> false },
            { _, _ -> throw IllegalStateException("auth unavailable") },
        )

        callbacks.forEach { callback ->
            val sent = CopyOnWriteArrayList<String>()
            val session = createTestSession(sent, this)
            val job = launch {
                GraphQLWebSocketProtocolHandler.handle(session, json, noOpSubscribe(), onConnectionInit = callback)
            }
            session._incoming.send(WebSocketFrame.Text("""{"type":"connection_init"}"""))
            withTimeout(5_000) { job.join() }
            assertTrue(sent.any { it.contains("connection_error") && it.contains("Unauthorized") })
            assertFalse(sent.any { it.contains("connection_ack") })
        }
    }

    @Test
    fun `duplicate connection init closes protocol`() = kotlinx.coroutines.runBlocking {
        val sent = CopyOnWriteArrayList<String>()
        val session = createTestSession(sent, this)
        val job = launch { GraphQLWebSocketProtocolHandler.handle(session, json, noOpSubscribe()) }

        session._incoming.send(WebSocketFrame.Text("""{"type":"connection_init"}"""))
        delay(50)
        session._incoming.send(WebSocketFrame.Text("""{"type":"connection_init"}"""))
        withTimeout(5_000) { job.join() }

        assertEquals(1, sent.count { it.contains("connection_ack") })
    }

    @Test
    fun `subscribe before init and subscription limit close protocol`() = kotlinx.coroutines.runBlocking {
        var sent = CopyOnWriteArrayList<String>()
        var session = createTestSession(sent, this)
        var job = launch { GraphQLWebSocketProtocolHandler.handle(session, json, noOpSubscribe()) }
        session._incoming.send(WebSocketFrame.Text("""{"type":"subscribe","id":"early","payload":{"query":"subscription { x }"}}"""))
        withTimeout(5_000) { job.join() }
        assertTrue(sent.any { it.contains("connection_error") })

        sent = CopyOnWriteArrayList()
        session = createTestSession(sent, this)
        val limitedSession = session
        job = launch {
            GraphQLWebSocketProtocolHandler.handle(limitedSession, json, noOpSubscribe(), maxSubscriptions = 0)
        }
        limitedSession._incoming.send(WebSocketFrame.Text("""{"type":"connection_init"}"""))
        delay(50)
        limitedSession._incoming.send(WebSocketFrame.Text("""{"type":"subscribe","id":"limited","payload":{"query":"subscription { x }"}}"""))
        withTimeout(5_000) { job.join() }
        assertTrue(sent.any { it.contains("connection_ack") })
    }

    @Test
    fun `malformed messages and missing subscription fields are ignored`() = kotlinx.coroutines.runBlocking {
        val sent = CopyOnWriteArrayList<String>()
        val session = createTestSession(sent, this)
        val job = launch { GraphQLWebSocketProtocolHandler.handle(session, json, noOpSubscribe()) }
        session._incoming.send(WebSocketFrame.Text("""{"type":"connection_init"}"""))
        delay(50)
        session._incoming.send(WebSocketFrame.Text("""{"type":"subscribe","payload":{"query":"subscription { x }"}}"""))
        session._incoming.send(WebSocketFrame.Text("""{"type":"subscribe","id":"missing-payload"}"""))
        session._incoming.send(WebSocketFrame.Text("""{"type":"complete"}"""))
        session._incoming.send(WebSocketFrame.Text("""{"type":"complete","id":"unknown"}"""))
        session._incoming.send(WebSocketFrame.Text("not-json"))
        session._incoming.send(WebSocketFrame.Text("""{"type":"ping"}"""))
        delay(150)
        session.closeReason.complete(null)
        withTimeout(5_000) { job.join() }

        assertTrue(sent.any { it.contains(""""type":"pong"""") })
    }

    @Test
    fun `a subscription that ends at once frees its id and its slot`() = kotlinx.coroutines.runBlocking {
        val sent = CopyOnWriteArrayList<String>()
        // Unconfined: each subscription runs to its end inside launch, before the handler registers
        // it, which is when a completed subscription used to leave a dead entry behind.
        val session = createTestSession(sent, CoroutineScope(Dispatchers.Unconfined + SupervisorJob()))
        val job = launch {
            GraphQLWebSocketProtocolHandler.handle(session, json, { _, _ -> flowOf() }, maxSubscriptions = 1)
        }
        session._incoming.send(WebSocketFrame.Text("""{"type":"connection_init"}"""))
        withTimeout(5_000) { while (sent.none { it.contains("connection_ack") }) delay(10) }

        // The same id, one slot: each is accepted only if the previous one's entry was removed.
        repeat(3) { attempt ->
            session._incoming.send(WebSocketFrame.Text("""{"type":"subscribe","id":"reused","payload":{"query":"subscription { x }"}}"""))
            withTimeout(5_000) {
                while (sent.count { it.contains(""""id":"reused","type":"complete"""") } <= attempt) delay(10)
            }
        }

        assertFalse(session.closeReason.isCompleted, "Completed subscriptions must not count as live: ${session.closeReason}")
        session.closeReason.complete(null)
        withTimeout(5_000) { job.join() }
    }

    @Test
    fun `duplicate subscription id closes protocol`() = kotlinx.coroutines.runBlocking {
        val sent = CopyOnWriteArrayList<String>()
        val session = createTestSession(sent, this)
        val started = CompletableDeferred<Unit>()
        val subscribe: suspend (ServerCall, GraphQLRequest) -> Flow<JsonElement> = { _, _ ->
            flow {
                started.complete(Unit)
                delay(Long.MAX_VALUE)
            }
        }
        val job = launch { GraphQLWebSocketProtocolHandler.handle(session, json, subscribe) }
        session._incoming.send(WebSocketFrame.Text("""{"type":"connection_init"}"""))
        delay(50)
        session._incoming.send(WebSocketFrame.Text("""{"type":"subscribe","id":"duplicate","payload":{"query":"subscription { x }"}}"""))
        withTimeout(5_000) { started.await() }
        session._incoming.send(WebSocketFrame.Text("""{"type":"subscribe","id":"duplicate","payload":{"query":"subscription { x }"}}"""))
        withTimeout(5_000) { job.join() }
    }

    @Test
    fun `subscription completion and failure send terminal messages`() = kotlinx.coroutines.runBlocking {
        val sent = CopyOnWriteArrayList<String>()
        val session = createTestSession(sent, this)
        val subscribe: suspend (ServerCall, GraphQLRequest) -> Flow<JsonElement> = { _, request ->
            if (request.operationName == "Failure") {
                flow { throw IllegalStateException("subscription failed") }
            } else {
                flowOf(JsonPrimitive("value"))
            }
        }
        val job = launch { GraphQLWebSocketProtocolHandler.handle(session, json, subscribe) }
        session._incoming.send(WebSocketFrame.Text("""{"type":"connection_init"}"""))
        delay(50)
        session._incoming.send(WebSocketFrame.Text("""{"type":"subscribe","id":"ok","payload":{"query":"subscription { x }","operationName":"Success"}}"""))
        session._incoming.send(WebSocketFrame.Text("""{"type":"subscribe","id":"failed","payload":{"query":"subscription { x }","operationName":"Failure"}}"""))
        withTimeout(5_000) {
            while (sent.none { it.contains(""""id":"ok","type":"complete"""") } ||
                sent.none { it.contains(""""id":"failed","type":"error"""") }) {
                delay(10)
            }
        }
        session.closeReason.complete(null)
        withTimeout(5_000) { job.join() }
    }

    @Test
    fun `connection init timeout closes uninitialized protocol`() = kotlinx.coroutines.runBlocking {
        val sent = CopyOnWriteArrayList<String>()
        val session = createTestSession(sent, this)
        withTimeout(5_000) {
            GraphQLWebSocketProtocolHandler.handle(
                session,
                json,
                noOpSubscribe(),
                timeoutCheckIntervalMs = 10,
                idleTimeoutMs = 10_000,
                connectionInitTimeoutMs = 20,
            )
        }
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `steady pings do not keep an uninitialised connection past its init deadline`() = kotlinx.coroutines.runBlocking {
        val sent = CopyOnWriteArrayList<String>()
        val session = createTestSession(sent, this)
        val handler = launch {
            GraphQLWebSocketProtocolHandler.handle(
                session,
                json,
                noOpSubscribe(),
                timeoutCheckIntervalMs = 50,
                idleTimeoutMs = 10_000,
                connectionInitTimeoutMs = 200,
            )
        }
        // Pings more often than the check interval, and never connection_init.
        val pinger = launch {
            while (true) {
                session._incoming.trySend(WebSocketFrame.Text("""{"type":"ping"}"""))
                delay(10)
            }
        }
        try {
            withTimeout(5_000) { handler.join() }
        } finally {
            pinger.cancel()
        }
        assertEquals(4408, session.closeReason.await()?.code)
    }
}
