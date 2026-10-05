package bosca.server.netty

import bosca.server.BoscaApplication
import bosca.server.ContentType
import bosca.server.HttpStatusCode
import bosca.server.config.ApplicationConfig
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.buffer.UnpooledByteBufAllocator
import io.netty.channel.Channel
import io.netty.channel.ChannelConfig
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelHandlerContext
import io.netty.util.Attribute
import io.netty.util.AttributeKey
import io.netty.handler.codec.http.DefaultFullHttpRequest
import io.netty.handler.codec.http.DefaultHttpContent
import io.netty.handler.codec.http.DefaultHttpRequest
import io.netty.handler.codec.http.DefaultLastHttpContent
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpVersion
import io.netty.handler.codec.http.LastHttpContent
import io.netty.util.concurrent.ImmediateEventExecutor
import io.netty.handler.codec.http.HttpMethod as NettyHttpMethod
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.ContinuationInterceptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Stress tests for [NettyHttpHandler] resource lifecycle under sequential
 * and concurrent load, verifying ByteBuf release, handler state cleanup,
 * and response streaming correctness.
 */
class NettyHttpHandlerStressTest {

    private fun createMockCtx(): ChannelHandlerContext {
        val ctx = mockk<ChannelHandlerContext>(relaxed = true)
        val channel = mockk<Channel>(relaxed = true)
        val config = mockk<ChannelConfig>(relaxed = true)
        every { ctx.channel() } returns channel
        every { channel.config() } returns config
        every { channel.isActive } returns true
        every { channel.isWritable } returns true
        every { ctx.executor() } returns ImmediateEventExecutor.INSTANCE
        every { ctx.alloc() } returns UnpooledByteBufAllocator.DEFAULT
        val future = mockk<ChannelFuture>(relaxed = true)
        every { ctx.writeAndFlush(any()) } returns future
        every { ctx.write(any()) } returns future
        every { config.isAutoRead } returns true

        val attributes = mutableMapOf<AttributeKey<*>, Any?>()
        every { channel.attr(any<AttributeKey<Any>>()) } answers {
            val key = firstArg<AttributeKey<Any>>()
            object : Attribute<Any> {
                override fun key() = key
                override fun get(): Any? = attributes[key]
                override fun set(value: Any?) { attributes[key] = value }
                override fun getAndSet(value: Any?): Any? { val old = attributes[key]; attributes[key] = value; return old }
                @Deprecated("Deprecated in Netty", replaceWith = ReplaceWith(""))
                override fun setIfAbsent(value: Any?): Any? { return attributes.putIfAbsent(key, value) }
                @Deprecated("Deprecated in Netty", replaceWith = ReplaceWith(""))
                override fun getAndRemove(): Any? { return attributes.remove(key) }
                override fun compareAndSet(oldValue: Any?, newValue: Any?): Boolean {
                    if (attributes[key] == oldValue) { attributes[key] = newValue; return true }; return false
                }
                override fun remove() { attributes.remove(key) }
            }
        }

        return ctx
    }

    private fun createApplication(): BoscaApplication {
        val config = mockk<ApplicationConfig>(relaxed = true)
        every { config.propertyOrNull(any()) } returns null
        return BoscaApplication(config)
    }

    // --- Sequential POST tests ---

