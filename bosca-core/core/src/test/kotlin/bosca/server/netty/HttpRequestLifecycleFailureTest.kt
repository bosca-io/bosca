package bosca.server.netty

import bosca.server.BoscaApplication
import bosca.server.ServerCall
import bosca.server.config.ApplicationConfig
import bosca.server.middleware.CallMiddleware
import io.mockk.every
import io.mockk.mockk
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.codec.http.DefaultFullHttpRequest
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpServerCodec
import io.netty.handler.codec.http.HttpVersion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HttpRequestLifecycleFailureTest {

    @Test
    fun `disconnect during error middleware cancels the pending answer and releases unread upload data`() {
        for (failure in listOf(IllegalStateException("handler failed"), CancellationException("handler deadline"))) {
            val unwind = CompletableDeferred<Unit>()
            val entered = CompletableDeferred<Unit>()
            var cleaned = false
            val app = application()
            app.install(object : CallMiddleware {
                override suspend fun onException(call: ServerCall, cause: Throwable) {
                    entered.complete(Unit)
                    unwind.await()
                }

                override suspend fun afterCall(call: ServerCall) { cleaned = true }
            })
            app.routing { get("/request") { throw failure } }
            withChannel(app, listOf(unwind)) { channel ->
                val request = request(false)
                val buffer = request.content()
                channel.writeInbound(request)
                assertTrue(entered.isCompleted)
                assertFalse(cleaned)

                channel.close()
                channel.runPendingTasks()

                assertTrue(cleaned)
                assertEquals(0, buffer.refCnt())
                assertFalse(channel.isActive)
                assertTrue(channel.outboundMessages().isEmpty(), "A disconnected call cannot be answered")
            }
        }
    }

    @Test
    fun `HTTP and SSE admission failures release unread bodies after suspended exception and cleanup middleware`() {
        for (sse in listOf(false, true)) {
            val admission = CompletableDeferred<Unit>()
            val unwind = CompletableDeferred<Unit>()
            val cleanup = CompletableDeferred<Unit>()
            val failureObserved = CompletableDeferred<Throwable>()
            val cleanupEntered = CompletableDeferred<Unit>()
            val app = application()
            var routeRan = false
            app.install(object : CallMiddleware {
                override suspend fun beforeCall(call: ServerCall) {
                    admission.await()
                    error("admission unavailable")
                }

                override suspend fun onException(call: ServerCall, cause: Throwable) {
                    failureObserved.complete(cause)
                    unwind.await()
                }

                override suspend fun afterCall(call: ServerCall) {
                    cleanupEntered.complete(Unit)
                    cleanup.await()
                }
            })
            app.routing {
                get("/request") { routeRan = true }
                sse("/request") { routeRan = true }
            }
            withChannel(app, listOf(admission, unwind, cleanup)) { channel ->
                val request = request(sse)
                val buffer = request.content()
                channel.writeInbound(request)
                assertEquals(1, buffer.refCnt(), "The body is retained while admission is suspended")
                admission.complete(Unit)
                channel.runPendingTasks()
                assertEquals("admission unavailable", failureObserved.getCompleted().message)
                assertTrue(channel.outboundMessages().isEmpty())

                unwind.complete(Unit)
                channel.runPendingTasks()
                assertTrue(cleanupEntered.isCompleted)
                assertTrue(channel.drainText().startsWith("HTTP/1.1 500"))

                cleanup.complete(Unit)
                channel.runPendingTasks()
                assertFalse(routeRan)
                assertEquals(0, buffer.refCnt())
                assertNull(channel.attr(ChannelAttributes.SSE_SESSION).get())
                assertEquals(!sse, channel.isActive)
            }
        }
    }

    @Test
    fun `cancelled exception middleware closes unanswered HTTP and preserves an already sent response`() {
        for (respondFirst in listOf(false, true)) {
            val route = CompletableDeferred<Unit>()
            val unwind = CompletableDeferred<Unit>()
            var cleaned = false
            val app = application()
            app.install(object : CallMiddleware {
                override suspend fun onException(call: ServerCall, cause: Throwable) {
                    unwind.await()
                    throw CancellationException("exception middleware deadline")
                }

                override suspend fun afterCall(call: ServerCall) { cleaned = true }
            })
            app.routing {
                get("/request") {
                    if (respondFirst) call.response.respondText("already sent")
                    route.await()
                    error("handler failure")
                }
                get("/next") { call.response.respondText("next response") }
            }
            withChannel(app, listOf(route, unwind)) { channel ->
                val request = request(false)
                val buffer = request.content()
                channel.writeInbound(request)
                val response = channel.drainText()
                if (respondFirst) {
                    assertTrue(response.startsWith("HTTP/1.1 200"), response)
                    assertTrue(response.endsWith("already sent"), response)
                } else {
                    assertTrue(response.isEmpty())
                }
                route.complete(Unit)
                channel.runPendingTasks()
                assertFalse(cleaned)
                unwind.complete(Unit)
                channel.runPendingTasks()

                assertTrue(cleaned)
                assertEquals(0, buffer.refCnt())
                assertEquals(respondFirst, channel.isActive)
                assertTrue(channel.outboundMessages().isEmpty(), "Cancellation must not emit a second or default response")
                if (respondFirst) {
                    channel.writeInbound(DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/next"))
                    assertTrue(channel.drainText().endsWith("next response"))
                }
            }
        }
    }

    @Test
    fun `SSE route cancellation still unwinds suspended middleware and releases its unread body`() {
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
        app.routing {
            sse("/request") {
                send("started")
                route.await()
                throw CancellationException("event source cancelled")
            }
        }
        withChannel(app, listOf(route, unwind, cleanup)) { channel ->
            val request = request(true)
            val buffer = request.content()
            channel.writeInbound(request)
            assertTrue(channel.drainText().contains("data: started"))
            route.complete(Unit)
            channel.runPendingTasks()
            assertEquals("event source cancelled", observed.getCompleted().message)
            assertTrue(channel.isActive)

            unwind.complete(Unit)
            channel.runPendingTasks()
            assertTrue(cleanupEntered.isCompleted)
            cleanup.complete(Unit)
            channel.runPendingTasks()
            assertEquals(0, buffer.refCnt())
            assertNull(channel.attr(ChannelAttributes.SSE_SESSION).get())
            assertFalse(channel.isActive)
        }
    }

    private fun application(): BoscaApplication {
        val config = mockk<ApplicationConfig>(relaxed = true)
        every { config.propertyOrNull(any()) } returns null
        return BoscaApplication(config)
    }

    private fun request(sse: Boolean) = DefaultFullHttpRequest(
        HttpVersion.HTTP_1_1, HttpMethod.GET, "/request", Unpooled.wrappedBuffer(byteArrayOf(1, 2, 3)),
    ).also {
        it.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, 3)
        if (sse) it.headers().set(HttpHeaderNames.ACCEPT, "text/event-stream")
    }

    private fun withChannel(
        app: BoscaApplication,
        releases: List<CompletableDeferred<Unit>>,
        test: (EmbeddedChannel) -> Unit,
    ) {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val channel = EmbeddedChannel(HttpServerCodec(),
            NettyHttpHandler(app, scope, 1024L, RequestDispatcherMode.EVENT_LOOP))
        try {
            test(channel)
        } finally {
            releases.forEach { it.complete(Unit) }
            scope.cancel()
            channel.finishAndReleaseAll()
        }
    }

    private fun EmbeddedChannel.drainText(): String = generateSequence { readOutbound<ByteBuf>() }
        .joinToString("") { buffer -> buffer.toString(Charsets.UTF_8).also { buffer.release() } }
}
