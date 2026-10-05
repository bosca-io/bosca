package bosca.server.netty

import bosca.server.BoscaApplication
import bosca.server.HttpStatusCode
import bosca.server.Parameters
import bosca.server.RequestBody
import bosca.server.RequestBodyTooLargeException
import bosca.server.ServerCall
import bosca.server.ServerRequest
import bosca.server.ServerResponse
import bosca.server.config.ApplicationConfig
import bosca.server.middleware.AuthMiddleware
import bosca.server.middleware.CallMiddleware
import bosca.server.routing.AuthConfig
import bosca.server.websocket.WebSocketFrame
import bosca.server.websocket.WebSocketSession
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.netty.buffer.Unpooled
import io.netty.buffer.UnpooledByteBufAllocator
import io.netty.channel.Channel
import io.netty.channel.ChannelConfig
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelPipeline
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.util.Attribute
import io.netty.util.AttributeKey
import io.netty.util.concurrent.GenericFutureListener
import io.netty.util.concurrent.ImmediateEventExecutor
import io.netty.handler.codec.http.DefaultFullHttpRequest
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.DefaultHttpContent
import io.netty.handler.codec.http.DefaultHttpHeaders
import io.netty.handler.codec.http.DefaultHttpRequest
import io.netty.handler.codec.http.DefaultHttpResponse
import io.netty.handler.codec.http.DefaultLastHttpContent
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpHeaderValues
import io.netty.handler.codec.http.HttpRequest
import io.netty.handler.codec.http.HttpObjectAggregator
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpServerCodec
import io.netty.handler.codec.http.HttpServerKeepAliveHandler
import io.netty.handler.codec.http.HttpVersion
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame
import io.netty.handler.timeout.IdleStateEvent
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshaker
import io.netty.handler.stream.ChunkedWriteHandler
import io.netty.handler.codec.http.HttpMethod as NettyHttpMethod
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Integration tests for [NettyHttpHandler] covering WebSocket upgrades, SSE routes,
 * middleware execution, and authentication middleware paths.
 */
class NettyHttpHandlerIntegrationTest {

    private fun succeededFuture(): ChannelFuture {
        val future = mockk<ChannelFuture>(relaxed = true)
        every { future.isSuccess } returns true
        every { future.cause() } returns null
        val listenerSlot = slot<GenericFutureListener<ChannelFuture>>()
        every { future.addListener(capture(listenerSlot)) } answers {
            listenerSlot.captured.operationComplete(future)
            future
        }
        return future
    }

    private fun createMockCtx(): ChannelHandlerContext {
        val ctx = mockk<ChannelHandlerContext>(relaxed = true)
        val channel = mockk<Channel>(relaxed = true)
        val config = mockk<ChannelConfig>(relaxed = true)
        val pipeline = mockk<ChannelPipeline>(relaxed = true)
        every { ctx.channel() } returns channel
        every { channel.config() } returns config
        every { channel.isActive } returns true
        every { channel.isWritable } returns true
        every { channel.pipeline() } returns pipeline
        every { ctx.pipeline() } returns pipeline
        every { ctx.executor() } returns ImmediateEventExecutor.INSTANCE
        every { ctx.alloc() } returns UnpooledByteBufAllocator.DEFAULT
        every { ctx.writeAndFlush(any()) } returns succeededFuture()
        every { ctx.write(any()) } returns succeededFuture()
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

    // --- SSE Route Tests ---

    @Test
    fun `SSE route sends headers and event data`() = runTest {
        val ctx = createMockCtx()
        val app = createApplication()
        val latch = CountDownLatch(1)

        app.routing {
            sse("/events") {
                send("hello", event = "message")
                latch.countDown()
            }
        }

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val handler = NettyHttpHandler(app, scope, 10_000_000L)

        val request = DefaultHttpRequest(HttpVersion.HTTP_1_1, NettyHttpMethod.GET, "/events")
        request.headers().set(HttpHeaderNames.ACCEPT, "text/event-stream")
        handler.readInbound(ctx, request)
        handler.readInbound(ctx, DefaultLastHttpContent(Unpooled.EMPTY_BUFFER))

        assertTrue(latch.await(5, TimeUnit.SECONDS), "SSE handler should have been called")
    }

    @Test
    fun `SSE route receives middleware beforeCall`() = runTest {
        val ctx = createMockCtx()
        val app = createApplication()
        val middlewareCalled = AtomicBoolean(false)
        val latch = CountDownLatch(1)

        app.install(object : CallMiddleware {
            override suspend fun beforeCall(call: ServerCall) {
                middlewareCalled.set(true)
            }
        })

        app.routing {
            sse("/events") {
                send("test")
                latch.countDown()
            }
        }

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val handler = NettyHttpHandler(app, scope, 10_000_000L)

        val request = DefaultHttpRequest(HttpVersion.HTTP_1_1, NettyHttpMethod.GET, "/events")
        request.headers().set(HttpHeaderNames.ACCEPT, "text/event-stream")
        handler.readInbound(ctx, request)
        handler.readInbound(ctx, DefaultLastHttpContent(Unpooled.EMPTY_BUFFER))

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertTrue(middlewareCalled.get(), "CallMiddleware beforeCall should run for SSE routes")
    }

    // --- CallMiddleware Tests ---

    @Test
    fun `beforeCall middleware runs before handler`() = runTest {
        val ctx = createMockCtx()
        val app = createApplication()
        val order = java.util.Collections.synchronizedList(mutableListOf<String>())
        val latch = CountDownLatch(1)

        app.install(object : CallMiddleware {
            override suspend fun beforeCall(call: ServerCall) {
                order.add("middleware")
            }
        })

        app.router.get("/test") {
            order.add("handler")
            call.respond(HttpStatusCode.OK, "ok")
            latch.countDown()
        }

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val handler = NettyHttpHandler(app, scope, 10_000_000L)

        val request = DefaultHttpRequest(HttpVersion.HTTP_1_1, NettyHttpMethod.GET, "/test")
        handler.readInbound(ctx, request)
        handler.readInbound(ctx, DefaultLastHttpContent(Unpooled.EMPTY_BUFFER))

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("middleware", "handler"), order)
    }