    @Test
    fun `sequential POST requests release all body buffers`() = runTest {
        val ctx = createMockCtx()
        val app = createApplication()
        val handledCount = AtomicInteger(0)
        val latch = CountDownLatch(100)

        app.router.post("/data") {
            val text = call.request.bodyText()
            assertTrue(text.isNotEmpty())
            call.respond(HttpStatusCode.OK, "ok")
            handledCount.incrementAndGet()
            latch.countDown()
        }

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val handler = NettyHttpHandler(app, scope, 10_000_000L)

        val trackedBuffers = mutableListOf<ByteBuf>()

        repeat(100) { i ->
            // Each request: headers + 3 content chunks + last chunk
            val request = DefaultHttpRequest(HttpVersion.HTTP_1_1, NettyHttpMethod.POST, "/data")
            request.headers().set(HttpHeaderNames.TRANSFER_ENCODING, "chunked")
            handler.readInbound(ctx, request)

            repeat(3) { j ->
                val buf = Unpooled.copiedBuffer("req-$i-chunk-$j-", StandardCharsets.UTF_8)
                trackedBuffers.add(buf)
                handler.readInbound(ctx, DefaultHttpContent(buf))
            }
            handler.readInbound(ctx, DefaultLastHttpContent(Unpooled.EMPTY_BUFFER))

            // Wait for handler to process before sending next request
            // (simulating HTTP keep-alive sequential pipelining)
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Not all requests were handled")
        assertEquals(100, handledCount.get())

        // All content buffers should have been released by readAllBytes → buf.release()
        // Note: addContent retains, readAllBytes releases. The msg itself is released by
        // ReferenceCountUtil.release in channelRead's finally block.
        delay(200)
        for ((index, buf) in trackedBuffers.withIndex()) {
            assertEquals(
                0, buf.refCnt(),
                "Buffer $index was not released (refCnt=${buf.refCnt()})"
            )
        }
    }

    // --- Sequential GET tests ---

    @Test
    fun `sequential GET requests do not accumulate state`() = runTest {
        val ctx = createMockCtx()
        val app = createApplication()
        val handledCount = AtomicInteger(0)
        val latch = CountDownLatch(500)

        app.router.get("/health") {
            call.respond(HttpStatusCode.OK, "ok")
            handledCount.incrementAndGet()
            latch.countDown()
        }

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val handler = NettyHttpHandler(app, scope, 10_000_000L)

        repeat(500) {
            val request = DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1,
                NettyHttpMethod.GET,
                "/health",
                Unpooled.EMPTY_BUFFER
            )
            handler.readInbound(ctx, request)
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Not all requests were handled")
        assertEquals(500, handledCount.get())
    }

    // --- Concurrent POST tests ---

    @Test
    fun `concurrent POST requests on separate handlers release all buffers`() = runTest {
        val app = createApplication()
        val latch = CountDownLatch(50)

        app.router.post("/upload") {
            call.request.bodyText()
            call.respond(HttpStatusCode.OK, "ok")
            latch.countDown()
        }

        val allTrackedBuffers = java.util.Collections.synchronizedList(mutableListOf<ByteBuf>())

        coroutineScope {
            repeat(50) { connIdx ->
                launch(Dispatchers.Default) {
                    val ctx = createMockCtx()
                    val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
                    val handler = NettyHttpHandler(app, scope, 10_000_000L)

                    val request = DefaultHttpRequest(HttpVersion.HTTP_1_1, NettyHttpMethod.POST, "/upload")
                    request.headers().set(HttpHeaderNames.TRANSFER_ENCODING, "chunked")
                    handler.readInbound(ctx, request)

                    repeat(5) { j ->
                        val buf = Unpooled.copiedBuffer("conn-$connIdx-$j-", StandardCharsets.UTF_8)
                        allTrackedBuffers.add(buf)
                        handler.readInbound(ctx, DefaultHttpContent(buf))
                    }
                    handler.readInbound(ctx, DefaultLastHttpContent(Unpooled.EMPTY_BUFFER))
                }
            }
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS), "Not all handlers completed")

        // Allow buffer release to propagate
        delay(200)

        for ((index, buf) in allTrackedBuffers.withIndex()) {
            assertEquals(
                0, buf.refCnt(),
                "Buffer $index was not released (refCnt=${buf.refCnt()})"
            )
        }
    }

    // --- Exception path tests ---

    @Test
    fun `handler exception with FullHttpRequest reveals unconsumed body leak`() = runTest {
        val ctx = createMockCtx()
        val app = createApplication()
        val latch = CountDownLatch(1)

        app.router.post("/explode") {
            // Handler throws without reading the body
            latch.countDown()
            error("Intentional handler exception")
        }

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val handler = NettyHttpHandler(app, scope, 10_000_000L)

        val body = Unpooled.copiedBuffer("payload", StandardCharsets.UTF_8)
        val request = DefaultFullHttpRequest(
            HttpVersion.HTTP_1_1,
            NettyHttpMethod.POST,
            "/explode",
            body
        )
        request.headers().set(HttpHeaderNames.CONTENT_LENGTH, body.readableBytes())

        handler.readInbound(ctx, request)
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        // Use real-time sleep since the handler coroutine runs on Dispatchers.Default, not the test dispatcher
        Thread.sleep(500)

        assertEquals(0, body.refCnt(), "Body buffer should be released after handler exception via RequestBody.discard()")
    }

    @Test
    fun `unresolved route with FullHttpRequest reveals unconsumed body leak`() = runTest {
        val ctx = createMockCtx()
        val app = createApplication()
        // No routes registered — everything is 404

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val handler = NettyHttpHandler(app, scope, 10_000_000L)

        val body = Unpooled.copiedBuffer("orphan", StandardCharsets.UTF_8)
        val request = DefaultFullHttpRequest(
            HttpVersion.HTTP_1_1,
            NettyHttpMethod.POST,
            "/nonexistent",
            body
        )
        request.headers().set(HttpHeaderNames.CONTENT_LENGTH, body.readableBytes())

        handler.readInbound(ctx, request)
        // Use real-time sleep since the handler coroutine runs on Dispatchers.Default, not the test dispatcher
        Thread.sleep(500)

        assertEquals(0, body.refCnt(), "Body buffer should be released after 404 via RequestBody.discard()")
    }

