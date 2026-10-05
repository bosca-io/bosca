package bosca.server.netty

import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.observability.ErrorCapture
import bosca.server.BoscaApplication
import bosca.server.HttpStatusCode
import bosca.server.ServerCall
import bosca.server.config.ApplicationConfig
import bosca.server.middleware.AuthMiddleware
import bosca.server.middleware.CallMiddleware
import bosca.server.routing.AuthConfig
import bosca.server.websocket.WebSocketFrame
import io.mockk.every
import io.mockk.mockk
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelPromise
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.codec.http.DefaultFullHttpRequest
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpHeaderValues
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpServerCodec
import io.netty.handler.codec.http.HttpServerKeepAliveHandler
import io.netty.handler.codec.http.HttpVersion
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame
import io.netty.handler.codec.http2.DefaultHttp2Headers
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame
import io.netty.handler.codec.http2.Http2Headers
import io.netty.handler.stream.ChunkedWriteHandler
import io.netty.handler.timeout.IdleStateEvent
import io.netty.util.ReferenceCountUtil
import io.netty.util.concurrent.DefaultEventExecutorGroup
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for WebSocket upgrade and frame handling paths in [NettyHttpHandler]
 * using Netty's [EmbeddedChannel] for a realistic pipeline.
 */
class NettyHttpHandlerWebSocketTest {

    private data class DeferredCloseWrite(
        val context: ChannelHandlerContext,
        val frame: CloseWebSocketFrame,
        val promise: ChannelPromise,
    )

    private class DeferredCloseWriteHandler : ChannelOutboundHandlerAdapter() {
        val captured = CountDownLatch(1)
        private val deferred = AtomicReference<DeferredCloseWrite?>()

        override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
            if (msg is CloseWebSocketFrame && deferred.compareAndSet(null, DeferredCloseWrite(ctx, msg, promise))) {
                captured.countDown()
                return
            }
            ctx.write(msg, promise)
        }

        fun complete() {
            val write = checkNotNull(deferred.getAndSet(null)) { "No close-frame write is pending" }
            write.context.write(write.frame, write.promise)
            write.context.flush()
        }