    @Test
    fun `afterCall middleware runs after handler`() = runTest {
        val ctx = createMockCtx()
        val app = createApplication()
        val order = java.util.Collections.synchronizedList(mutableListOf<String>())
        val latch = CountDownLatch(1)

        app.install(object : CallMiddleware {
            override suspend fun afterCall(call: ServerCall) {
                order.add("after")
                latch.countDown()
            }
        })

        app.router.get("/test") {
            order.add("handler")
            call.respond(HttpStatusCode.OK, "ok")
        }

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val handler = NettyHttpHandler(app, scope, 10_000_000L)

        val request = DefaultHttpRequest(HttpVersion.HTTP_1_1, NettyHttpMethod.GET, "/test")
        handler.readInbound(ctx, request)
        handler.readInbound(ctx, DefaultLastHttpContent(Unpooled.EMPTY_BUFFER))

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals("handler", order[0])
        assertEquals("after", order[1])
    }

    @Test
    fun `afterCall middleware completes after the channel closes`() = runTest {
        val ctx = createMockCtx()
        val app = createApplication()
        val handlerStarted = CountDownLatch(1)
        val cleanupCompleted = CountDownLatch(1)

        app.install(object : CallMiddleware {
            override suspend fun afterCall(call: ServerCall) {
                delay(10)
                cleanupCompleted.countDown()
            }
        })

        app.router.get("/stream") {
            handlerStarted.countDown()
            awaitCancellation()
        }

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val handler = NettyHttpHandler(app, scope, 10_000_000L)
        handler.channelActive(ctx)

        val request = DefaultHttpRequest(HttpVersion.HTTP_1_1, NettyHttpMethod.GET, "/stream")
        handler.readInbound(ctx, request)
        handler.readInbound(ctx, DefaultLastHttpContent(Unpooled.EMPTY_BUFFER))

        assertTrue(handlerStarted.await(5, TimeUnit.SECONDS))
        handler.channelInactive(ctx)
        assertTrue(
            cleanupCompleted.await(5, TimeUnit.SECONDS),
            "Suspending middleware cleanup must finish after channel cancellation",
        )
    }

    @Test
    fun `SSE afterCall middleware completes after the channel closes`() = runTest {
        val ctx = createMockCtx()
        val app = createApplication()
        val handlerStarted = CountDownLatch(1)
        val cleanupCompleted = CountDownLatch(1)

        app.install(object : CallMiddleware {
            override suspend fun afterCall(call: ServerCall) {
                delay(10)
                cleanupCompleted.countDown()
            }
        })

        app.routing {
            sse("/events") {
                send("connected")
                handlerStarted.countDown()
                awaitCancellation()
            }
        }

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val handler = NettyHttpHandler(app, scope, 10_000_000L)
        handler.channelActive(ctx)

        val request = DefaultHttpRequest(HttpVersion.HTTP_1_1, NettyHttpMethod.GET, "/events")
        request.headers().set(HttpHeaderNames.ACCEPT, "text/event-stream")
        handler.readInbound(ctx, request)
        handler.readInbound(ctx, DefaultLastHttpContent(Unpooled.EMPTY_BUFFER))

        assertTrue(handlerStarted.await(5, TimeUnit.SECONDS))
        handler.channelInactive(ctx)
        assertTrue(
            cleanupCompleted.await(5, TimeUnit.SECONDS),
            "SSE middleware cleanup must finish after channel cancellation",
        )
    }

    @Test
    fun `afterCall middleware cancellation is propagated`() = runTest {
        val ctx = createMockCtx()
        val app = createApplication()
        val cancellationThrown = CountDownLatch(1)
        val earlierMiddlewareCalled = CountDownLatch(1)

        app.install(object : CallMiddleware {
            override suspend fun afterCall(call: ServerCall) {
                earlierMiddlewareCalled.countDown()
            }
        })
        app.install(object : CallMiddleware {
            override suspend fun afterCall(call: ServerCall) {
                cancellationThrown.countDown()
                throw CancellationException("middleware cancelled")
            }
        })

        app.router.get("/test") {
            call.respond(HttpStatusCode.OK, "ok")
        }

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val handler = NettyHttpHandler(app, scope, 10_000_000L)
        val request = DefaultHttpRequest(HttpVersion.HTTP_1_1, NettyHttpMethod.GET, "/test")
        handler.readInbound(ctx, request)
        handler.readInbound(ctx, DefaultLastHttpContent(Unpooled.EMPTY_BUFFER))

        assertTrue(cancellationThrown.await(5, TimeUnit.SECONDS))
        assertFalse(
            earlierMiddlewareCalled.await(250, TimeUnit.MILLISECONDS),
            "Cancellation must stop the afterCall unwind instead of being logged and swallowed",
        )
    }

    @Test
    fun `beforeCall middleware can short-circuit by committing response`() = runTest {
        val ctx = createMockCtx()
        val app = createApplication()
        val handlerCalled = AtomicBoolean(false)
        val latch = CountDownLatch(1)

        app.install(object : CallMiddleware {
            override suspend fun beforeCall(call: ServerCall) {
                call.respond(HttpStatusCode.Forbidden, "blocked")
            }

            override suspend fun afterCall(call: ServerCall) {
                latch.countDown()
            }
        })
        app.install(object : CallMiddleware {
            override suspend fun beforeCall(call: ServerCall) {
                error("middleware after a committed response must not run")
            }
        })

        app.router.get("/test") {
            handlerCalled.set(true)
            call.respond(HttpStatusCode.OK, "ok")
        }

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val handler = NettyHttpHandler(app, scope, 10_000_000L)

        val request = DefaultHttpRequest(HttpVersion.HTTP_1_1, NettyHttpMethod.GET, "/test")
        handler.readInbound(ctx, request)
        handler.readInbound(ctx, DefaultLastHttpContent(Unpooled.EMPTY_BUFFER))

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        // The handler should not have been called since middleware committed the response
        Thread.sleep(200)
        assertTrue(!handlerCalled.get(), "Handler should not run when middleware commits response")
    }