    // --- respondOutputStream tests ---

    @Test
    fun `respondStreaming writes all chunks and terminates`() = runTest {
        val ctx = createMockCtx()
        val app = createApplication()
        val written = java.util.Collections.synchronizedList(mutableListOf<Any>())
        every { ctx.writeAndFlush(capture(written)) } returns mockk<ChannelFuture>(relaxed = true)
        every { ctx.write(capture(written)) } returns mockk<ChannelFuture>(relaxed = true)

        val latch = CountDownLatch(1)

        app.router.get("/stream-out") {
            call.response.respondStreaming(ContentType.Application.OctetStream) { stream ->
                repeat(100) {
                    stream.write(ByteArray(1024) { 0x42 })
                    stream.flush()
                }
            }
            latch.countDown()
        }

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val handler = NettyHttpHandler(app, scope, 10_000_000L)

        val request = DefaultFullHttpRequest(
            HttpVersion.HTTP_1_1,
            NettyHttpMethod.GET,
            "/stream-out",
            Unpooled.EMPTY_BUFFER
        )
        handler.readInbound(ctx, request)

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Handler should complete")

        // Verify LastHttpContent was sent (the final write)
        val lastWritten = written.lastOrNull()
        assertTrue(
            lastWritten is LastHttpContent,
            "Last written object should be LastHttpContent, got: ${lastWritten?.javaClass?.simpleName}"
        )
    }

    // --- State lifecycle tests ---

    @Test
    fun `channel active initializes a channel-owned scope`() {
        val ctx = createMockCtx()
        val app = createApplication()
        val engineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val handler = NettyHttpHandler(app, engineScope, 10_000_000L)

        handler.channelActive(ctx)

        val channelScope = assertNotNull(ctx.channel().attr(ChannelAttributes.SCOPE).get())
        assertNotSame(engineScope, channelScope)
        assertSame(
            engineScope.coroutineContext[ContinuationInterceptor],
            channelScope.coroutineContext[ContinuationInterceptor],
        )
        assertSame(
            engineScope.coroutineContext[Job],
            channelScope.coroutineContext[Job]?.parent,
            "The channel job must be a child of the engine job",
        )
    }

    @Test
    fun `shared handler isolates request state between channels`() = runTest {
        val firstContext = createMockCtx()
        val secondContext = createMockCtx()
        val app = createApplication()
        val receivedBodies = ConcurrentHashMap<String, String>()
        val handled = CountDownLatch(2)

        app.router.post("/data/{id}") {
            receivedBodies[call.pathParameters["id"] ?: error("missing id")] = call.request.bodyText()
            call.respond(HttpStatusCode.OK, "ok")
            handled.countDown()
        }

        val handler = NettyHttpHandler(
            app,
            CoroutineScope(Dispatchers.Default + SupervisorJob()),
            10_000_000L,
        )
        handler.channelActive(firstContext)
        handler.channelActive(secondContext)

        try {
            assertNotSame(
                firstContext.channel().attr(ChannelAttributes.SCOPE).get(),
                secondContext.channel().attr(ChannelAttributes.SCOPE).get(),
            )

            val firstRequest = DefaultHttpRequest(HttpVersion.HTTP_1_1, NettyHttpMethod.POST, "/data/first")
            firstRequest.headers().set(HttpHeaderNames.CONTENT_LENGTH, "5")
            val secondRequest = DefaultHttpRequest(HttpVersion.HTTP_1_1, NettyHttpMethod.POST, "/data/second")
            secondRequest.headers().set(HttpHeaderNames.CONTENT_LENGTH, "4")

            handler.readInbound(firstContext, firstRequest)
            handler.readInbound(secondContext, secondRequest)
            handler.readInbound(
                secondContext,
                DefaultLastHttpContent(Unpooled.copiedBuffer("beta", StandardCharsets.UTF_8)),
            )
            handler.readInbound(
                firstContext,
                DefaultLastHttpContent(Unpooled.copiedBuffer("alpha", StandardCharsets.UTF_8)),
            )

            assertTrue(handled.await(5, TimeUnit.SECONDS), "Both channel requests should complete")
            assertEquals("alpha", receivedBodies["first"])
            assertEquals("beta", receivedBodies["second"])
        } finally {
            handler.channelInactive(firstContext)
            handler.channelInactive(secondContext)
        }
    }

