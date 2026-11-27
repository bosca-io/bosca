package bosca.server.netty

import bosca.server.BoscaApplication
import bosca.server.ServerCall
import bosca.server.config.ApplicationConfig
import bosca.server.middleware.CallMiddleware
import io.mockk.every
import io.mockk.mockk
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelPromise
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.codec.http.DefaultFullHttpRequest
import io.netty.handler.codec.http.FullHttpResponse
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpHeaderValues
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpServerCodec
import io.netty.handler.codec.http.HttpVersion
import io.netty.util.ReferenceCountUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class WebSocketUpgradeLifecycleTest {

    @Test
    fun `failed suspended admission finishes cleanup and leaves HTTP reusable`() {
        val admission = CompletableDeferred<Unit>()
        val failureObserved = CompletableDeferred<Throwable>()
        val finishEntered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val error = IllegalStateException("authorization service failed")
        var routeRan = false
        val app = application()
        app.install(object : CallMiddleware {
            override suspend fun beforeCall(call: ServerCall) {
                if (call.request.uri == "/ws") {
                    admission.await()
                    throw error
                }
            }

            override suspend fun onException(call: ServerCall, cause: Throwable) {
                failureObserved.complete(cause)
            }

            override suspend fun afterCall(call: ServerCall) {
                if (call.request.uri == "/ws") {
                    finishEntered.complete(Unit)
                    finish.await()
                }
            }
        })
        app.routing {
            webSocket("/ws") { routeRan = true }
            get("/ready") { call.response.respondText("ready") }
        }
        withChannel(app, releases = listOf(admission, finish)) { channel ->
            channel.writeInbound(upgrade())
            assertTrue(channel.outboundMessages().isEmpty())
            admission.complete(Unit)
            channel.runPendingTasks()

            assertSame(error, failureObserved.getCompleted())
            assertTrue(finishEntered.isCompleted)
            assertTrue(channel.drainText().startsWith("HTTP/1.1 500"))
            assertFalse(routeRan)
            assertNull(channel.attr(ChannelAttributes.WS_SESSION).get())

            finish.complete(Unit)
            channel.runPendingTasks()
            channel.writeInbound(DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/ready"))
            val response = channel.drainText()
            assertTrue(response.startsWith("HTTP/1.1 200"), response)
            assertTrue(response.endsWith("ready"), response)
            assertTrue(channel.isActive)
        }
    }

    @Test
    fun `failed suspended route waits for exception and cleanup middleware before closing`() {
        for (failure in listOf(IllegalStateException("route failed"), CancellationException("route deadline"))) {
            val route = CompletableDeferred<Unit>()
            val unwind = CompletableDeferred<Unit>()
            val cleanup = CompletableDeferred<Unit>()
            val observed = CompletableDeferred<Throwable>()
            val cleanupEntered = CompletableDeferred<Unit>()
            val app = application()
            app.install(object : CallMiddleware {
                override suspend fun onException(call: ServerCall, cause: Throwable) {
                    observed.complete(cause)
                    unwind.await()
                }

                override suspend fun afterCall(call: ServerCall) {
                    cleanupEntered.complete(Unit)
                    cleanup.await()
                }
            })
            app.routing { webSocket("/ws") { route.await(); throw failure } }
            withChannel(app, releases = listOf(route, unwind, cleanup)) { channel ->
                channel.writeInbound(upgrade())
                assertTrue(channel.drainText().startsWith("HTTP/1.1 101"))
                val session = checkNotNull(channel.attr(ChannelAttributes.WS_SESSION).get())

                route.complete(Unit)
                channel.runPendingTasks()
                assertSame(failure, observed.getCompleted())
                assertTrue(channel.outboundMessages().isEmpty())
                assertTrue(channel.isActive)

                unwind.complete(Unit)
                channel.runPendingTasks()
                assertTrue(cleanupEntered.isCompleted)
                assertTrue(channel.outboundMessages().isEmpty())

                cleanup.complete(Unit)
                channel.runPendingTasks()
                assertEquals(1011, channel.closeCode())
                assertEquals(1011, session.closeReason.getCompleted()?.code)
                assertFalse(channel.isActive)
            }
        }
    }

    @Test
    fun `cancelled suspended admission closes without accepting and still releases resources`() {
        val admission = CompletableDeferred<Unit>()
        val cleanupEntered = CompletableDeferred<Unit>()
        val cleanup = CompletableDeferred<Unit>()
        var cleaned = false
        var routeRan = false
        val app = application()
        app.install(object : CallMiddleware {
            override suspend fun beforeCall(call: ServerCall) {
                admission.await()
                throw CancellationException("request cancelled before handshake")
            }

            override suspend fun afterCall(call: ServerCall) {
                cleanupEntered.complete(Unit)
                cleanup.await()
                cleaned = true
            }
        })
        app.routing { webSocket("/ws") { routeRan = true } }
        withChannel(app, releases = listOf(admission, cleanup)) { channel ->
            channel.writeInbound(upgrade())
            admission.complete(Unit)
            channel.runPendingTasks()
            assertFalse(channel.isActive)
            assertTrue(cleanupEntered.isCompleted)
            assertFalse(cleaned)
            assertFalse(routeRan)
            assertNull(channel.attr(ChannelAttributes.WS_SESSION).get())
            assertTrue(channel.outboundMessages().isEmpty())

            cleanup.complete(Unit)
            channel.runPendingTasks()
            assertTrue(cleaned)
        }
    }

    @Test
    fun `disconnect during suspended admission never accepts and completes suspended cleanup`() {
        val admission = CompletableDeferred<Unit>()
        val cleanupEntered = CompletableDeferred<Unit>()
        val cleanup = CompletableDeferred<Unit>()
        var cleaned = false
        var routeRan = false
        val app = application()
        app.install(object : CallMiddleware {
            override suspend fun beforeCall(call: ServerCall) { admission.await() }
            override suspend fun afterCall(call: ServerCall) {
                cleanupEntered.complete(Unit)
                cleanup.await()
                cleaned = true
            }
        })
        app.routing { webSocket("/ws") { routeRan = true } }
        withChannel(app, releases = listOf(admission, cleanup)) { channel ->
            channel.writeInbound(upgrade())
            channel.close()
            channel.runPendingTasks()
            assertTrue(cleanupEntered.isCompleted)
            cleanup.complete(Unit)
            channel.runPendingTasks()
            admission.complete(Unit)
            channel.runPendingTasks()

            assertTrue(cleaned)
            assertFalse(routeRan)
            assertNull(channel.attr(ChannelAttributes.WS_SESSION).get())
            assertTrue(channel.outboundMessages().isEmpty())
        }
    }

    @Test
    fun `failed handshake write abandons the unaccepted session after suspended cleanup`() {
        val app = application()
        var routeRan = false
        val cleanupEntered = CompletableDeferred<Unit>()
        val cleanup = CompletableDeferred<Unit>()
        app.install(object : CallMiddleware {
            override suspend fun afterCall(call: ServerCall) {
                cleanupEntered.complete(Unit)
                cleanup.await()
            }
        })
        app.routing { webSocket("/ws") { routeRan = true } }
        val handshake = HandshakeWriteGate()
        withChannel(app, handshake, listOf(cleanup)) { channel ->
            channel.writeInbound(upgrade())
            val session = checkNotNull(channel.attr(ChannelAttributes.WS_SESSION).get())
            assertTrue(handshake.isPending)
            handshake.fail()
            channel.runPendingTasks()
            assertTrue(cleanupEntered.isCompleted)
            assertFalse(routeRan)

            cleanup.complete(Unit)
            channel.runPendingTasks()
            assertFalse(channel.isActive)
            assertTrue(session.coroutineContext.job.isCancelled)
            assertTrue(session.closeReason.isCompleted)
            assertNull(session.closeReason.getCompleted())
            assertNull(channel.attr(ChannelAttributes.WS_SESSION).get())
        }
    }

    private class HandshakeWriteGate : ChannelOutboundHandlerAdapter() {
        private var message: FullHttpResponse? = null
        private var pending: ChannelPromise? = null
        val isPending: Boolean get() = pending != null

        override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
            if (msg is FullHttpResponse && msg.status() == HttpResponseStatus.SWITCHING_PROTOCOLS) {
                check(pending == null)
                message = msg
                pending = promise
            } else {
                ctx.write(msg, promise)
            }
        }

        fun fail() {
            ReferenceCountUtil.release(message)
            message = null
            checkNotNull(pending).tryFailure(IllegalStateException("handshake write failed"))
            pending = null
        }
    }

    private fun application(): BoscaApplication {
        val config = mockk<ApplicationConfig>(relaxed = true)
        every { config.propertyOrNull(any()) } returns null
        return BoscaApplication(config)
    }

    private fun withChannel(
        app: BoscaApplication,
        outbound: ChannelOutboundHandlerAdapter = ChannelOutboundHandlerAdapter(),
        releases: List<CompletableDeferred<Unit>>,
        test: (EmbeddedChannel) -> Unit,
    ) {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val channel = EmbeddedChannel(HttpServerCodec(), outbound,
            NettyHttpHandler(app, scope, 10_000_000L, RequestDispatcherMode.EVENT_LOOP))
        try {
            test(channel)
        } finally {
            releases.forEach { it.complete(Unit) }
            if (outbound is HandshakeWriteGate && outbound.isPending) outbound.fail()
            scope.cancel()
            channel.finishAndReleaseAll()
        }
    }

    private fun upgrade() = DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/ws").also {
        it.headers().set(HttpHeaderNames.HOST, "localhost")
        it.headers().set(HttpHeaderNames.UPGRADE, HttpHeaderValues.WEBSOCKET)
        it.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.UPGRADE)
        it.headers().set(HttpHeaderNames.SEC_WEBSOCKET_KEY, "dGhlIHNhbXBsZSBub25jZQ==")
        it.headers().set(HttpHeaderNames.SEC_WEBSOCKET_VERSION, "13")
    }

    private fun EmbeddedChannel.drainText(): String = generateSequence { readOutbound<ByteBuf>() }
        .joinToString("") { buffer -> buffer.toString(Charsets.UTF_8).also { buffer.release() } }

    private fun EmbeddedChannel.closeCode(): Int {
        val frame = checkNotNull(readOutbound<ByteBuf>())
        return try {
            assertEquals(0x88, frame.getUnsignedByte(frame.readerIndex()).toInt())
            frame.getUnsignedShort(frame.readerIndex() + 2)
        } finally {
            frame.release()
        }
    }
}