    @Test
    fun `onException middleware handles handler errors`() = runTest {
        val ctx = createMockCtx()
        val app = createApplication()
        val exceptionCaught = AtomicReference<Throwable?>(null)
        val latch = CountDownLatch(1)

        app.install(object : CallMiddleware {
            override suspend fun onException(call: ServerCall, cause: Throwable) {
                error("exception unwind must stop after a response is committed")
            }
        })
        app.install(object : CallMiddleware {
            override suspend fun onException(call: ServerCall, cause: Throwable) {
                exceptionCaught.set(cause)
                call.respond(HttpStatusCode.BadRequest, "custom error")
            }

            override suspend fun afterCall(call: ServerCall) {
                latch.countDown()
            }
        })

        app.router.get("/fail") {
            throw IllegalArgumentException("bad input")
        }

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val handler = NettyHttpHandler(app, scope, 10_000_000L)

        val request = DefaultHttpRequest(HttpVersion.HTTP_1_1, NettyHttpMethod.GET, "/fail")
        handler.readInbound(ctx, request)
        handler.readInbound(ctx, DefaultLastHttpContent(Unpooled.EMPTY_BUFFER))

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals("bad input", exceptionCaught.get()?.message)
    }

    @Test
    fun `committed middleware skips missing and authenticated HTTP dispatch`() = runTest {
        fun application(finished: CountDownLatch): BoscaApplication {
            val app = createApplication()
            app.install(object : CallMiddleware {
                override suspend fun beforeCall(call: ServerCall) {
                    call.respond(HttpStatusCode.Forbidden, "blocked")
                }

                override suspend fun afterCall(call: ServerCall) {
                    finished.countDown()
                }
            })
            app.installAuth(object : AuthMiddleware {
                override suspend fun authenticate(call: ServerCall, authConfig: AuthConfig?) {
                    error("auth must not run after middleware commits")
                }
            })
            return app
        }

        val missingFinished = CountDownLatch(1)
        val missingApp = application(missingFinished)
        val missingHandler = NettyHttpHandler(
            missingApp,
            CoroutineScope(Dispatchers.Default + SupervisorJob()),
            10_000_000L,
        )
        missingHandler.readInbound(
            createMockCtx(),
            DefaultFullHttpRequest(HttpVersion.HTTP_1_1, NettyHttpMethod.GET, "/missing"),
        )
        assertTrue(missingFinished.await(5, TimeUnit.SECONDS))

        val authFinished = CountDownLatch(1)
        val authApp = application(authFinished)
        authApp.routing {
            authenticate("test") {
                get("/authenticated") { error("handler must not run") }
            }
        }
        val authHandler = NettyHttpHandler(
            authApp,
            CoroutineScope(Dispatchers.Default + SupervisorJob()),
            10_000_000L,
        )
        authHandler.readInbound(
            createMockCtx(),
            DefaultFullHttpRequest(HttpVersion.HTTP_1_1, NettyHttpMethod.GET, "/authenticated"),
        )
        assertTrue(authFinished.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun `payload exception after a committed response preserves that response`() = runTest {
        val ctx = createMockCtx()
        val app = createApplication()
        val finished = CountDownLatch(1)
        app.install(object : CallMiddleware {
            override suspend fun afterCall(call: ServerCall) {
                finished.countDown()
            }
        })
        app.router.get("/committed-payload-error") {
            call.respond(HttpStatusCode.Accepted, "accepted")
            throw RequestBodyTooLargeException(1)
        }
        val handler = NettyHttpHandler(app, CoroutineScope(Dispatchers.Default + SupervisorJob()), 10_000_000L)

        handler.readInbound(
            ctx,
            DefaultFullHttpRequest(HttpVersion.HTTP_1_1, NettyHttpMethod.GET, "/committed-payload-error"),
        )

        assertTrue(finished.await(5, TimeUnit.SECONDS))
        verify {
            ctx.writeAndFlush(match { message ->
                message is DefaultFullHttpResponse && message.status() == HttpResponseStatus.ACCEPTED
            })
        }
    }

    @Test
    fun `status set without a response body is committed by finalization`() = runTest {
        val ctx = createMockCtx()
        val app = createApplication()
        val finished = CountDownLatch(1)
        app.install(object : CallMiddleware {
            override suspend fun afterCall(call: ServerCall) {
                finished.countDown()
            }
        })
        app.router.get("/status-only-finalization") {
            call.response.status(HttpStatusCode.Accepted)
        }
        val handler = NettyHttpHandler(app, CoroutineScope(Dispatchers.Default + SupervisorJob()), 10_000_000L)

        handler.readInbound(
            ctx,
            DefaultFullHttpRequest(HttpVersion.HTTP_1_1, NettyHttpMethod.GET, "/status-only-finalization"),
        )

        assertTrue(finished.await(5, TimeUnit.SECONDS))
        verify {
            ctx.writeAndFlush(match { message ->
                message is DefaultFullHttpResponse && message.status() == HttpResponseStatus.ACCEPTED
            })
        }
    }

    @Test
    fun `middleware failure exception unwind stops after a response is committed`() = runTest {
        val ctx = createMockCtx()
        val app = createApplication()
        val finished = CountDownLatch(1)
        app.install(object : CallMiddleware {
            override suspend fun beforeCall(call: ServerCall) {
                throw IllegalStateException("before failure")
            }

            override suspend fun onException(call: ServerCall, cause: Throwable) {
                error("exception unwind must stop after a response is committed")
            }
        })
        app.install(object : CallMiddleware {
            override suspend fun onException(call: ServerCall, cause: Throwable) {
                call.respond(HttpStatusCode.BadRequest, "handled")
            }

            override suspend fun afterCall(call: ServerCall) {
                finished.countDown()
            }
        })
        app.router.get("/middleware-failure") {
            error("handler must not run")
        }
        val handler = NettyHttpHandler(app, CoroutineScope(Dispatchers.Default + SupervisorJob()), 10_000_000L)

        handler.readInbound(
            ctx,
            DefaultFullHttpRequest(HttpVersion.HTTP_1_1, NettyHttpMethod.GET, "/middleware-failure"),
        )

        assertTrue(finished.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun `multiple middleware run in order`() = runTest {
        val ctx = createMockCtx()
        val app = createApplication()
        val order = java.util.Collections.synchronizedList(mutableListOf<String>())
        val latch = CountDownLatch(1)

        // First-installed middleware is the LAST to run during the
        // reverse-order afterCall unwind, so its afterCall is the
        // canonical "all middleware finished" signal — putting the
        // latch anywhere else would let the assertion run before the
        // earlier-installed middlewares' afterCalls had a chance to
        // append their entries (this used to be racy).
        app.install(object : CallMiddleware {
            override suspend fun beforeCall(call: ServerCall) {
                order.add("first-before")
            }
            override suspend fun afterCall(call: ServerCall) {
                order.add("first-after")
                latch.countDown()
            }
        })

        app.install(object : CallMiddleware {
            override suspend fun beforeCall(call: ServerCall) {
                order.add("second-before")
            }
            override suspend fun afterCall(call: ServerCall) {
                order.add("second-after")
            }
        })

        app.router.get("/test") {
            order.add("handler")
            call.respond(HttpStatusCode.OK, "ok")
        }

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val handler = NettyHttpHandler(app, scope, 10_000_000L)

        val request = DefaultHttpRequest(HttpVersion.HTTP_1_1, NettyHttpMethod.GET, "/test")
        handler.readInbound(ctx, request)
        handler.readInbound(ctx, DefaultLastHttpContent(Unpooled.EMPTY_BUFFER))

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        // beforeCall runs in forward order, afterCall runs in reverse order (stack unwinding)
        assertEquals("first-before", order[0])
        assertEquals("second-before", order[1])
        assertEquals("handler", order[2])
        assertEquals("second-after", order[3])
        assertEquals("first-after", order[4])
    }

    // --- Auth CallMiddleware Tests ---

    @Test
    fun `auth middleware runs for authenticated routes`() = runTest {
        val ctx = createMockCtx()
        val app = createApplication()
        val authCalled = AtomicBoolean(false)
        val latch = CountDownLatch(1)

        app.installAuth(object : AuthMiddleware {
            override suspend fun authenticate(call: ServerCall, authConfig: AuthConfig?) {
                authCalled.set(true)
            }
        })

        app.routing {
            authenticate("bearer") {
                get("/secure") {
                    call.respond(HttpStatusCode.OK, "ok")
                    latch.countDown()
                }
            }
        }

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val handler = NettyHttpHandler(app, scope, 10_000_000L)

        val request = DefaultHttpRequest(HttpVersion.HTTP_1_1, NettyHttpMethod.GET, "/secure")
        handler.readInbound(ctx, request)
        handler.readInbound(ctx, DefaultLastHttpContent(Unpooled.EMPTY_BUFFER))

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertTrue(authCalled.get(), "Auth middleware should run for authenticated routes")
    }

    @Test
    fun `auth middleware does not run for unauthenticated routes`() = runTest {
        val ctx = createMockCtx()
        val app = createApplication()
        val authCalled = AtomicBoolean(false)
        val latch = CountDownLatch(1)

        app.installAuth(object : AuthMiddleware {
            override suspend fun authenticate(call: ServerCall, authConfig: AuthConfig?) {
                authCalled.set(true)
            }
        })

        app.router.get("/public") {
            call.respond(HttpStatusCode.OK, "ok")
            latch.countDown()
        }

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val handler = NettyHttpHandler(app, scope, 10_000_000L)

        val request = DefaultHttpRequest(HttpVersion.HTTP_1_1, NettyHttpMethod.GET, "/public")
        handler.readInbound(ctx, request)
        handler.readInbound(ctx, DefaultLastHttpContent(Unpooled.EMPTY_BUFFER))

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertTrue(!authCalled.get(), "Auth middleware should not run for unauthenticated routes")
    }

    @Test
    fun `auth middleware runs for authenticated SSE routes and can short circuit`() = runTest {
        val ctx = createMockCtx()
        val app = createApplication()
        val authCalls = java.util.concurrent.atomic.AtomicInteger()
        val handled = AtomicBoolean(false)
        val finished = CountDownLatch(1)

        app.installAuth(object : AuthMiddleware {
            override suspend fun authenticate(call: ServerCall, authConfig: AuthConfig?) {
                authCalls.incrementAndGet()
                call.respond(HttpStatusCode.Unauthorized, "denied")
            }
        })
        app.install(object : CallMiddleware {
            override suspend fun afterCall(call: ServerCall) {
                finished.countDown()
            }
        })
        app.routing {
            authenticate("bearer") {
                sse("/secure-events") {
                    handled.set(true)
                }
            }
        }

        val handler = NettyHttpHandler(app, CoroutineScope(Dispatchers.Default + SupervisorJob()), 10_000_000L)
        val request = DefaultHttpRequest(HttpVersion.HTTP_1_1, NettyHttpMethod.GET, "/secure-events")
        request.headers().set(HttpHeaderNames.ACCEPT, "text/event-stream")
        handler.readInbound(ctx, request)
        handler.readInbound(ctx, DefaultLastHttpContent(Unpooled.EMPTY_BUFFER))

        assertTrue(finished.await(5, TimeUnit.SECONDS))
        assertEquals(1, authCalls.get())
        assertFalse(handled.get())
    }

    @Test
    fun `committed SSE middleware skips later middleware auth and handler`() = runTest {
        val ctx = createMockCtx()
        val app = createApplication()
        val handled = AtomicBoolean(false)
        val finished = CountDownLatch(1)

        app.install(object : CallMiddleware {
            override suspend fun beforeCall(call: ServerCall) {
                call.respond(HttpStatusCode.Forbidden, "blocked")
            }

            override suspend fun afterCall(call: ServerCall) {
                finished.countDown()
            }
        })
        app.install(object : CallMiddleware {
            override suspend fun beforeCall(call: ServerCall) {
                error("middleware after a committed response must not run")
            }
        })
        app.installAuth(object : AuthMiddleware {
            override suspend fun authenticate(call: ServerCall, authConfig: AuthConfig?) {
                error("auth must not run after middleware commits")
            }
        })
        app.routing {
            authenticate("bearer") {
                sse("/committed-events") {
                    handled.set(true)
                }
            }
        }

        val handler = NettyHttpHandler(app, CoroutineScope(Dispatchers.Default + SupervisorJob()), 10_000_000L)
        val request = DefaultHttpRequest(HttpVersion.HTTP_1_1, NettyHttpMethod.GET, "/committed-events")
        request.headers().set(HttpHeaderNames.ACCEPT, "text/event-stream")
        handler.readInbound(ctx, request)
        handler.readInbound(ctx, DefaultLastHttpContent(Unpooled.EMPTY_BUFFER))

        assertTrue(finished.await(5, TimeUnit.SECONDS))
        assertFalse(handled.get())
    }

    @Test
    fun `SSE exception unwind stops after middleware commits an error response`() = runTest {
        val ctx = createMockCtx()
        val app = createApplication()
        val finished = CountDownLatch(1)

        app.install(object : CallMiddleware {
            override suspend fun onException(call: ServerCall, cause: Throwable) {
                error("exception unwind must stop after a response is committed")
            }
        })
        app.install(object : CallMiddleware {
            override suspend fun onException(call: ServerCall, cause: Throwable) {
                call.respond(HttpStatusCode.BadRequest, "handled")
            }

            override suspend fun afterCall(call: ServerCall) {
                finished.countDown()
            }
        })
        app.routing {
            sse("/handled-events") {
                throw IllegalStateException("primary")
            }
        }

        val handler = NettyHttpHandler(app, CoroutineScope(Dispatchers.Default + SupervisorJob()), 10_000_000L)
        val request = DefaultHttpRequest(HttpVersion.HTTP_1_1, NettyHttpMethod.GET, "/handled-events")
        request.headers().set(HttpHeaderNames.ACCEPT, "text/event-stream")
        handler.readInbound(ctx, request)
        handler.readInbound(ctx, DefaultLastHttpContent(Unpooled.EMPTY_BUFFER))

        assertTrue(finished.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun `SSE exception middleware failures are contained`() = runTest {
        val ctx = createMockCtx()
        val app = createApplication()
        val exceptionCalls = java.util.concurrent.atomic.AtomicInteger()
        val finished = CountDownLatch(1)
        app.install(object : CallMiddleware {
            override suspend fun onException(call: ServerCall, cause: Throwable) {
                exceptionCalls.incrementAndGet()
                throw IllegalStateException("secondary")
            }

            override suspend fun afterCall(call: ServerCall) {
                finished.countDown()
            }
        })
        app.routing { sse("/broken-events") { throw IllegalStateException("primary") } }

        val handler = NettyHttpHandler(app, CoroutineScope(Dispatchers.Default + SupervisorJob()), 10_000_000L)
        val request = DefaultHttpRequest(HttpVersion.HTTP_1_1, NettyHttpMethod.GET, "/broken-events")
        request.headers().set(HttpHeaderNames.ACCEPT, "text/event-stream")
        handler.readInbound(ctx, request)
        handler.readInbound(ctx, DefaultLastHttpContent(Unpooled.EMPTY_BUFFER))

        assertTrue(finished.await(5, TimeUnit.SECONDS))
        assertEquals(1, exceptionCalls.get())
    }

    // --- hasRequestBody edge cases ---

    @Test
    fun `content-length of 0 does not create body`() = runTest {
        val ctx = createMockCtx()
        val app = createApplication()
        var bodySize = -1
        val latch = CountDownLatch(1)

        app.router.post("/empty") {
            bodySize = call.request.bodyBytes().size
            call.respond(HttpStatusCode.OK, "ok")
            latch.countDown()
        }

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val handler = NettyHttpHandler(app, scope, 10_000_000L)

        val request = DefaultHttpRequest(HttpVersion.HTTP_1_1, NettyHttpMethod.POST, "/empty")
        request.headers().set(HttpHeaderNames.CONTENT_LENGTH, "0")
        handler.readInbound(ctx, request)
        handler.readInbound(ctx, DefaultLastHttpContent(Unpooled.EMPTY_BUFFER))

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals(0, bodySize)
    }

    @Test
    fun `SSE route falls through to HTTP without an event stream accept header`() = runTest {
        val ctx = createMockCtx()
        val app = createApplication()
        val handled = CountDownLatch(2)
        app.routing {
            sse("/dual") { error("SSE handler must not run") }
            get("/dual") {
                call.respond(HttpStatusCode.OK, "http")
                handled.countDown()
            }
        }
        val handler = NettyHttpHandler(app, CoroutineScope(Dispatchers.Default + SupervisorJob()), 10_000_000L)

        handler.readInbound(ctx, DefaultFullHttpRequest(HttpVersion.HTTP_1_1, NettyHttpMethod.GET, "/dual"))
        val incompatible = DefaultFullHttpRequest(HttpVersion.HTTP_1_1, NettyHttpMethod.GET, "/dual")
        incompatible.headers().set(HttpHeaderNames.ACCEPT, "application/json")
        incompatible.headers().set(HttpHeaderNames.UPGRADE, "h2c")
        handler.readInbound(ctx, incompatible)

        assertTrue(handled.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun `HEAD request with event stream accept uses HTTP route without a body`() {
        val ctx = createMockCtx()
        val app = createApplication()
        val routed = CountDownLatch(1)
        val sseHandled = AtomicBoolean(false)
        val writes = mutableListOf<Any>()
        every { ctx.writeAndFlush(capture(writes)) } returns succeededFuture()

        app.routing {
            sse("/dual") {
                sseHandled.set(true)
                send("sse")
                routed.countDown()
            }
            get("/dual") {
                call.respond(HttpStatusCode.OK, "http")
                routed.countDown()
            }
        }
        val handler = NettyHttpHandler(
            app,
            CoroutineScope(Dispatchers.Default + SupervisorJob()),
            10_000_000L,
        )
        val request = DefaultFullHttpRequest(HttpVersion.HTTP_1_1, NettyHttpMethod.HEAD, "/dual")
        request.headers().set(HttpHeaderNames.ACCEPT, "text/event-stream")

        try {
            handler.readInbound(ctx, request)

            assertTrue(routed.await(5, TimeUnit.SECONDS))
            assertFalse(sseHandled.get(), "HEAD must not start the long-lived SSE handler")
            val response = writes.filterIsInstance<DefaultFullHttpResponse>().single()
            assertEquals(HttpResponseStatus.OK, response.status())
            assertEquals("4", response.headers().get(HttpHeaderNames.CONTENT_LENGTH))
            assertFalse(response.content().isReadable, "HEAD must preserve headers without emitting content")
        } finally {
            writes.forEach(io.netty.util.ReferenceCountUtil::safeRelease)
        }
    }

    @Test
    fun `HEAD WebSocket upgrade is rejected without a body`() {
        val ctx = createMockCtx()
        val app = createApplication()
        val routeInvoked = AtomicBoolean(false)
        val writes = mutableListOf<Any>()
        every { ctx.writeAndFlush(capture(writes)) } returns succeededFuture()
        app.routing {
            webSocket("/socket") {
                routeInvoked.set(true)
            }
        }
        val handler = NettyHttpHandler(
            app,
            CoroutineScope(Dispatchers.Default + SupervisorJob()),
            10_000_000L,
        )
        val request = DefaultFullHttpRequest(HttpVersion.HTTP_1_1, NettyHttpMethod.HEAD, "/socket")
        request.headers().set(HttpHeaderNames.UPGRADE, HttpHeaderValues.WEBSOCKET)

        try {
            handler.readInbound(ctx, request)

            assertFalse(routeInvoked.get())
            val response = writes.filterIsInstance<DefaultFullHttpResponse>().single()
            assertEquals(HttpResponseStatus.BAD_REQUEST, response.status())
            assertEquals("15", response.headers().get(HttpHeaderNames.CONTENT_LENGTH))
            assertFalse(response.content().isReadable)
        } finally {
            writes.forEach(io.netty.util.ReferenceCountUtil::safeRelease)
        }
    }

    @Test
    fun `synchronous request inspection failure returns 500`() {
        val ctx = createMockCtx()
        val request = mockk<HttpRequest>(relaxed = true)
        every { request.headers() } returns DefaultHttpHeaders()
        every { request.uri() } throws IllegalStateException("broken request")
        val handler = NettyHttpHandler(
            createApplication(),
            CoroutineScope(Dispatchers.Default + SupervisorJob()),
            10_000_000L,
        )

        handler.readInbound(ctx, request)

        verify(timeout = 2_000) {
            ctx.writeAndFlush(match { message ->
                message is DefaultFullHttpResponse && message.status() == HttpResponseStatus.INTERNAL_SERVER_ERROR
            })
        }
    }

    // --- WebSocket Frame Tests ---

    @Test
    fun `WebSocket frame without session is ignored`() = runTest {
        val ctx = createMockCtx()
        val app = createApplication()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val handler = NettyHttpHandler(app, scope, 10_000_000L)

        // Send a WebSocket frame without having done a handshake first
        val frame = TextWebSocketFrame("hello")
        handler.readInbound(ctx, frame)

        // Should not throw, just return silently
        Thread.sleep(100)
    }

    // --- Response committed without explicit handler response ---

    @Test
    fun `uncommitted response defaults to 200 OK`() = runTest {
        val ctx = createMockCtx()
        val app = createApplication()
        val latch = CountDownLatch(1)

        app.install(object : CallMiddleware {
            override suspend fun afterCall(call: ServerCall) {
                latch.countDown()
            }
        })

        app.router.get("/no-respond") {
            // Handler does not call respond - response should default to 200 OK
        }

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val handler = NettyHttpHandler(app, scope, 10_000_000L)

        val request = DefaultHttpRequest(HttpVersion.HTTP_1_1, NettyHttpMethod.GET, "/no-respond")
        handler.readInbound(ctx, request)
        handler.readInbound(ctx, DefaultLastHttpContent(Unpooled.EMPTY_BUFFER))

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        delay(100)
        verify(atLeast = 1) { ctx.writeAndFlush(any()) }
    }

    @Test
    fun `uncommitted response preserves a status selected by the handler`() = runTest {
        val ctx = createMockCtx()
        val app = createApplication()
        val finished = CountDownLatch(1)
        app.install(object : CallMiddleware {
            override suspend fun afterCall(call: ServerCall) {
                finished.countDown()
            }
        })
        app.router.get("/status-only") {
            call.response.status(HttpStatusCode.Accepted)
        }
        val handler = NettyHttpHandler(app, CoroutineScope(Dispatchers.Default + SupervisorJob()), 10_000_000L)

        handler.readInbound(ctx, DefaultFullHttpRequest(HttpVersion.HTTP_1_1, NettyHttpMethod.GET, "/status-only"))

        assertTrue(finished.await(5, TimeUnit.SECONDS))
        verify(timeout = 5_000) {
            ctx.writeAndFlush(match { message ->
                message is DefaultFullHttpResponse && message.status() == HttpResponseStatus.ACCEPTED
            })
        }
    }

    @Test
    fun `registered path with unsupported method returns 405 and Allow`() = runTest {
        val ctx = createMockCtx()
        val app = createApplication()
        val finished = CountDownLatch(1)
        app.install(object : CallMiddleware {
            override suspend fun afterCall(call: ServerCall) {
                finished.countDown()
            }
        })
        app.router.get("/method-specific") {
            call.respond(HttpStatusCode.OK, "ok")
        }
        val handler = NettyHttpHandler(app, CoroutineScope(Dispatchers.Default + SupervisorJob()), 10_000_000L)

        handler.readInbound(ctx, DefaultFullHttpRequest(HttpVersion.HTTP_1_1, NettyHttpMethod.POST, "/method-specific"))

        assertTrue(finished.await(5, TimeUnit.SECONDS))
        verify(timeout = 5_000) {
            ctx.writeAndFlush(match { message ->
                message is DefaultFullHttpResponse &&
                    message.status() == HttpResponseStatus.METHOD_NOT_ALLOWED &&
                    message.headers().get(HttpHeaderNames.ALLOW) == "GET"
            })
        }
    }

    // --- PUT and PATCH method tests ---

    @Test
    fun `PUT request with body`() = runTest {
        val ctx = createMockCtx()
        val app = createApplication()
        var receivedBody = ""
        val latch = CountDownLatch(1)

        app.router.put("/items/{id}") {
            receivedBody = call.request.bodyText()
            call.respond(HttpStatusCode.OK, "updated")
            latch.countDown()
        }

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val handler = NettyHttpHandler(app, scope, 10_000_000L)

        val request = DefaultHttpRequest(HttpVersion.HTTP_1_1, NettyHttpMethod.PUT, "/items/42")
        request.headers().set(HttpHeaderNames.CONTENT_LENGTH, "11")
        handler.readInbound(ctx, request)
        handler.readInbound(ctx, DefaultLastHttpContent(
            Unpooled.copiedBuffer("update data", StandardCharsets.UTF_8)
        ))

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals("update data", receivedBody)
    }

    @Test
    fun `PATCH request with body`() = runTest {
        val ctx = createMockCtx()
        val app = createApplication()
        var receivedBody = ""
        val latch = CountDownLatch(1)

        app.router.patch("/items/{id}") {
            receivedBody = call.request.bodyText()
            call.respond(HttpStatusCode.OK, "patched")
            latch.countDown()
        }

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val handler = NettyHttpHandler(app, scope, 10_000_000L)

        val request = DefaultHttpRequest(HttpVersion.HTTP_1_1, NettyHttpMethod.PATCH, "/items/42")
        request.headers().set(HttpHeaderNames.CONTENT_LENGTH, "10")
        handler.readInbound(ctx, request)
        handler.readInbound(ctx, DefaultLastHttpContent(
            Unpooled.copiedBuffer("patch data", StandardCharsets.UTF_8)
        ))

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals("patch data", receivedBody)
    }

    // --- Query parameter forwarding ---

    @Test
    fun `query parameters available in handler`() = runTest {
        val ctx = createMockCtx()
        val app = createApplication()
        var page = ""
        var size = ""
        val latch = CountDownLatch(1)

        app.router.get("/search") {
            page = call.request.queryParameters["page"] ?: ""
            size = call.request.queryParameters["size"] ?: ""
            call.respond(HttpStatusCode.OK, "ok")
            latch.countDown()
        }

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val handler = NettyHttpHandler(app, scope, 10_000_000L)

        val request = DefaultHttpRequest(HttpVersion.HTTP_1_1, NettyHttpMethod.GET, "/search?page=2&size=10")
        handler.readInbound(ctx, request)
        handler.readInbound(ctx, DefaultLastHttpContent(Unpooled.EMPTY_BUFFER))

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals("2", page)
        assertEquals("10", size)
    }

    // --- WebSocket upgrade for unregistered path ---

    @Test
    fun `WebSocket upgrade to unregistered path returns 404`() = runTest {
        val ctx = createMockCtx()
        val app = createApplication()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val handler = NettyHttpHandler(app, scope, 10_000_000L)

        val request = DefaultHttpRequest(HttpVersion.HTTP_1_1, NettyHttpMethod.GET, "/ws-not-registered")
        request.headers().set(HttpHeaderNames.UPGRADE, "websocket")
        request.headers().set(HttpHeaderNames.HOST, "localhost:8080")
        handler.readInbound(ctx, request)
        handler.readInbound(ctx, DefaultLastHttpContent(Unpooled.EMPTY_BUFFER))

        verify { ctx.writeAndFlush(match { msg ->
            msg is DefaultFullHttpResponse && msg.status() == HttpResponseStatus.NOT_FOUND
        }) }
    }

    // --- Outer catch handler (lines 185-188 of NettyHttpHandler) ---

    @Test
    fun `middleware exception triggers outer error handler`() = runTest {
        val ctx = createMockCtx()
        val app = createApplication()

        app.install(object : CallMiddleware {
            override suspend fun beforeCall(call: ServerCall) {
                throw RuntimeException("middleware crash")
            }
        })
        app.install(object : CallMiddleware {
            override suspend fun onException(call: ServerCall, cause: Throwable) {
                throw RuntimeException("exception middleware crash")
            }
        })

        app.router.get("/crash") {
            call.respond(HttpStatusCode.OK, "ok")
        }

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val handler = NettyHttpHandler(app, scope, 10_000_000L)

        val request = DefaultHttpRequest(HttpVersion.HTTP_1_1, NettyHttpMethod.GET, "/crash")
        handler.readInbound(ctx, request)
        handler.readInbound(ctx, DefaultLastHttpContent(Unpooled.EMPTY_BUFFER))

        Thread.sleep(300)
        // The outer catch sends a 500 error response
        verify { ctx.writeAndFlush(match { msg ->
            msg is DefaultFullHttpResponse && msg.status() == HttpResponseStatus.INTERNAL_SERVER_ERROR
        }) }
    }

    @Test
    fun `channel lifecycle handles idle sessions cancellation and pending bodies`() {
        val ctx = createMockCtx()
        val app = createApplication()
        val handler = NettyHttpHandler(app, CoroutineScope(Dispatchers.Default + SupervisorJob()), 10_000_000L)

        handler.channelActive(ctx)
        assertNotNull(ctx.channel().attr(ChannelAttributes.SCOPE).get())
        handler.userEventTriggered(ctx, IdleStateEvent.ALL_IDLE_STATE_EVENT)
        verify(atLeast = 1) { ctx.close() }

        val sseKey = ChannelAttributes.SSE_SESSION
        ctx.channel().attr(sseKey).set(mockk(relaxed = true))
        io.mockk.clearMocks(ctx, answers = false, recordedCalls = true, childMocks = false)
        handler.userEventTriggered(ctx, IdleStateEvent.ALL_IDLE_STATE_EVENT)
        verify(exactly = 0) { ctx.close() }
        ctx.channel().attr(sseKey).set(null)

        val request = DefaultHttpRequest(HttpVersion.HTTP_1_1, NettyHttpMethod.POST, "/")
        val body = RequestBody(ctx, 1024)
        body.addContent(DefaultLastHttpContent(Unpooled.copiedBuffer("pending", StandardCharsets.UTF_8)))
        val call = ServerCall(ServerRequest(request, body, null), ServerResponse(ctx), Parameters.Empty, app)
        val session = WebSocketSession(call, ctx.channel(), SupervisorJob())
        val wsKey = io.netty.util.AttributeKey.valueOf<WebSocketSession>("bosca.wsSession")
        val bodyKey = io.netty.util.AttributeKey.valueOf<RequestBody>("bosca.currentBody")
        ctx.channel().attr(wsKey).set(session)
        ctx.channel().attr(bodyKey).set(body)

        handler.userEventTriggered(ctx, IdleStateEvent.ALL_IDLE_STATE_EVENT)
        val channel = ctx.channel()
        verify { channel.writeAndFlush(ofType<CloseWebSocketFrame>(), any()) }

        ctx.channel().attr(wsKey).set(session)
        ctx.channel().attr(bodyKey).set(body)
        handler.exceptionCaught(ctx, CancellationException("cancelled"))
        assertTrue(session.closeReason.isCompleted)

        val secondSession = WebSocketSession(call, ctx.channel(), SupervisorJob())
        ctx.channel().attr(wsKey).set(secondSession)
        ctx.channel().attr(bodyKey).set(RequestBody(ctx, 1024))
        handler.channelInactive(ctx)
        assertTrue(secondSession.closeReason.isCompleted)
        assertNull(ctx.channel().attr(ChannelAttributes.SCOPE).get())

        handler.channelInactive(ctx)

        val ordinaryEvent = Any()
        handler.userEventTriggered(ctx, ordinaryEvent)
        verify { ctx.fireUserEventTriggered(ordinaryEvent) }
    }

    // --- SSE error handling ---

    @Test
    fun `SSE handler error writes fallback response before closing channel`() = runTest {
        val ctx = createMockCtx()
        val channel = ctx.channel()
        val app = createApplication()
        val handlerStarted = CountDownLatch(1)
        val responseSubmitted = AtomicBoolean(false)
        val channelClosed = CountDownLatch(1)
        val closedAfterResponse = AtomicBoolean(false)
        val responseFuture = succeededFuture()
        every { responseFuture.channel() } returns channel
        every {
            ctx.writeAndFlush(match {
                it is DefaultFullHttpResponse && it.status() == HttpResponseStatus.INTERNAL_SERVER_ERROR
            })
        } answers {
            responseSubmitted.set(true)
            responseFuture
        }
        every { channel.close() } answers {
            closedAfterResponse.set(responseSubmitted.get())
            channelClosed.countDown()
            succeededFuture()
        }

        app.routing {
            sse("/events-err") {
                handlerStarted.countDown()
                throw RuntimeException("SSE error")
            }
        }

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val handler = NettyHttpHandler(app, scope, 10_000_000L)

        val request = DefaultHttpRequest(HttpVersion.HTTP_1_1, NettyHttpMethod.GET, "/events-err")
        request.headers().set(HttpHeaderNames.ACCEPT, "text/event-stream")
        handler.readInbound(ctx, request)
        handler.readInbound(ctx, DefaultLastHttpContent(Unpooled.EMPTY_BUFFER))

        assertTrue(handlerStarted.await(5, TimeUnit.SECONDS))
        assertTrue(channelClosed.await(5, TimeUnit.SECONDS))
        assertTrue(closedAfterResponse.get(), "The fallback response must be submitted before the channel closes")
    }

    // --- Full pipeline tests (with HttpContentCompressor and HttpServerKeepAliveHandler) ---

    @Test
    fun `unmatched route returns 404 through full pipeline without corruption`() {
        val app = createApplication()

        // Register a route so the router is not completely empty
        app.router.get("/exists") {
            call.respond(HttpStatusCode.OK, "ok")
        }

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val handler = NettyHttpHandler(app, scope, 10_000_000L)

        // Full pipeline matching the real server setup (minus IdleStateHandler)
        val channel = EmbeddedChannel(
            HttpServerCodec(),
            HttpServerKeepAliveHandler(),
            SelectiveContentCompressor(),
            ChunkedWriteHandler(),
            handler
        )

        val request = DefaultFullHttpRequest(HttpVersion.HTTP_1_1, NettyHttpMethod.GET, "/nonexistent")
        request.headers().set(HttpHeaderNames.HOST, "localhost")
        channel.writeInbound(request)

        // Give the coroutine time to process
        Thread.sleep(500)
        channel.runPendingTasks()
        channel.flushOutbound()

        // The channel should still be active (no pipeline corruption)
        // and exactly one response should have been written
        val response = channel.readOutbound<io.netty.buffer.ByteBuf>()
        assertTrue(response != null, "Response should be written to the channel")

        val responseText = response.toString(StandardCharsets.UTF_8)
        assertTrue(responseText.contains("404"), "Response should contain 404 status")
        response.release()

        channel.close()
    }

    @Test
    fun `matched route returns 200 through full pipeline`() {
        val app = createApplication()
        val latch = CountDownLatch(1)

        app.router.get("/hello") {
            call.respond(HttpStatusCode.OK, "world")
            latch.countDown()
        }

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val handler = NettyHttpHandler(app, scope, 10_000_000L)

        val channel = EmbeddedChannel(
            HttpServerCodec(),
            HttpServerKeepAliveHandler(),
            SelectiveContentCompressor(),
            ChunkedWriteHandler(),
            handler
        )

        val request = DefaultFullHttpRequest(HttpVersion.HTTP_1_1, NettyHttpMethod.GET, "/hello")
        request.headers().set(HttpHeaderNames.HOST, "localhost")
        channel.writeInbound(request)

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Handler should be called")
        channel.runPendingTasks()
        channel.flushOutbound()

        val response = channel.readOutbound<io.netty.buffer.ByteBuf>()
        assertTrue(response != null, "Response should be written to the channel")

        val responseText = response.toString(StandardCharsets.UTF_8)
        assertTrue(responseText.contains("200"), "Response should contain 200 status")
        response.release()

        channel.close()
    }

    @Test
    fun `HTTP and SSE lifecycle callbacks resume after genuine suspension`() {
        fun suspendingApplication(finished: CountDownLatch): BoscaApplication {
            val app = createApplication()
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
            return app
        }

        val httpFinished = CountDownLatch(1)
        val httpApp = suspendingApplication(httpFinished)
        httpApp.routing {
            authenticate("test") {
                post("/suspending") {
                    delay(1)
                    call.respond(HttpStatusCode.OK, "done")
                }
            }
        }
        val httpHandler = NettyHttpHandler(httpApp, CoroutineScope(Dispatchers.Default + SupervisorJob()), 10_000_000L)
        val httpContext = createMockCtx()
        val httpRequest = DefaultHttpRequest(HttpVersion.HTTP_1_1, NettyHttpMethod.POST, "/suspending")
        httpRequest.headers().set(HttpHeaderNames.CONTENT_LENGTH, "1")
        httpHandler.readInbound(httpContext, httpRequest)
        httpHandler.readInbound(httpContext, DefaultLastHttpContent(Unpooled.wrappedBuffer(byteArrayOf(1))))
        assertTrue(httpFinished.await(5, TimeUnit.SECONDS))

        val sseFinished = CountDownLatch(1)
        val sseApp = suspendingApplication(sseFinished)
        sseApp.routing {
            authenticate("test") {
                sse("/suspending-events") {
                    delay(1)
                    send("event")
                }
            }
        }
        val sseHandler = NettyHttpHandler(sseApp, CoroutineScope(Dispatchers.Default + SupervisorJob()), 10_000_000L)
        val sseContext = createMockCtx()
        val sseRequest = DefaultHttpRequest(HttpVersion.HTTP_1_1, NettyHttpMethod.POST, "/suspending-events")
        sseRequest.headers().set(HttpHeaderNames.ACCEPT, "text/event-stream")
        sseRequest.headers().set(HttpHeaderNames.CONTENT_LENGTH, "1")
        sseHandler.readInbound(sseContext, sseRequest)
        sseHandler.readInbound(sseContext, DefaultLastHttpContent(Unpooled.wrappedBuffer(byteArrayOf(1))))
        assertTrue(sseFinished.await(5, TimeUnit.SECONDS))
    }
}