    @Test
    fun `sequential POST then GET clears currentBody`() = runTest {
        val ctx = createMockCtx()
        val app = createApplication()
        val bodies = java.util.Collections.synchronizedList(mutableListOf<String>())
        val postLatch = CountDownLatch(1)
        val getLatch = CountDownLatch(1)

        app.router.post("/submit") {
            bodies.add(call.request.bodyText())
            call.respond(HttpStatusCode.OK, "posted")
            postLatch.countDown()
        }
        app.router.get("/check") {
            bodies.add("GET-no-body")
            call.respond(HttpStatusCode.OK, "checked")
            getLatch.countDown()
        }

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val handler = NettyHttpHandler(app, scope, 10_000_000L)

        // POST with body
        val postRequest = DefaultHttpRequest(HttpVersion.HTTP_1_1, NettyHttpMethod.POST, "/submit")
        postRequest.headers().set(HttpHeaderNames.CONTENT_LENGTH, "5")
        handler.readInbound(ctx, postRequest)
        handler.readInbound(ctx, DefaultLastHttpContent(
            Unpooled.copiedBuffer("hello", StandardCharsets.UTF_8)
        ))

        // Wait for POST handler to fully complete before sending GET
        assertTrue(postLatch.await(5, TimeUnit.SECONDS), "POST handler should complete")

        // GET without body
        val getRequest = DefaultFullHttpRequest(
            HttpVersion.HTTP_1_1,
            NettyHttpMethod.GET,
            "/check",
            Unpooled.EMPTY_BUFFER
        )
        handler.readInbound(ctx, getRequest)

        assertTrue(getLatch.await(5, TimeUnit.SECONDS), "GET handler should complete")
        assertEquals(2, bodies.size)
        assertEquals("hello", bodies[0])
        assertEquals("GET-no-body", bodies[1])
    }

    @Test
    fun `rapid alternating body and bodyless requests`() = runTest {
        val ctx = createMockCtx()
        val app = createApplication()
        val handledCount = AtomicInteger(0)
        val latch = CountDownLatch(200)

        app.router.post("/data") {
            call.request.bodyText()
            call.respond(HttpStatusCode.OK, "ok")
            handledCount.incrementAndGet()
            latch.countDown()
        }
        app.router.get("/ping") {
            call.respond(HttpStatusCode.OK, "pong")
            handledCount.incrementAndGet()
            latch.countDown()
        }

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val handler = NettyHttpHandler(app, scope, 10_000_000L)

        val trackedBuffers = mutableListOf<ByteBuf>()

        repeat(200) { i ->
            if (i % 2 == 0) {
                // POST with body
                val request = DefaultHttpRequest(HttpVersion.HTTP_1_1, NettyHttpMethod.POST, "/data")
                request.headers().set(HttpHeaderNames.CONTENT_LENGTH, "4")
                handler.readInbound(ctx, request)
                val buf = Unpooled.copiedBuffer("data", StandardCharsets.UTF_8)
                trackedBuffers.add(buf)
                handler.readInbound(ctx, DefaultLastHttpContent(buf))
            } else {
                // GET without body
                val request = DefaultFullHttpRequest(
                    HttpVersion.HTTP_1_1,
                    NettyHttpMethod.GET,
                    "/ping",
                    Unpooled.EMPTY_BUFFER
                )
                handler.readInbound(ctx, request)
            }
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS), "Not all requests handled in time")
        assertEquals(200, handledCount.get())

        delay(200)
        for ((index, buf) in trackedBuffers.withIndex()) {
            assertEquals(
                0, buf.refCnt(),
                "POST buffer $index was not released (refCnt=${buf.refCnt()})"
            )
        }
    }

    // --- Unconsumed body leak detection ---

    @Test
    fun `POST body never read by handler reveals buffer abandonment`() = runTest {
        val ctx = createMockCtx()
        val app = createApplication()
        val latch = CountDownLatch(1)

        app.router.post("/ignore-body") {
            // Respond without reading the body at all
            call.respond(HttpStatusCode.OK, "ignored")
            latch.countDown()
        }

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val handler = NettyHttpHandler(app, scope, 10_000_000L)

        val trackedBuffers = mutableListOf<ByteBuf>()

        val request = DefaultHttpRequest(HttpVersion.HTTP_1_1, NettyHttpMethod.POST, "/ignore-body")
        request.headers().set(HttpHeaderNames.TRANSFER_ENCODING, "chunked")
        handler.readInbound(ctx, request)

        repeat(5) { i ->
            val buf = Unpooled.copiedBuffer("abandoned-$i-", StandardCharsets.UTF_8)
            trackedBuffers.add(buf)
            handler.readInbound(ctx, DefaultHttpContent(buf))
        }
        handler.readInbound(ctx, DefaultLastHttpContent(Unpooled.EMPTY_BUFFER))

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Handler should complete")
        withContext(Dispatchers.Default) {
            withTimeout(5.seconds) {
                while (trackedBuffers.any { it.refCnt() > 0 }) {
                    delay(10.milliseconds)
                }
            }
        }

        val leakedBuffers = trackedBuffers.count { it.refCnt() > 0 }
        assertEquals(0, leakedBuffers, "All buffers should be released via RequestBody.discard() in finally block")
    }
}
