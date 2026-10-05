package bosca.server.netty

import bosca.server.BoscaApplication
import bosca.server.HttpStatusCode
import bosca.server.config.ApplicationConfig
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.netty.buffer.Unpooled
import io.netty.buffer.UnpooledByteBufAllocator
import io.netty.channel.Channel
import io.netty.channel.ChannelConfig
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelHandlerContext
import io.netty.util.Attribute
import io.netty.util.AttributeKey
import io.netty.util.concurrent.ImmediateEventExecutor
import io.netty.handler.codec.http.DefaultFullHttpRequest
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.DefaultHttpContent
import io.netty.handler.codec.http.DefaultHttpRequest
import io.netty.handler.codec.http.DefaultLastHttpContent
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpVersion
import io.netty.handler.codec.http.HttpMethod as NettyHttpMethod
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NettyHttpHandlerTest {

    private fun createMockCtx(): ChannelHandlerContext {
        val ctx = mockk<ChannelHandlerContext>(relaxed = true)
        val channel = mockk<Channel>(relaxed = true)
        val config = mockk<ChannelConfig>(relaxed = true)
        every { ctx.channel() } returns channel
        every { channel.config() } returns config
        every { channel.isActive } returns true
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
                override fun setIfAbsent(value: Any?): Any? { return attributes.putIfAbsent(key, value) }
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

    @Test
    fun `ordinary request before channel activation returns internal server error`() {
        val ctx = createMockCtx()
        val app = createApplication()
        app.router.get("/test") {
            call.respond(HttpStatusCode.OK, "unexpected")
        }
        val handler = NettyHttpHandler(
            app,
            CoroutineScope(Dispatchers.Default + SupervisorJob()),
            10_000_000L,
        )

        handler.channelRead(ctx, DefaultHttpRequest(HttpVersion.HTTP_1_1, NettyHttpMethod.GET, "/test"))

        verify {
            ctx.writeAndFlush(match { message ->
                message is DefaultFullHttpResponse && message.status() == HttpResponseStatus.INTERNAL_SERVER_ERROR
            })
        }
    }

    @Test
    fun `streaming request before channel activation returns internal server error`() {
        val ctx = createMockCtx()
        val app = createApplication()
        app.router.sse("/events") {}
        val handler = NettyHttpHandler(
            app,
            CoroutineScope(Dispatchers.Default + SupervisorJob()),
            10_000_000L,
        )
        val request = DefaultHttpRequest(HttpVersion.HTTP_1_1, NettyHttpMethod.GET, "/events")
        request.headers().set(HttpHeaderNames.ACCEPT, "text/event-stream")

        handler.channelRead(ctx, request)

        verify {
            ctx.writeAndFlush(match { message ->
                message is DefaultFullHttpResponse && message.status() == HttpResponseStatus.INTERNAL_SERVER_ERROR
            })
        }
    }

    @Test
    fun `channel may become inactive before activation initializes scopes`() {
        val ctx = createMockCtx()
        val handler = NettyHttpHandler(
            createApplication(),
            CoroutineScope(Dispatchers.Default + SupervisorJob()),
            10_000_000L,
        )
        ctx.channel().attr(ChannelAttributes.SCOPE).set(
            object : CoroutineScope {
                override val coroutineContext = EmptyCoroutineContext
            },
        )

        handler.channelInactive(ctx)

        verify { ctx.fireChannelInactive() }
    }

    @Test
    fun `handles GET request with no body`() = runTest {
        val ctx = createMockCtx()
        val app = createApplication()
        var handlerCalled = false
        val latch = CountDownLatch(1)

        app.router.get("/test") {
            val bytes = call.request.bodyBytes()
            assertEquals(0, bytes.size)
            call.respond(HttpStatusCode.OK, "ok")
            handlerCalled = true
            latch.countDown()
        }

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val handler = NettyHttpHandler(app, scope, 10_000_000L)

        val request = DefaultHttpRequest(HttpVersion.HTTP_1_1, NettyHttpMethod.GET, "/test")
        handler.readInbound(ctx, request)
        handler.readInbound(ctx, DefaultLastHttpContent(Unpooled.EMPTY_BUFFER))

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Handler timed out")
        assertTrue(handlerCalled, "Route handler should have been called")
    }

    @Test
    fun `handles POST with body streamed across chunks`() = runTest {
        val ctx = createMockCtx()
        val app = createApplication()
        var receivedBody = ""
        val latch = CountDownLatch(1)

        app.router.post("/upload") {
            receivedBody = call.request.bodyText()
            call.respond(HttpStatusCode.OK, "ok")
            latch.countDown()
        }

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val handler = NettyHttpHandler(app, scope, 10_000_000L)

        val request = DefaultHttpRequest(HttpVersion.HTTP_1_1, NettyHttpMethod.POST, "/upload")
        request.headers().set(HttpHeaderNames.CONTENT_LENGTH, "11")
        handler.readInbound(ctx, request)

        handler.readInbound(ctx, DefaultHttpContent(
            Unpooled.copiedBuffer("hello ", StandardCharsets.UTF_8)
        ))
        handler.readInbound(ctx, DefaultLastHttpContent(
            Unpooled.copiedBuffer("world", StandardCharsets.UTF_8)
        ))

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Handler timed out")
        assertEquals("hello world", receivedBody)
    }

    @Test
    fun `sends 404 for unresolved route`() {
        val ctx = createMockCtx()
        val app = createApplication()
        val latch = CountDownLatch(1)
        val future = mockk<ChannelFuture>(relaxed = true)
        every { ctx.writeAndFlush(any()) } answers {
            latch.countDown()
            future
        }
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val handler = NettyHttpHandler(app, scope, 10_000_000L)

        val request = DefaultHttpRequest(HttpVersion.HTTP_1_1, NettyHttpMethod.GET, "/nonexistent")
        handler.readInbound(ctx, request)
        handler.readInbound(ctx, DefaultLastHttpContent(Unpooled.EMPTY_BUFFER))

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Handler timed out")
        verify { ctx.writeAndFlush(match { msg ->
            msg is DefaultFullHttpResponse && msg.status() == HttpResponseStatus.NOT_FOUND
        }) }
    }

    @Test
    fun `handles FullHttpRequest in one message`() = runTest {
        val ctx = createMockCtx()
        val app = createApplication()
        var receivedBody = ""
        val latch = CountDownLatch(1)

        app.router.post("/api") {
            receivedBody = call.request.bodyText()
            call.respond(HttpStatusCode.OK, "ok")
            latch.countDown()
        }

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val handler = NettyHttpHandler(app, scope, 10_000_000L)

        val fullRequest = DefaultFullHttpRequest(
            HttpVersion.HTTP_1_1,
            NettyHttpMethod.POST,
            "/api",
            Unpooled.copiedBuffer("full body", StandardCharsets.UTF_8)
        )
        fullRequest.headers().set(HttpHeaderNames.CONTENT_LENGTH, "9")
        handler.readInbound(ctx, fullRequest)

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Handler timed out")
        assertEquals("full body", receivedBody)
    }

    @Test
    fun `chunked transfer encoding detected as having body`() = runTest {
        val ctx = createMockCtx()
        val app = createApplication()
        var receivedBody = ""
        val latch = CountDownLatch(1)

        app.router.post("/chunked") {
            receivedBody = call.request.bodyText()
            call.respond(HttpStatusCode.OK, "ok")
            latch.countDown()
        }

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val handler = NettyHttpHandler(app, scope, 10_000_000L)

        val request = DefaultHttpRequest(HttpVersion.HTTP_1_1, NettyHttpMethod.POST, "/chunked")
        request.headers().set(HttpHeaderNames.TRANSFER_ENCODING, "chunked")
        handler.readInbound(ctx, request)

        handler.readInbound(ctx, DefaultHttpContent(
            Unpooled.copiedBuffer("chunked data", StandardCharsets.UTF_8)
        ))
        handler.readInbound(ctx, DefaultLastHttpContent(Unpooled.EMPTY_BUFFER))

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Handler timed out")
        assertEquals("chunked data", receivedBody)
    }

    @Test
    fun `path parameters are extracted`() = runTest {
        val ctx = createMockCtx()
        val app = createApplication()
        var capturedId = ""
        val latch = CountDownLatch(1)

        app.router.get("/items/{id}") {
            capturedId = call.pathParameters["id"] ?: ""
            call.respond(HttpStatusCode.OK, "ok")
            latch.countDown()
        }

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val handler = NettyHttpHandler(app, scope, 10_000_000L)

        val request = DefaultHttpRequest(HttpVersion.HTTP_1_1, NettyHttpMethod.GET, "/items/42")
        handler.readInbound(ctx, request)
        handler.readInbound(ctx, DefaultLastHttpContent(Unpooled.EMPTY_BUFFER))

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Handler timed out")
        assertEquals("42", capturedId)
    }

    @Test
    fun `percent encoded path parameters are decoded`() = runTest {
        val ctx = createMockCtx()
        val app = createApplication()
        var capturedId = ""
        val latch = CountDownLatch(1)

        app.router.get("/items/{id}") {
            capturedId = call.pathParameters["id"] ?: ""
            call.respond(HttpStatusCode.OK, "ok")
            latch.countDown()
        }

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val handler = NettyHttpHandler(app, scope, 10_000_000L)

        val request = DefaultHttpRequest(HttpVersion.HTTP_1_1, NettyHttpMethod.GET, "/items/hello%20world")
        handler.readInbound(ctx, request)
        handler.readInbound(ctx, DefaultLastHttpContent(Unpooled.EMPTY_BUFFER))

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Handler timed out")
        assertEquals("hello world", capturedId)
    }

    @Test
    fun `plus in path retains existing URL decoder behavior`() = runTest {
        val ctx = createMockCtx()
        val app = createApplication()
        var capturedId = ""
        val latch = CountDownLatch(1)

        app.router.get("/items/{id}") {
            capturedId = call.pathParameters["id"] ?: ""
            call.respond(HttpStatusCode.OK, "ok")
            latch.countDown()
        }

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val handler = NettyHttpHandler(app, scope, 10_000_000L)

        val request = DefaultHttpRequest(HttpVersion.HTTP_1_1, NettyHttpMethod.GET, "/items/hello+world")
        handler.readInbound(ctx, request)
        handler.readInbound(ctx, DefaultLastHttpContent(Unpooled.EMPTY_BUFFER))

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Handler timed out")
        assertEquals("hello world", capturedId)
    }

    @Test
    fun `exception in handler is handled gracefully`() = runTest {
        val ctx = createMockCtx()
        val app = createApplication()
        val latch = CountDownLatch(1)
        val future = mockk<ChannelFuture>(relaxed = true)
        every { ctx.writeAndFlush(any()) } answers {
            latch.countDown()
            future
        }

        app.router.get("/error") {
            throw RuntimeException("test error")
        }

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val handler = NettyHttpHandler(app, scope, 10_000_000L)

        val request = DefaultHttpRequest(HttpVersion.HTTP_1_1, NettyHttpMethod.GET, "/error")
        handler.readInbound(ctx, request)
        handler.readInbound(ctx, DefaultLastHttpContent(Unpooled.EMPTY_BUFFER))

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Handler timed out")

        // Should have written an error response
        verify(atLeast = 1) { ctx.writeAndFlush(any()) }
    }

    @Test
    fun `exceptionCaught closes context`() {
        val ctx = createMockCtx()
        val app = createApplication()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val handler = NettyHttpHandler(app, scope, 10_000_000L)

        handler.exceptionCaught(ctx, RuntimeException("channel error"))

        verify { ctx.close() }
    }

    @Test
    fun `DELETE request has no body`() = runTest {
        val ctx = createMockCtx()
        val app = createApplication()
        var handlerCalled = false
        val latch = CountDownLatch(1)

        app.router.delete("/items/{id}") {
            val bytes = call.request.bodyBytes()
            assertEquals(0, bytes.size)
            handlerCalled = true
            call.respond(HttpStatusCode.OK, "deleted")
            latch.countDown()
        }

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val handler = NettyHttpHandler(app, scope, 10_000_000L)

        val request = DefaultHttpRequest(HttpVersion.HTTP_1_1, NettyHttpMethod.DELETE, "/items/99")
        handler.readInbound(ctx, request)
        handler.readInbound(ctx, DefaultLastHttpContent(Unpooled.EMPTY_BUFFER))

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Handler timed out")
        assertTrue(handlerCalled)
    }

    @Test
    fun `multiple sequential requests on same connection`() = runTest {
        val ctx = createMockCtx()
        val app = createApplication()
        val responses = mutableListOf<String>()
        val latch1 = CountDownLatch(1)
        val latch2 = CountDownLatch(1)

        app.router.get("/seq") {
            responses.add("get")
            call.respond(HttpStatusCode.OK, "ok")
            latch1.countDown()
        }
        app.router.post("/seq") {
            val body = call.request.bodyText()
            responses.add("post:$body")
            call.respond(HttpStatusCode.OK, "ok")
            latch2.countDown()
        }

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val handler = NettyHttpHandler(app, scope, 10_000_000L)

        // First request: GET
        val req1 = DefaultHttpRequest(HttpVersion.HTTP_1_1, NettyHttpMethod.GET, "/seq")
        handler.readInbound(ctx, req1)
        handler.readInbound(ctx, DefaultLastHttpContent(Unpooled.EMPTY_BUFFER))
        assertTrue(latch1.await(5, TimeUnit.SECONDS), "Handler timed out")

        // Second request: POST with body
        val req2 = DefaultHttpRequest(HttpVersion.HTTP_1_1, NettyHttpMethod.POST, "/seq")
        req2.headers().set(HttpHeaderNames.CONTENT_LENGTH, "4")
        handler.readInbound(ctx, req2)
        handler.readInbound(ctx, DefaultLastHttpContent(
            Unpooled.copiedBuffer("data", StandardCharsets.UTF_8)
        ))
        assertTrue(latch2.await(5, TimeUnit.SECONDS), "Handler timed out")

        assertEquals(2, responses.size)
        assertEquals("get", responses[0])
        assertEquals("post:data", responses[1])
    }

    @Test
    fun `malformed percent encoding in URL path returns 400 Bad Request`() {
        val ctx = createMockCtx()
        val app = createApplication()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val handler = NettyHttpHandler(app, scope, 10_000_000L)

        // %GG is not a valid percent-encoded sequence; URLDecoder will throw
        // IllegalArgumentException, which the handler catches and converts to 400.
        val latch = CountDownLatch(1)
        every { ctx.writeAndFlush(any()) } answers {
            latch.countDown()
            val future = mockk<ChannelFuture>(relaxed = true)
            future
        }

        val request = DefaultHttpRequest(HttpVersion.HTTP_1_1, NettyHttpMethod.GET, "/items/%GG")
        handler.readInbound(ctx, request)
        handler.readInbound(ctx, DefaultLastHttpContent(Unpooled.EMPTY_BUFFER))

        assertTrue(latch.await(2, TimeUnit.SECONDS), "Response must be written within 2 seconds")
        verify { ctx.writeAndFlush(match { msg ->
            msg is DefaultFullHttpResponse && msg.status() == HttpResponseStatus.BAD_REQUEST
        }) }
    }

    @Test
    fun `malformed HEAD path preserves error length without a body`() {
        val ctx = createMockCtx()
        val handler = NettyHttpHandler(
            createApplication(),
            CoroutineScope(Dispatchers.Default + SupervisorJob()),
            10_000_000L,
        )
        val responseWritten = CountDownLatch(1)
        every { ctx.writeAndFlush(any()) } answers {
            responseWritten.countDown()
            mockk<ChannelFuture>(relaxed = true)
        }

        handler.readInbound(
            ctx,
            DefaultHttpRequest(HttpVersion.HTTP_1_1, NettyHttpMethod.HEAD, "/items/%GG"),
        )

        assertTrue(responseWritten.await(2, TimeUnit.SECONDS))
        verify {
            ctx.writeAndFlush(match { message ->
                message is DefaultFullHttpResponse &&
                    message.status() == HttpResponseStatus.BAD_REQUEST &&
                    message.headers()[HttpHeaderNames.CONTENT_LENGTH] == "15" &&
                    !message.content().isReadable
            })
        }
    }

    @Test
    fun `malformed percent encoding discards an allocated request body`() {
        val ctx = createMockCtx()
        val handler = NettyHttpHandler(
            createApplication(),
            CoroutineScope(Dispatchers.Default + SupervisorJob()),
            10_000_000L,
        )
        val request = DefaultHttpRequest(HttpVersion.HTTP_1_1, NettyHttpMethod.POST, "/items/%GG")
        request.headers().set(HttpHeaderNames.CONTENT_LENGTH, "1")

        handler.readInbound(ctx, request)

        verify(timeout = 2_000) {
            ctx.writeAndFlush(match { message ->
                message is DefaultFullHttpResponse && message.status() == HttpResponseStatus.BAD_REQUEST
            })
        }
    }

    @Test
    fun `body larger than maxRequestSize returns 413 Payload Too Large`() {
        val ctx = createMockCtx()
        val app = createApplication()

        // Use a very small maxRequestSize so the test body trivially exceeds it.
        val maxRequestSize = 10L
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val handler = NettyHttpHandler(app, scope, maxRequestSize)

        app.router.post("/upload-large") {
            // Consuming the body triggers size enforcement inside RequestBody and
            // causes RequestBodyTooLargeException to propagate to the handler.
            call.request.bodyText()
            call.respond(HttpStatusCode.OK, "ok")
        }

        val latch = CountDownLatch(1)
        every { ctx.writeAndFlush(any()) } answers {
            latch.countDown()
            val future = mockk<ChannelFuture>(relaxed = true)
            future
        }

        val oversizedBody = "x".repeat(100)
        val request = DefaultHttpRequest(HttpVersion.HTTP_1_1, NettyHttpMethod.POST, "/upload-large")
        request.headers().set(HttpHeaderNames.CONTENT_LENGTH, oversizedBody.length.toString())
        handler.readInbound(ctx, request)
        handler.readInbound(ctx, DefaultLastHttpContent(
            Unpooled.copiedBuffer(oversizedBody, StandardCharsets.UTF_8)
        ))

        assertTrue(latch.await(2, TimeUnit.SECONDS), "Response must be written within 2 seconds")
        verify { ctx.writeAndFlush(match { msg ->
            msg is DefaultFullHttpResponse && msg.status() == HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE
        }) }
    }

    @Test
    fun `a request whose headers are too large is answered 431 and never reaches its route`() {
        val app = createApplication()
        var routeRan = false
        app.router.get("/test") {
            routeRan = true
            call.respond(HttpStatusCode.OK, "ok")
        }
        val handler = NettyHttpHandler(app, CoroutineScope(Dispatchers.Default + SupervisorJob()), 10_000_000L, RequestDispatcherMode.EVENT_LOOP)
        // Small header limit, so an ordinary header exceeds it.
        val channel = io.netty.channel.embedded.EmbeddedChannel(io.netty.handler.codec.http.HttpServerCodec(4096, 64, 8192), handler)
        try {
            channel.writeInbound(
                Unpooled.copiedBuffer("GET /test HTTP/1.1\r\nHost: a\r\nX-Big: ${"x".repeat(200)}\r\n\r\n", StandardCharsets.US_ASCII),
            )

            val response = generateSequence { channel.readOutbound<io.netty.buffer.ByteBuf>() }
                .joinToString("") { buf -> buf.toString(StandardCharsets.UTF_8).also { buf.release() } }
            assertTrue(response.startsWith("HTTP/1.1 431"), "Got: $response")
            assertFalse(routeRan, "A request that failed to decode must never reach a route")
            assertFalse(channel.isActive, "The decoder discards the rest of the connection, so it is closed")
        } finally {
            runCatching { channel.finishAndReleaseAll() }
        }
    }

    @Test
    fun `a malformed chunk fails the body being read instead of ending it cleanly`() {
        val app = createApplication()
        val outcome = java.util.concurrent.atomic.AtomicReference<String>()
        app.router.post("/upload") {
            try {
                call.request.bodyText()
                outcome.set("read")
            } catch (e: java.io.IOException) {
                outcome.set("failed")
                throw e
            }
        }
        val handler = NettyHttpHandler(app, CoroutineScope(Dispatchers.Default + SupervisorJob()), 10_000_000L, RequestDispatcherMode.EVENT_LOOP)
        val channel = io.netty.channel.embedded.EmbeddedChannel(io.netty.handler.codec.http.HttpServerCodec(), handler)
        try {
            // A valid first chunk, then a chunk size that is not hex.
            channel.writeInbound(
                Unpooled.copiedBuffer(
                    "POST /upload HTTP/1.1\r\nHost: a\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhello\r\nzz\r\n",
                    StandardCharsets.US_ASCII,
                ),
            )
            channel.runPendingTasks()

            assertEquals("failed", outcome.get(), "A truncated upload must not read as complete")
            assertFalse(channel.isActive)
        } finally {
            runCatching { channel.finishAndReleaseAll() }
        }
    }
}