        fun releasePending() {
            deferred.getAndSet(null)?.let { write ->
                ReferenceCountUtil.safeRelease(write.frame)
                write.promise.tryFailure(IllegalStateException("Test released pending close-frame write"))
            }
        }
    }

    /** Reads every outbound message, returning the encoded WebSocket close frames (first byte 0x88). */
    private fun EmbeddedChannel.drainOutboundCloseFrames(): List<ByteArray> = drainOutboundFrames(0x88)

    /** Reads every outbound message, returning the encoded frames whose first byte is [firstByte]. */
    private fun EmbeddedChannel.drainOutboundFrames(firstByte: Int): List<ByteArray> =
        generateSequence { readOutbound<Any>() }.mapNotNull { message ->
            try {
                (message as? io.netty.buffer.ByteBuf)
                    ?.let { buf -> ByteArray(buf.readableBytes()).also { buf.getBytes(buf.readerIndex(), it) } }
                    ?.takeIf { it.isNotEmpty() && it[0] == firstByte.toByte() }
            } finally {
                ReferenceCountUtil.release(message)
            }
        }.toList()

    private fun createApplication(): BoscaApplication {
        val config = mockk<ApplicationConfig>(relaxed = true)
        every { config.propertyOrNull(any()) } returns null
        return BoscaApplication(config)
    }

    @Test
    fun `WebSocket upgrade handshake succeeds and handler is invoked`() {
        val app = createApplication()
        val handlerInvoked = CountDownLatch(1)

        app.routing {
            webSocket("/ws") {
                handlerInvoked.countDown()
                // Keep session open until close
                for (frame in incoming) {
                    if (frame is WebSocketFrame.Close) break
                }
            }
        }

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val handler = NettyHttpHandler(app, scope, 10_000_000L)

        val channel = EmbeddedChannel(HttpServerCodec(), handler)

        // Build a proper WebSocket upgrade request
        val request = DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/ws")
        request.headers().set(HttpHeaderNames.HOST, "localhost")
        request.headers().set(HttpHeaderNames.UPGRADE, HttpHeaderValues.WEBSOCKET)
        request.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.UPGRADE)
        request.headers().set(HttpHeaderNames.SEC_WEBSOCKET_KEY, "dGhlIHNhbXBsZSBub25jZQ==")
        request.headers().set(HttpHeaderNames.SEC_WEBSOCKET_VERSION, "13")

        channel.writeInbound(request)
        channel.flushOutbound()

        assertTrue(handlerInvoked.await(5, TimeUnit.SECONDS), "WebSocket handler should be invoked")

        channel.close()
    }

    @Test
    fun `a failed upgrade is still answered when an onException middleware is cancelled`() {
        val app = createApplication()
        app.install(object : CallMiddleware {
            override suspend fun beforeCall(call: ServerCall) {
                throw IllegalStateException("auth backend down")
            }

            override suspend fun onException(call: ServerCall, cause: Throwable) {
                // The middleware's own timeout expiring while it handles the failure.
                kotlinx.coroutines.withTimeout(0) { }
            }
        })
        app.routing { webSocket("/ws") { } }
        val handler = NettyHttpHandler(app, CoroutineScope(Dispatchers.Default + SupervisorJob()), 10_000_000L, RequestDispatcherMode.EVENT_LOOP)
        val channel = EmbeddedChannel(HttpServerCodec(), handler)
        try {
            channel.writeInbound(createUpgradeRequest("/ws"))
            channel.runPendingTasks()

            val response = generateSequence { channel.readOutbound<io.netty.buffer.ByteBuf>() }
                .joinToString("") { buf -> buf.toString(Charsets.UTF_8).also { buf.release() } }
            assertTrue(response.startsWith("HTTP/1.1 500"), "The client must get an answer, got: $response")
        } finally {
            runCatching { channel.finishAndReleaseAll() }
        }
    }

    @Test
    fun `an afterCall middleware that is cancelled does not keep the WebSocket open`() {
        val app = createApplication()
        app.install(object : CallMiddleware {
            override suspend fun afterCall(call: ServerCall) {
                kotlinx.coroutines.withTimeout(0) { }
            }
        })
        app.routing { webSocket("/ws") { } }
        val handler = NettyHttpHandler(app, CoroutineScope(Dispatchers.Default + SupervisorJob()), 10_000_000L, RequestDispatcherMode.EVENT_LOOP)
        val channel = EmbeddedChannel(HttpServerCodec(), handler)
        try {
            // Event-loop mode runs the whole exchange inline: the upgrade, the route, and the
            // teardown that closes the connection.
            channel.writeInbound(createUpgradeRequest("/ws"))

            assertEquals(1, channel.drainOutboundCloseFrames().size, "The finished route's session must still be closed")
            assertFalse(channel.isActive, "HTTP/1 closes the connection after the close frame")
        } finally {
            runCatching { channel.finishAndReleaseAll() }
        }
    }

    @Test
    fun `a WebSocket cancelled by a stopping server closes with 1001`() {
        val (channel, _, sessionRef) = openEventLoopWebSocket(routeFinishes = false)
        try {
            // The engine stopping cancels every connection's scope while the connections are still up.
            checkNotNull(channel.attr(ChannelAttributes.SCOPE).get()).coroutineContext[kotlinx.coroutines.Job]?.cancel()
            channel.runPendingTasks()

            val closeFrames = channel.drainOutboundCloseFrames()
            assertEquals(1001, ((closeFrames.single()[2].toInt() and 0xff) shl 8) or (closeFrames.single()[3].toInt() and 0xff))
            assertEquals(1001, sessionRef.get().closeReason.getCompleted()?.code)
        } finally {
            runCatching { channel.finishAndReleaseAll() }
        }
    }

    @Test
    fun `an upgrade that is not RFC 6455 version 13 is answered 426 without reaching the route`() {
        val app = createApplication()
        var routeRan = false
        app.routing { webSocket("/ws") { routeRan = true } }
        val handler = NettyHttpHandler(app, CoroutineScope(Dispatchers.Default + SupervisorJob()), 10_000_000L, RequestDispatcherMode.EVENT_LOOP)
        val channel = EmbeddedChannel(HttpServerCodec(), handler)
        try {
            // No version header: Netty would otherwise pick its pre-standard (Hixie) handshaker.
            channel.writeInbound(createUpgradeRequest("/ws").also { it.headers().remove(HttpHeaderNames.SEC_WEBSOCKET_VERSION) })

            val response = generateSequence { channel.readOutbound<io.netty.buffer.ByteBuf>() }
                .joinToString("") { buf -> buf.toString(Charsets.UTF_8).also { buf.release() } }
            assertTrue(response.startsWith("HTTP/1.1 426"), "Got: $response")
            assertTrue(response.contains("sec-websocket-version: 13", ignoreCase = true), response)
            assertFalse(routeRan)
        } finally {
            runCatching { channel.finishAndReleaseAll() }
        }
    }

    @Test
    fun `malformed HTTP1 upgrades are answered 400 before any handshake`() {
        val malformations = listOf<(DefaultFullHttpRequest) -> Unit>(
            { it.headers().remove(HttpHeaderNames.SEC_WEBSOCKET_KEY) },
            { it.headers().set(HttpHeaderNames.CONNECTION, "keep-alive") },
        )
        for (malform in malformations) {
            val app = createApplication()
            var routeRan = false
            app.routing { webSocket("/ws") { routeRan = true } }
            val handler = NettyHttpHandler(app, CoroutineScope(Dispatchers.Default + SupervisorJob()), 10_000_000L, RequestDispatcherMode.EVENT_LOOP)
            val channel = EmbeddedChannel(HttpServerCodec(), handler)
            try {
                channel.writeInbound(createUpgradeRequest("/ws").also(malform))
                channel.runPendingTasks()

                val response = generateSequence { channel.readOutbound<io.netty.buffer.ByteBuf>() }
                    .joinToString("") { buf -> buf.toString(Charsets.UTF_8).also { buf.release() } }
                assertTrue(response.startsWith("HTTP/1.1 400"), "Got: $response")
                assertFalse(routeRan)
                assertTrue(channel.isActive, "A rejected upgrade leaves a reusable HTTP connection")
            } finally {
                runCatching { channel.finishAndReleaseAll() }
            }
        }
    }

    /**
     * Helper to build a standard WebSocket upgrade request.
     */
    private fun createUpgradeRequest(
        path: String,
        protocol: String? = null,
    ): DefaultFullHttpRequest {
        val request = DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, path)
        request.headers().set(HttpHeaderNames.HOST, "localhost")
        request.headers().set(HttpHeaderNames.UPGRADE, HttpHeaderValues.WEBSOCKET)
        request.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.UPGRADE)
        request.headers().set(HttpHeaderNames.SEC_WEBSOCKET_KEY, "dGhlIHNhbXBsZSBub25jZQ==")
        request.headers().set(HttpHeaderNames.SEC_WEBSOCKET_VERSION, "13")
        protocol?.let { request.headers().set(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL, it) }
        return request
    }

    // --- Frame delivery tests ---
    // These call handler.readInbound() directly with WebSocket frames,
    // bypassing the WebSocket decoder (which requires masked client frames).
    // This exercises the real channelRead → handleWebSocketFrame → session._incoming path.

    @Test
    fun `frame handed directly to a waiting receiver is not left pending`() {
        val app = createApplication()
        val handlerStarted = CountDownLatch(1)
        val frameConsumed = CountDownLatch(1)
        val release = CompletableDeferred<Unit>()
        val sessionRef = AtomicReference<bosca.server.websocket.WebSocketSession>()

        app.routing {
            webSocket("/accounting") {
                sessionRef.set(this)
                handlerStarted.countDown()
                incoming.receive()
                frameConsumed()
                frameConsumed.countDown()
                release.await()
            }
        }

        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val handler = NettyHttpHandler(app, scope, 10_000_000L)
        val channel = EmbeddedChannel(HttpServerCodec(), handler)

        try {
            channel.writeInbound(createUpgradeRequest("/accounting"))
            channel.flushOutbound()
            assertTrue(handlerStarted.await(5, TimeUnit.SECONDS))

            val context = channel.pipeline().context(handler)
            handler.readInbound(context, TextWebSocketFrame("direct handoff"))

            assertTrue(frameConsumed.await(5, TimeUnit.SECONDS))
            assertEquals(
                0,
                sessionRef.get().pendingFrameCount.get(),
                "A consumed direct handoff must not leave a phantom pending frame",
            )
        } finally {
            release.complete(Unit)
            channel.finishAndReleaseAll()
            scope.cancel()
        }
    }

    @Test
    fun `text frame content is extracted before ByteBuf release`() {
        val app = createApplication()
        val receivedText = AtomicReference<String>()
        val handlerStarted = CountDownLatch(1)
        val frameReceived = CountDownLatch(1)

        app.routing {
            webSocket("/ws") {
                handlerStarted.countDown()
                for (frame in incoming) {
                    if (frame is WebSocketFrame.Text) {
                        receivedText.set(frame.readText())
                        frameReceived.countDown()
                    }
                    if (frame is WebSocketFrame.Close) break
                }
            }
        }

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val handler = NettyHttpHandler(app, scope, 10_000_000L)
        val channel = EmbeddedChannel(HttpServerCodec(), handler)

        channel.writeInbound(createUpgradeRequest("/ws"))
        channel.flushOutbound()
        assertTrue(handlerStarted.await(5, TimeUnit.SECONDS))

        // Send a text frame directly through channelRead, bypassing the WebSocket
        // frame decoder. This exercises the ByteBuf-to-String extraction path.
        val ctx = channel.pipeline().context(handler)
        val frame = TextWebSocketFrame("hello world")
        handler.readInbound(ctx, frame)

        // Frame ByteBuf should be released after channelRead returns
        assertEquals(0, frame.refCnt(), "Frame ByteBuf should be released")

        // Verify text was extracted before release and delivered correctly
        assertTrue(frameReceived.await(5, TimeUnit.SECONDS), "Frame should be delivered")
        assertEquals("hello world", receivedText.get())

        channel.close()
    }

    @Test
    fun `close frame data is extracted before ByteBuf release`() {
        val app = createApplication()
        val handlerStarted = CountDownLatch(1)
        val closeReceived = CountDownLatch(1)
        val closeCode = AtomicReference<Int>()
        val closeReason = AtomicReference<String>()

        app.routing {
            webSocket("/ws") {
                handlerStarted.countDown()
                for (frame in incoming) {
                    if (frame is WebSocketFrame.Close) {
                        closeCode.set(frame.code)
                        closeReason.set(frame.reason)
                        closeReceived.countDown()
                        break
                    }
                }
            }
        }

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val handler = NettyHttpHandler(app, scope, 10_000_000L)
        val channel = EmbeddedChannel(HttpServerCodec(), handler)

        channel.writeInbound(createUpgradeRequest("/ws"))
        channel.flushOutbound()
        assertTrue(handlerStarted.await(5, TimeUnit.SECONDS))

        val ctx = channel.pipeline().context(handler)
        val frame = CloseWebSocketFrame(1000, "normal")
        handler.readInbound(ctx, frame)

        assertEquals(0, frame.refCnt(), "Frame ByteBuf should be released")
        assertTrue(closeReceived.await(5, TimeUnit.SECONDS), "Close frame should be delivered")
        assertEquals(1000, closeCode.get())
        assertEquals("normal", closeReason.get())

        channel.close()
    }

    @Test
    fun `client close keeps HTTP1 channel open until echoed close frame is written`() {
        val app = createApplication()
        val handlerStarted = CountDownLatch(1)
        val closeReceived = CountDownLatch(1)
        app.routing {
            webSocket("/ws") {
                handlerStarted.countDown()
                for (frame in incoming) {
                    if (frame is WebSocketFrame.Close) {
                        closeReceived.countDown()
                        break
                    }
                }
            }
        }

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val handler = NettyHttpHandler(app, scope, 10_000_000L)
        val deferredClose = DeferredCloseWriteHandler()
        val channel = EmbeddedChannel(HttpServerCodec(), deferredClose, handler)

        try {
            channel.writeInbound(createUpgradeRequest("/ws"))
            channel.flushOutbound()
            assertTrue(handlerStarted.await(5, TimeUnit.SECONDS))

            val context = channel.pipeline().context(handler)
            handler.readInbound(context, CloseWebSocketFrame(1000, "normal"))
            assertTrue(closeReceived.await(5, TimeUnit.SECONDS))
            assertTrue(deferredClose.captured.await(5, TimeUnit.SECONDS))

            val channelJob = checkNotNull(channel.attr(ChannelAttributes.SCOPE).get())
                .coroutineContext[Job]
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while ((channelJob?.children?.count() ?: 0) > 1 && System.nanoTime() < deadline) {
                channel.runPendingTasks()
                Thread.sleep(10)
            }
            channel.runPendingTasks()

            assertTrue(
                channel.isActive,
                "HTTP/1 must not close while the echoed WebSocket close frame is still pending",
            )

            deferredClose.complete()
            channel.runPendingTasks()
            assertFalse(channel.isActive, "HTTP/1 must close after the echoed close frame is written")
        } finally {
            deferredClose.releasePending()
            runCatching { channel.finishAndReleaseAll() }
            scope.cancel()
        }
    }

    @Test
    fun `idle timeout keeps HTTP1 channel open until close frame is written`() {
        val app = createApplication()
        val handlerStarted = CountDownLatch(1)
        app.routing {
            webSocket("/ws") {
                handlerStarted.countDown()
                for (frame in incoming) {
                    if (frame is WebSocketFrame.Close) break
                }
            }
        }

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val handler = NettyHttpHandler(app, scope, 10_000_000L)
        val deferredClose = DeferredCloseWriteHandler()
        val channel = EmbeddedChannel(HttpServerCodec(), deferredClose, handler)

        try {
            channel.writeInbound(createUpgradeRequest("/ws"))
            channel.flushOutbound()
            assertTrue(handlerStarted.await(5, TimeUnit.SECONDS))

            handler.userEventTriggered(channel.pipeline().context(handler), IdleStateEvent.ALL_IDLE_STATE_EVENT)
            assertTrue(deferredClose.captured.await(5, TimeUnit.SECONDS))

            // The route tears down on a pool thread; let it finish before touching the channel again
            // (EmbeddedChannel is not thread-safe), as the echoed-close test above does.
            val channelJob = checkNotNull(channel.attr(ChannelAttributes.SCOPE).get())
                .coroutineContext[Job]
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while ((channelJob?.children?.count() ?: 0) > 1 && System.nanoTime() < deadline) {
                channel.runPendingTasks()
                Thread.sleep(10)
            }
            channel.runPendingTasks()
            assertTrue(
                channel.isActive,
                "HTTP/1 must not close while its idle-timeout close frame is still pending",
            )

            deferredClose.complete()
            channel.runPendingTasks()
            assertFalse(channel.isActive, "HTTP/1 must close after its idle-timeout close frame is written")
        } finally {
            deferredClose.releasePending()
            runCatching { channel.finishAndReleaseAll() }
            scope.cancel()
        }
    }

    @Test
    fun `a second idle timeout closes a WebSocket whose peer never read the close frame`() {
        val app = createApplication()
        val handlerStarted = CountDownLatch(1)
        app.routing {
            webSocket("/ws") {
                handlerStarted.countDown()
                closeReason.await()
            }
        }

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val handler = NettyHttpHandler(app, scope, 10_000_000L, RequestDispatcherMode.EVENT_LOOP)
        val deferredClose = DeferredCloseWriteHandler()
        val channel = EmbeddedChannel(HttpServerCodec(), deferredClose, handler)

        try {
            channel.writeInbound(createUpgradeRequest("/ws"))
            channel.flushOutbound()
            assertTrue(handlerStarted.await(5, TimeUnit.SECONDS))
            channel.drainOutboundCloseFrames()

            val context = channel.pipeline().context(handler)
            handler.userEventTriggered(context, IdleStateEvent.ALL_IDLE_STATE_EVENT)
            assertTrue(deferredClose.captured.await(5, TimeUnit.SECONDS))
            channel.runPendingTasks()
            assertTrue(channel.isActive, "The first idle timeout waits for its close frame to be written")

            // The peer is not reading, so the close frame is still pending when idleness fires again.
            handler.userEventTriggered(context, IdleStateEvent.ALL_IDLE_STATE_EVENT)
            channel.runPendingTasks()
            assertFalse(channel.isActive, "A second idle timeout must close instead of waiting forever")
            assertEquals(0, channel.drainOutboundCloseFrames().size, "No further close frames are queued")
        } finally {
            deferredClose.releasePending()
            runCatching { channel.finishAndReleaseAll() }
            scope.cancel()
        }
    }

    @Test
    fun `a peer close without a status is answered with an empty close and reported as 1005`() {
        val app = createApplication()
        val sessionRef = AtomicReference<bosca.server.websocket.WebSocketSession>()
        val started = CountDownLatch(1)
        app.routing {
            webSocket("/ws") {
                sessionRef.set(this)
                started.countDown()
                for (frame in incoming) {
                    if (frame is WebSocketFrame.Close) break
                }
            }
        }
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val handler = NettyHttpHandler(app, scope, 10_000_000L, RequestDispatcherMode.EVENT_LOOP)
        val channel = EmbeddedChannel(HttpServerCodec(), handler)
        try {
            channel.writeInbound(createUpgradeRequest("/ws"))
            channel.flushOutbound()
            assertTrue(started.await(5, TimeUnit.SECONDS))
            channel.drainOutboundCloseFrames()

            handler.readInbound(channel.pipeline().context(handler), CloseWebSocketFrame())
            channel.runPendingTasks()

            val closeFrames = channel.drainOutboundCloseFrames()
            assertEquals(1, closeFrames.size)
            assertContentEquals(byteArrayOf(0x88.toByte(), 0x00), closeFrames.single(), "The echo carries no status, never 65535")
            assertEquals(
                bosca.server.websocket.CloseReason(bosca.server.websocket.CloseReason.Codes.NO_STATUS_RECEIVED, ""),
                sessionRef.get().closeReason.getCompleted(),
            )
        } finally {
            runCatching { channel.finishAndReleaseAll() }
            scope.cancel()
        }
    }

    @Test
    fun `the peer's reply to our close is not echoed a second time`() {
        val app = createApplication()
        val closed = CountDownLatch(1)
        app.routing {
            webSocket("/ws") {
                close(4000, "bye")
                closed.countDown()
                for (frame in incoming) {
                    if (frame is WebSocketFrame.Close) break
                }
            }
        }
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val handler = NettyHttpHandler(app, scope, 10_000_000L, RequestDispatcherMode.EVENT_LOOP)
        val channel = EmbeddedChannel(HttpServerCodec(), handler)
        try {
            channel.writeInbound(createUpgradeRequest("/ws"))
            channel.flushOutbound()
            assertTrue(closed.await(5, TimeUnit.SECONDS))
            channel.runPendingTasks()
            assertEquals(1, channel.drainOutboundCloseFrames().size, "The server's own close frame")

            handler.readInbound(channel.pipeline().context(handler), CloseWebSocketFrame(4000, "bye"))
            channel.runPendingTasks()
            assertEquals(0, channel.drainOutboundCloseFrames().size, "A second close frame is a protocol error")
        } finally {
            runCatching { channel.finishAndReleaseAll() }
            scope.cancel()
        }
    }

    /**
     * Opens an HTTP/1 WebSocket in event-loop mode (the route runs inline on this thread). The route
     * waits for its close reason, or, with [routeFinishes] false, never ends on its own.
     */
    private fun openEventLoopWebSocket(
        routeFinishes: Boolean = true,
        route: (suspend bosca.server.websocket.WebSocketSession.() -> Unit)? = null,
    ): Triple<EmbeddedChannel, NettyHttpHandler, AtomicReference<bosca.server.websocket.WebSocketSession>> {
        val app = createApplication()
        val sessionRef = AtomicReference<bosca.server.websocket.WebSocketSession>()
        app.routing {
            webSocket("/ws") {
                sessionRef.set(this)
                when {
                    route != null -> route()
                    routeFinishes -> closeReason.await()
                    else -> awaitCancellation()
                }
            }
        }
        val handler = NettyHttpHandler(app, CoroutineScope(Dispatchers.Default + SupervisorJob()), 10_000_000L, RequestDispatcherMode.EVENT_LOOP)
        val channel = EmbeddedChannel(HttpServerCodec(), handler)
        channel.writeInbound(createUpgradeRequest("/ws"))
        channel.flushOutbound()
        channel.runPendingTasks()
        checkNotNull(sessionRef.get()) { "The WebSocket route did not start" }
        channel.drainOutboundCloseFrames()
        return Triple(channel, handler, sessionRef)
    }

    @Test
    fun `a route that times out closes with 1011, not a normal close`() {
        val timeOut = kotlinx.coroutines.CompletableDeferred<Unit>()
        val (channel, _, sessionRef) = openEventLoopWebSocket {
            timeOut.await()
            // Expires at once: the route's own timeout, as a withTimeout around its work would.
            kotlinx.coroutines.withTimeout(0) { }
        }
        try {
            // EmbeddedEventLoop counts every thread as its loop, so the route resumes here, inline.
            timeOut.complete(Unit)
            channel.runPendingTasks()

            val closeFrames = channel.drainOutboundCloseFrames()
            assertEquals(1011, ((closeFrames.single()[2].toInt() and 0xff) shl 8) or (closeFrames.single()[3].toInt() and 0xff))
            assertEquals(1011, sessionRef.get().closeReason.getCompleted()?.code)
        } finally {
            runCatching { channel.finishAndReleaseAll() }
        }
    }

    @Test
    fun `pings from a peer that is not reading are answered once, with the latest payload, when it reads again`() {
        val (channel, handler, _) = openEventLoopWebSocket(routeFinishes = false)
        try {
            val context = channel.pipeline().context(handler)
            val outbound = checkNotNull(channel.unsafe().outboundBuffer())
            // The peer stops reading: the channel turns unwritable.
            outbound.setUserDefinedWritability(1, false)
            channel.runPendingTasks()

            repeat(3) { i -> handler.readInbound(context, PingWebSocketFrame(Unpooled.wrappedBuffer(byteArrayOf(i.toByte())))) }
            assertEquals(0, channel.drainOutboundFrames(0x8A).size, "No pongs pile up for a peer that is not reading")

            outbound.setUserDefinedWritability(1, true)
            channel.runPendingTasks()

            val pongs = channel.drainOutboundFrames(0x8A)
            assertEquals(1, pongs.size)
            assertEquals(2, pongs.single()[2].toInt(), "The latest ping is the one answered")
        } finally {
            runCatching { channel.finishAndReleaseAll() }
        }
    }

    @Test
    fun `no pong follows our close frame`() {
        val (channel, handler, sessionRef) = openEventLoopWebSocket(routeFinishes = false)
        channel.attr(ChannelAttributes.HTTP2_WEBSOCKET_TRANSPORT).set(mockk(relaxed = true))
        try {
            sessionRef.get().writeClose(1000, "")
            channel.runPendingTasks()
            channel.drainOutboundCloseFrames()

            handler.readInbound(channel.pipeline().context(handler), PingWebSocketFrame(Unpooled.wrappedBuffer(byteArrayOf(1))))
            channel.runPendingTasks()

            assertEquals(0, channel.drainOutboundFrames(0x8A).size)
        } finally {
            runCatching { channel.finishAndReleaseAll() }
        }
    }

    @Test
    fun `a message over the size limit is closed with 1009 on HTTP1`() {
        val (channel, handler, sessionRef) = openEventLoopWebSocket()
        try {
            handler.exceptionCaught(
                channel.pipeline().context(handler),
                io.netty.handler.codec.TooLongFrameException("content length exceeded 1048576 bytes."),
            )
            channel.runPendingTasks()

            val closeFrames = channel.drainOutboundCloseFrames()
            assertEquals(1, closeFrames.size)
            assertEquals(1009, ((closeFrames.single()[2].toInt() and 0xff) shl 8) or (closeFrames.single()[3].toInt() and 0xff))
            assertEquals(1009, sessionRef.get().closeReason.getCompleted()?.code)
            assertFalse(channel.isActive, "HTTP/1 closes the connection once the close frame is written")
        } finally {
            runCatching { channel.finishAndReleaseAll() }
        }
    }

    @Test
    fun `a message over the size limit on an RFC 8441 stream leaves the stream to its close observer`() {
        // The session was upgraded over HTTP/1, whose route teardown would close the channel itself;
        // a route that does not finish isolates what the violation handling does on an RFC 8441 stream.
        val (channel, handler, sessionRef) = openEventLoopWebSocket(routeFinishes = false)
        channel.attr(ChannelAttributes.HTTP2_WEBSOCKET_TRANSPORT).set(mockk(relaxed = true))
        try {
            handler.exceptionCaught(
                channel.pipeline().context(handler),
                io.netty.handler.codec.TooLongFrameException("content length exceeded 1048576 bytes."),
            )
            channel.runPendingTasks()

            assertEquals(1, channel.drainOutboundCloseFrames().size)
            assertEquals(1009, sessionRef.get().closeReason.getCompleted()?.code)
            assertTrue(channel.isActive, "The stream ends through its close observer, not a channel close")
        } finally {
            runCatching { channel.finishAndReleaseAll() }
        }
    }

    @Test
    fun `a malformed HTTP1 frame is closed once with its violation status`() {
        val (channel, handler, sessionRef) = openEventLoopWebSocket()
        try {
            val violation = io.netty.handler.codec.http.websocketx.CorruptedWebSocketFrameException(
                io.netty.handler.codec.http.websocketx.WebSocketCloseStatus.INVALID_PAYLOAD_DATA,
                "invalid UTF-8",
            )
            // The decoder and UTF-8 validator only raise violations; the handler sends the close.
            handler.exceptionCaught(channel.pipeline().context(handler), violation)
            channel.runPendingTasks()

            val closeFrames = channel.drainOutboundCloseFrames()
            assertEquals(1, closeFrames.size)
            assertEquals(1007, ((closeFrames.single()[2].toInt() and 0xff) shl 8) or (closeFrames.single()[3].toInt() and 0xff))
            assertEquals(
                bosca.server.websocket.CloseReason(1007, "invalid UTF-8"),
                sessionRef.get().closeReason.getCompleted(),
            )
            assertFalse(channel.isActive, "HTTP/1 closes the connection once the close frame is written")
        } finally {
            runCatching { channel.finishAndReleaseAll() }
        }
    }

    @Test
    fun `a violation after our own close sends no second close frame`() {
        val (channel, handler, _) = openEventLoopWebSocket(routeFinishes = false)
        // As on an RFC 8441 stream, nothing closes the channel after the first close frame, so a
        // second one would reach the outbound buffer if it were written.
        channel.attr(ChannelAttributes.HTTP2_WEBSOCKET_TRANSPORT).set(mockk(relaxed = true))
        try {
            val context = channel.pipeline().context(handler)
            // An idle close goes out first; the peer then sends an oversized message.
            handler.userEventTriggered(context, IdleStateEvent.ALL_IDLE_STATE_EVENT)
            channel.runPendingTasks()
            assertEquals(1, channel.drainOutboundCloseFrames().size)
            assertTrue(channel.isActive)

            handler.exceptionCaught(context, io.netty.handler.codec.TooLongFrameException("too long"))
            channel.runPendingTasks()

            assertEquals(0, channel.drainOutboundCloseFrames().size, "At most one close frame per session")
        } finally {
            runCatching { channel.finishAndReleaseAll() }
        }
    }

    @Test
    fun `cancelled HTTP1 WebSocket middleware closes the pending connection`() {
        val app = createApplication()
        val middlewareCancelled = CountDownLatch(1)
        val handlerCalled = AtomicReference(false)
        app.install(object : CallMiddleware {
            override suspend fun beforeCall(call: ServerCall) {
                middlewareCancelled.countDown()
                throw kotlinx.coroutines.CancellationException("request timeout")
            }
        })
        app.routing {
            webSocket("/graphqlws") {
                handlerCalled.set(true)
            }
        }

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val handler = NettyHttpHandler(app, scope, 10_000_000L)
        val codec = HttpServerCodec()
        val channel = EmbeddedChannel(codec, Http1ExchangeSequencer(codec), handler)

        try {
            channel.writeInbound(createUpgradeRequest("/graphqlws"))
            assertTrue(middlewareCancelled.await(5, TimeUnit.SECONDS))

            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (channel.isActive && System.nanoTime() < deadline) {
                channel.runPendingTasks()
                Thread.sleep(10)
            }
            channel.runPendingTasks()

            assertFalse(handlerCalled.get(), "A cancelled handshake must not invoke the WebSocket route")
            assertFalse(
                channel.isActive,
                "Cancellation before an HTTP/1 upgrade must close the connection and release the active exchange",
            )
        } finally {
            runCatching { channel.finishAndReleaseAll() }
            scope.cancel()
        }
    }

    @Test
    fun `frames sent after handshake are buffered and delivered`() {
        val app = createApplication()
        val receivedText = AtomicReference<String>()
        val handlerStarted = CountDownLatch(1)
        val frameReceived = CountDownLatch(1)

        app.routing {
            webSocket("/ws") {
                handlerStarted.countDown()
                for (frame in incoming) {
                    if (frame is WebSocketFrame.Text) {
                        receivedText.set(frame.readText())
                        frameReceived.countDown()
                        break
                    }
                }
            }
        }

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val handler = NettyHttpHandler(app, scope, 10_000_000L)
        val channel = EmbeddedChannel(HttpServerCodec(), handler)

        channel.writeInbound(createUpgradeRequest("/ws"))
        channel.flushOutbound()

        // Wait for the handler to start (auth middleware + handshake must complete first)
        assertTrue(handlerStarted.await(5, TimeUnit.SECONDS), "Handler should start")

        val ctx = channel.pipeline().context(handler)
        handler.readInbound(ctx, TextWebSocketFrame("early message"))

        // Frame should be delivered once the handler starts reading
        assertTrue(frameReceived.await(5, TimeUnit.SECONDS), "Frame should be delivered")
        assertEquals("early message", receivedText.get())

        channel.close()
    }

    @Test
    fun `WebSocket handler exception does not crash channel`() {
        val app = createApplication()
        val handlerInvoked = CountDownLatch(1)

        app.routing {
            webSocket("/ws") {
                handlerInvoked.countDown()
                throw RuntimeException("intentional error")
            }
        }

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val handler = NettyHttpHandler(app, scope, 10_000_000L)

        val channel = EmbeddedChannel(HttpServerCodec(), handler)

        val request = DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/ws")
        request.headers().set(HttpHeaderNames.HOST, "localhost")
        request.headers().set(HttpHeaderNames.UPGRADE, HttpHeaderValues.WEBSOCKET)
        request.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.UPGRADE)
        request.headers().set(HttpHeaderNames.SEC_WEBSOCKET_KEY, "dGhlIHNhbXBsZSBub25jZQ==")
        request.headers().set(HttpHeaderNames.SEC_WEBSOCKET_VERSION, "13")
        channel.writeInbound(request)

        // The handler coroutine throws immediately and its finally block closes
        // the channel, so flushOutbound() may race with that closure.
        try {
            channel.flushOutbound()
        } catch (_: java.nio.channels.ClosedChannelException) {
            // Expected when the handler's exception closes the channel before flush
        }

        assertTrue(handlerInvoked.await(5, TimeUnit.SECONDS))

        // Allow time for exception handling to complete without propagating
        Thread.sleep(300)

        channel.close()
    }

    @Test
    fun `authenticated WebSocket can be rejected by middleware before handler`() {
        val app = createApplication()
        val authCalled = CountDownLatch(1)
        val afterCalled = CountDownLatch(1)
        var handlerCalled = false
        app.installAuth(object : AuthMiddleware {
            override suspend fun authenticate(call: ServerCall, authConfig: AuthConfig?) {
                authCalled.countDown()
                call.respond(HttpStatusCode.Unauthorized, "denied")
            }
        })
        app.install(object : CallMiddleware {
            override suspend fun afterCall(call: ServerCall) {
                afterCalled.countDown()
            }
        })
        app.routing {
            authenticate("bearer") {
                webSocket("/secure") {
                    handlerCalled = true
                }
            }
        }

        val handler = NettyHttpHandler(app, CoroutineScope(Dispatchers.Default + SupervisorJob()), 10_000_000L)
        val channel = EmbeddedChannel(HttpServerCodec(), handler)
        channel.writeInbound(createUpgradeRequest("/secure"))
        channel.flushOutbound()

        assertTrue(authCalled.await(5, TimeUnit.SECONDS))
        assertTrue(afterCalled.await(5, TimeUnit.SECONDS))
        assertFalse(handlerCalled)
        channel.close()
    }

    @Test
    fun `rejected WebSocket waits for compressed HTTP response and keeps HTTP connection reusable`() {
        val app = createApplication()
        val authCalled = CountDownLatch(1)
        val followUpCalled = CountDownLatch(1)
        app.installAuth(object : AuthMiddleware {
            override suspend fun authenticate(call: ServerCall, authConfig: AuthConfig?) {
                call.respond(HttpStatusCode.Unauthorized, "denied")
                authCalled.countDown()
            }
        })
        app.routing {
            authenticate("bearer") {
                webSocket("/secure") {
                    error("rejected WebSocket route must not run")
                }
            }
        }
        app.router.get("/after-rejection") {
            call.respond(HttpStatusCode.OK, "still usable")
            followUpCalled.countDown()
        }

        val codecGroup = DefaultEventExecutorGroup(1)
        val codecExecutor = codecGroup.next()
        val blockerStarted = CountDownLatch(1)
        val releaseBlocker = CountDownLatch(1)
        codecExecutor.execute {
            blockerStarted.countDown()
            releaseBlocker.await(10, TimeUnit.SECONDS)
        }
        assertTrue(blockerStarted.await(5, TimeUnit.SECONDS))

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val handler = NettyHttpHandler(app, scope, 10_000_000L)
        val channel = EmbeddedChannel(
            HttpServerKeepAliveHandler(),
            CompressionOffloadHandler(codecExecutor),
            ChunkedWriteHandler(),
            handler,
        )
        val channelScope = checkNotNull(channel.attr(ChannelAttributes.SCOPE).get())
        val channelJob = checkNotNull(channelScope.coroutineContext[Job])

        try {
            val request = createUpgradeRequest("/secure")
            request.headers().set(HttpHeaderNames.ACCEPT_ENCODING, HttpHeaderValues.GZIP)
            channel.writeInbound(request)
            assertTrue(authCalled.await(5, TimeUnit.SECONDS))

            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            // Rejection happens before a WebSocket session is allocated. Wait for the request
            // coroutine to finish and verify it does not leave a session job on the connection.
            while (channelJob.children.any() && System.nanoTime() < deadline) {
                channel.runPendingTasks()
                Thread.sleep(10)
            }
            assertFalse(channelJob.children.any(), "The WebSocket rejection leaked a child job")
            channel.runPendingTasks()

            assertTrue(
                channel.isActive,
                "The HTTP channel must remain open until the asynchronously compressed rejection is written",
            )

            releaseBlocker.countDown()
            codecExecutor.submit {}.syncUninterruptibly()
            channel.runPendingTasks()
            codecExecutor.submit {}.syncUninterruptibly()
            channel.runPendingTasks()

            val response = channel.readOutbound<DefaultFullHttpResponse>()
            assertEquals(HttpResponseStatus.UNAUTHORIZED, response.status())
            assertEquals(HttpHeaderValues.GZIP.toString(), response.headers()[HttpHeaderNames.CONTENT_ENCODING])
            ReferenceCountUtil.safeRelease(response)
            assertTrue(channel.isActive, "An ordinary HTTP rejection must preserve the keep-alive connection")

            channel.writeInbound(
                DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/after-rejection"),
            )
            assertTrue(followUpCalled.await(5, TimeUnit.SECONDS), "The connection must accept a follow-up request")
            channel.runPendingTasks()
            val followUp = channel.readOutbound<DefaultFullHttpResponse>()
            assertEquals(HttpResponseStatus.OK, followUp.status())
            assertEquals("still usable", followUp.content().toString(Charsets.UTF_8))
            ReferenceCountUtil.safeRelease(followUp)
        } finally {
            releaseBlocker.countDown()
            codecExecutor.submit {}.syncUninterruptibly()
            runCatching { channel.runPendingTasks() }
            runCatching { channel.finishAndReleaseAll() }
            scope.cancel()
            codecGroup.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).syncUninterruptibly()
        }
    }

    @Test
    fun `WebSocket exception and teardown middleware failures are captured`() {
        val app = createApplication()
        val onExceptionCalled = CountDownLatch(1)
        val afterCalled = CountDownLatch(1)
        app.install(object : CallMiddleware {
            override suspend fun onException(call: ServerCall, cause: Throwable) {
                onExceptionCalled.countDown()
                throw IllegalStateException("onException failed")
            }

            override suspend fun afterCall(call: ServerCall) {
                afterCalled.countDown()
                throw IllegalStateException("afterCall failed")
            }
        })
        app.routing {
            webSocket("/broken") {
                throw IllegalStateException("handler failed")
            }
        }

        val handler = NettyHttpHandler(app, CoroutineScope(Dispatchers.Default + SupervisorJob()), 10_000_000L)
        val channel = EmbeddedChannel(HttpServerCodec(), handler)
        channel.writeInbound(createUpgradeRequest("/broken"))
        try {
            channel.flushOutbound()
        } catch (_: java.nio.channels.ClosedChannelException) {
            // The handler closes the upgraded connection during teardown.
        }

        assertTrue(onExceptionCalled.await(5, TimeUnit.SECONDS))
        assertTrue(afterCalled.await(5, TimeUnit.SECONDS))
        channel.close()
    }

    @Test
    @OptIn(InternalDI::class)
    fun `HTTP1 WebSocket route exception is reported exactly once when middleware captures it`() {
        ProviderRegistry.clear()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        var channel: EmbeddedChannel? = null
        try {
            val capturedCount = AtomicInteger()
            val afterCalled = CountDownLatch(1)
            val app = createApplication()
            provides<ErrorCapture>(singleton = true) {
                ErrorCapture { throwable, _, _ ->
                    if (throwable.message == "handler failed") capturedCount.incrementAndGet()
                }
            }
            app.install(object : CallMiddleware {
                override suspend fun onException(call: ServerCall, cause: Throwable) {
                    call.application.errorCapture.capture(cause, call, emptyMap())
                }

                override suspend fun afterCall(call: ServerCall) {
                    afterCalled.countDown()
                }
            })
            app.routing {
                webSocket("/reported-once") {
                    throw IllegalStateException("handler failed")
                }
            }

            val handler = NettyHttpHandler(app, scope, 10_000_000L)
            channel = EmbeddedChannel(HttpServerCodec(), handler)
            channel.writeInbound(createUpgradeRequest("/reported-once"))
            try {
                channel.flushOutbound()
            } catch (_: java.nio.channels.ClosedChannelException) {
                // The handler closes the upgraded connection during teardown.
            }

            assertTrue(afterCalled.await(5, TimeUnit.SECONDS), "WebSocket teardown should complete")
            assertEquals(1, capturedCount.get(), "The route exception must have one reporting path")
        } finally {
            channel?.let { runCatching { it.finishAndReleaseAll() } }
            scope.cancel()
            ProviderRegistry.clear()
        }
    }

    @Test
    @OptIn(InternalDI::class)
    fun `body-bearing WebSocket upgrade returns 400 without reporting an application exception`() {
        ProviderRegistry.clear()
        val capturedCount = AtomicInteger()
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        var channel: EmbeddedChannel? = null
        try {
            provides<ErrorCapture>(singleton = true) {
                ErrorCapture { _, _, _ -> capturedCount.incrementAndGet() }
            }
            val app = createApplication()
            app.routing {
                webSocket("/ws-with-body") {}
            }
            val handler = NettyHttpHandler(app, scope, 10_000_000L)
            channel = EmbeddedChannel(HttpServerCodec(), handler)
            val request = Unpooled.copiedBuffer(
                "GET /ws-with-body HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" +
                    "Sec-WebSocket-Version: 13\r\n" +
                    "Content-Length: 1\r\n" +
                    "\r\n" +
                    "x",
                Charsets.US_ASCII,
            )

            channel.writeInbound(request)
            channel.runPendingTasks()

            val encodedResponse = StringBuilder()
            while (true) {
                val buffer = channel.readOutbound<io.netty.buffer.ByteBuf>() ?: break
                try {
                    encodedResponse.append(buffer.toString(Charsets.US_ASCII))
                } finally {
                    buffer.release()
                }
            }
            assertTrue(encodedResponse.startsWith("HTTP/1.1 400 Bad Request"))
            assertEquals(0, capturedCount.get(), "Expected client errors must not reach ErrorCapture")
        } finally {
            channel?.let { runCatching { it.finishAndReleaseAll() } }
            scope.cancel()
            ProviderRegistry.clear()
        }
    }

    @Test
    fun `WebSocket upgrade with protocol`() {
        val app = createApplication()
        val handlerInvoked = CountDownLatch(1)

        app.routing {
            webSocket("/ws", protocol = "graphql-ws") {
                handlerInvoked.countDown()
                for (frame in incoming) {
                    if (frame is WebSocketFrame.Close) break
                }
            }
        }

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val handler = NettyHttpHandler(app, scope, 10_000_000L)

        val channel = EmbeddedChannel(HttpServerCodec(), handler)

        val request = DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/ws")
        request.headers().set(HttpHeaderNames.HOST, "localhost")
        request.headers().set(HttpHeaderNames.UPGRADE, HttpHeaderValues.WEBSOCKET)
        request.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.UPGRADE)
        request.headers().set(HttpHeaderNames.SEC_WEBSOCKET_KEY, "dGhlIHNhbXBsZSBub25jZQ==")
        request.headers().set(HttpHeaderNames.SEC_WEBSOCKET_VERSION, "13")
        request.headers().set(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL, "graphql-ws")
        channel.writeInbound(request)
        channel.flushOutbound()

        assertTrue(handlerInvoked.await(5, TimeUnit.SECONDS))

        channel.close()
    }

    @Test
    fun `WebSocket upgrade succeeds through full pipeline with compressor and keepAlive`() {
        val app = createApplication()
        val handlerInvoked = CountDownLatch(1)

        app.routing {
            webSocket("/ws", protocol = "graphql-transport-ws") {
                handlerInvoked.countDown()
                for (frame in incoming) {
                    if (frame is WebSocketFrame.Close) break
                }
            }
        }

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val handler = NettyHttpHandler(app, scope, 10_000_000L)

        // Full pipeline matching the real server setup
        val channel = EmbeddedChannel(
            HttpServerCodec(),
            HttpServerKeepAliveHandler(),
            SelectiveContentCompressor(),
            ChunkedWriteHandler(),
            handler
        )

        val request = createUpgradeRequest("/ws", "graphql-transport-ws")
        channel.writeInbound(request)
        channel.flushOutbound()

        assertTrue(handlerInvoked.await(5, TimeUnit.SECONDS), "WebSocket handler should be invoked through full pipeline")

        // Verify the handshake response was sent
        val response = channel.readOutbound<io.netty.buffer.ByteBuf>()
        if (response != null) {
            val text = response.toString(java.nio.charset.StandardCharsets.UTF_8)
            assertTrue(text.contains("101"), "Should contain 101 Switching Protocols")
            response.release()
        }

        channel.close()
    }

    @Test
    fun `binary ping and pong frames follow protocol paths`() {
        val app = createApplication()
        val started = CountDownLatch(1)
        val delivered = CountDownLatch(2)
        val binary = AtomicReference<ByteArray>()
        val pong = AtomicReference<ByteArray>()
        app.routing {
            webSocket("/frames") {
                started.countDown()
                for (frame in incoming) {
                    when (frame) {
                        is WebSocketFrame.Binary -> {
                            binary.set(frame.data)
                            delivered.countDown()
                        }
                        is WebSocketFrame.Pong -> {
                            pong.set(frame.data)
                            delivered.countDown()
                        }
                        else -> Unit
                    }
                    frameConsumed()
                    if (delivered.count == 0L) break
                }
            }
        }
        val handler = NettyHttpHandler(app, CoroutineScope(Dispatchers.Default + SupervisorJob()), 10_000_000L)
        val channel = EmbeddedChannel(HttpServerCodec(), handler)
        channel.writeInbound(createUpgradeRequest("/frames"))
        channel.flushOutbound()
        assertTrue(started.await(5, TimeUnit.SECONDS))
        val context = channel.pipeline().context(handler)

        val binaryFrame = BinaryWebSocketFrame(Unpooled.wrappedBuffer(byteArrayOf(1, 2)))
        val pingFrame = PingWebSocketFrame(Unpooled.wrappedBuffer(byteArrayOf(3)))
        val pongFrame = PongWebSocketFrame(Unpooled.wrappedBuffer(byteArrayOf(4, 5)))
        handler.readInbound(context, binaryFrame)
        handler.readInbound(context, pingFrame)
        handler.readInbound(context, pongFrame)

        assertTrue(delivered.await(5, TimeUnit.SECONDS))
        assertContentEquals(byteArrayOf(1, 2), binary.get())
        assertContentEquals(byteArrayOf(4, 5), pong.get())
        assertEquals(0, binaryFrame.refCnt())
        assertEquals(0, pingFrame.refCnt())
        assertEquals(0, pongFrame.refCnt())
        channel.close()
    }

    @Test
    fun `frames for a route that stopped reading are dropped without closing the session`() {
        val app = createApplication()
        val started = CountDownLatch(1)
        val release = CompletableDeferred<Unit>()
        val sessionRef = AtomicReference<bosca.server.websocket.WebSocketSession>()
        app.routing {
            webSocket("/stopped") {
                sessionRef.set(this)
                started.countDown()
                release.await()
            }
        }
        val handler = NettyHttpHandler(app, CoroutineScope(Dispatchers.Default + SupervisorJob()), 10_000_000L)
        val channel = EmbeddedChannel(HttpServerCodec(), handler)
        channel.writeInbound(createUpgradeRequest("/stopped"))
        channel.flushOutbound()
        assertTrue(started.await(5, TimeUnit.SECONDS))
        val session = sessionRef.get()
        session._incoming.close()
        channel.drainOutboundCloseFrames()
        val context = channel.pipeline().context(handler)

        handler.readInbound(context, TextWebSocketFrame("dropped"))
        handler.readInbound(context, BinaryWebSocketFrame(Unpooled.wrappedBuffer(byteArrayOf(1))))
        handler.readInbound(context, PongWebSocketFrame(Unpooled.wrappedBuffer(byteArrayOf(2))))

        assertFalse(session.closeReason.isCompleted)
        assertEquals(0, session.pendingFrameCount.get(), "Dropped frames are not counted as pending")
        channel.runPendingTasks()
        assertEquals(0, channel.drainOutboundCloseFrames().size)
        release.complete(Unit)
        channel.close()
    }

    @Test
    fun `a burst of small messages past the watermark is queued and pauses reads instead of closing`() {
        val app = createApplication()
        val started = CountDownLatch(1)
        val release = CompletableDeferred<Unit>()
        val sessionRef = AtomicReference<bosca.server.websocket.WebSocketSession>()
        app.routing {
            webSocket("/burst") {
                sessionRef.set(this)
                started.countDown()
                release.await()
            }
        }
        val handler = NettyHttpHandler(app, CoroutineScope(Dispatchers.Default + SupervisorJob()), 10_000_000L)
        val channel = EmbeddedChannel(HttpServerCodec(), handler)
        channel.writeInbound(createUpgradeRequest("/burst"))
        channel.flushOutbound()
        assertTrue(started.await(5, TimeUnit.SECONDS))
        val session = sessionRef.get()
        channel.drainOutboundCloseFrames()
        val context = channel.pipeline().context(handler)

        // More than one read's worth of tiny messages arriving after reads were paused; pongs count
        // too, or a pong flood could grow the queue without ever pausing reads.
        repeat(500) {
            handler.readInbound(context, TextWebSocketFrame("m$it"))
            handler.readInbound(context, PongWebSocketFrame(Unpooled.wrappedBuffer(byteArrayOf(1))))
        }
        channel.runPendingTasks()

        assertFalse(session.closeReason.isCompleted, "A burst the pause absorbs must not close the session")
        assertEquals(1_000, session.pendingFrameCount.get())
        assertFalse(channel.config().isAutoRead)
        assertEquals(0, channel.drainOutboundCloseFrames().size)
        release.complete(Unit)
        channel.close()
    }

    @Test
    fun `WebSocket backpressure pauses and resumes auto read at watermarks`() {
        val app = createApplication()
        val started = CountDownLatch(1)
        val release = CompletableDeferred<Unit>()
        val sessionRef = AtomicReference<bosca.server.websocket.WebSocketSession>()
        app.routing {
            webSocket("/backpressure") {
                sessionRef.set(this)
                started.countDown()
                release.await()
            }
        }
        val handler = NettyHttpHandler(app, CoroutineScope(Dispatchers.Default + SupervisorJob()), 10_000_000L)
        val channel = EmbeddedChannel(HttpServerCodec(), handler)
        channel.writeInbound(createUpgradeRequest("/backpressure"))
        channel.flushOutbound()
        assertTrue(started.await(5, TimeUnit.SECONDS))
        val session = sessionRef.get()
        val context = channel.pipeline().context(handler)

        session.pendingFrameCount.set(384)
        handler.readInbound(context, TextWebSocketFrame("pause"))
        assertFalse(channel.config().isAutoRead)

        session.pendingFrameCount.set(128)
        session.frameConsumed()
        channel.runPendingTasks()
        assertTrue(channel.config().isAutoRead)

        session.pendingFrameCount.set(385)
        handler.readInbound(context, TextWebSocketFrame("already-over-high-watermark"))
        assertFalse(channel.config().isAutoRead)

        session.pendingFrameCount.set(128)
        session.frameConsumed()
        channel.runPendingTasks()
        assertTrue(channel.config().isAutoRead)

        session.pendingFrameCount.set(383)
        handler.readInbound(context, TextWebSocketFrame("at-high-watermark"))
        assertTrue(channel.config().isAutoRead)

        session.pendingFrameCount.set(200)
        handler.readInbound(context, TextWebSocketFrame("middle"))
        assertTrue(channel.config().isAutoRead)
        release.complete(Unit)
        channel.close()
    }

    @Test
    fun `upgrade supports forwarded TLS path parameters and rejects unsupported versions`() {
        val app = createApplication()
        val invoked = CountDownLatch(1)
        val release = CompletableDeferred<Unit>()
        app.routing {
            webSocket("/secure/{id}") {
                assertEquals("42", call.pathParameters["id"])
                invoked.countDown()
                release.await()
            }
        }
        val handler = NettyHttpHandler(app, CoroutineScope(Dispatchers.Default + SupervisorJob()), 10_000_000L)
        var channel = EmbeddedChannel(HttpServerCodec(), handler)
        val secure = createUpgradeRequest("/secure/42")
        secure.headers().set("X-Forwarded-Proto", "https")
        channel.writeInbound(secure)
        channel.flushOutbound()
        assertTrue(invoked.await(5, TimeUnit.SECONDS))
        release.complete(Unit)
        channel.close()

        val unsupportedApp = createApplication()
        unsupportedApp.routing { webSocket("/unsupported") {} }
        val unsupportedHandler = NettyHttpHandler(
            unsupportedApp,
            CoroutineScope(Dispatchers.Default + SupervisorJob()),
            10_000_000L,
        )
        channel = EmbeddedChannel(HttpServerCodec(), unsupportedHandler)
        val unsupported = createUpgradeRequest("/unsupported")
        unsupported.headers().set(HttpHeaderNames.SEC_WEBSOCKET_VERSION, "12")
        channel.writeInbound(unsupported)
        channel.flushOutbound()
        assertTrue(channel.outboundMessages().isNotEmpty())
        channel.close()
    }

    @Test
    fun `WebSocket lifecycle callbacks resume after genuine suspension`() {
        val app = createApplication()
        val finished = CountDownLatch(1)
        app.install(object : CallMiddleware {
            override suspend fun beforeCall(call: ServerCall) {
                delay(1)
            }

            override suspend fun afterCall(call: ServerCall) {
                delay(1)
                finished.countDown()
            }
        })
        app.installAuth(object : AuthMiddleware {
            override suspend fun authenticate(call: ServerCall, authConfig: AuthConfig?) {
                delay(1)
            }
        })
        app.routing {
            authenticate("test") {
                webSocket("/suspending-ws") {
                    delay(1)
                }
            }
        }

        val handler = NettyHttpHandler(app, CoroutineScope(Dispatchers.Default + SupervisorJob()), 10_000_000L)
        val channel = EmbeddedChannel(HttpServerCodec(), handler)
        channel.writeInbound(createUpgradeRequest("/suspending-ws"))
        channel.flushOutbound()

        assertTrue(finished.await(5, TimeUnit.SECONDS))
        channel.finishAndReleaseAll()
    }
}
