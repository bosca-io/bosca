package bosca.server.sse

import bosca.server.BoscaApplication
import bosca.server.ContentType
import bosca.server.HttpStatusCode
import bosca.server.Parameters
import bosca.server.ServerCall
import bosca.server.ServerRequest
import bosca.server.ServerResponse
import bosca.server.config.ApplicationConfig
import bosca.server.middleware.CallMiddleware
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.netty.buffer.UnpooledByteBufAllocator
import io.netty.channel.Channel
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.DefaultHttpContent
import io.netty.handler.codec.http.DefaultHttpResponse
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.LastHttpContent
import io.netty.util.concurrent.GenericFutureListener
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServerSSESessionTest {

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

    private fun failedFuture(cause: Throwable? = null): ChannelFuture {
        val future = mockk<ChannelFuture>(relaxed = true)
        every { future.isSuccess } returns false
        every { future.cause() } returns cause
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
        every { ctx.channel() } returns channel
        every { channel.isActive } returns true
        every { ctx.alloc() } returns UnpooledByteBufAllocator.DEFAULT
        every { ctx.writeAndFlush(any()) } returns succeededFuture()
        every { ctx.write(any()) } returns succeededFuture()
        return ctx
    }

    private fun createSession(
        ctx: ChannelHandlerContext,
        configureApplication: (BoscaApplication) -> Unit = {},
    ): ServerSSESession {
        val request = mockk<ServerRequest>(relaxed = true)
        val response = ServerResponse(ctx)
        val config = mockk<ApplicationConfig>(relaxed = true)
        every { config.propertyOrNull(any()) } returns null
        val app = BoscaApplication(config)
        configureApplication(app)
        val call = ServerCall(request, response, Parameters.Empty, app)
        return ServerSSESession(call, ctx)
    }

    @Test
    fun `first send writes SSE headers`() = runTest {
        val ctx = createMockCtx()
        val written = mutableListOf<Any>()
        every { ctx.writeAndFlush(capture(written)) } answers { succeededFuture() }

        val session = createSession(ctx)
        session.send("hello")

        // First write should be headers
        val headers = written[0]
        assertTrue(headers is DefaultHttpResponse)
        assertEquals(HttpResponseStatus.OK, (headers as DefaultHttpResponse).status())
        assertEquals(
            ContentType.Text.EventStream.toString(),
            headers.headers().get(HttpHeaderNames.CONTENT_TYPE)
        )
        assertEquals("no-cache", headers.headers().get(HttpHeaderNames.CACHE_CONTROL))
        assertEquals("keep-alive", headers.headers().get(HttpHeaderNames.CONNECTION))
        assertEquals("chunked", headers.headers().get(HttpHeaderNames.TRANSFER_ENCODING))
    }

    @Test
    fun `send formats data-only event correctly`() = runTest {
        val ctx = createMockCtx()
        val written = mutableListOf<Any>()
        every { ctx.writeAndFlush(capture(written)) } answers { succeededFuture() }

        val session = createSession(ctx)
        session.send("hello world")

        // Second write is the event data
        val dataContent = written[1] as DefaultHttpContent
        val text = dataContent.content().toString(StandardCharsets.UTF_8)
        assertEquals("data: hello world\n\n", text)
    }

    @Test
    fun `send formats empty data as an empty SSE data field`() = runTest {
        val ctx = createMockCtx()
        val written = mutableListOf<Any>()
        every { ctx.writeAndFlush(capture(written)) } answers { succeededFuture() }

        createSession(ctx).send("")

        val dataContent = written[1] as DefaultHttpContent
        assertEquals("data:\n\n", dataContent.content().toString(StandardCharsets.UTF_8))
    }

    @Test
    fun `send reports a failed write without a channel cause`() = runTest {
        val ctx = createMockCtx()
        var write = 0
        every { ctx.writeAndFlush(any()) } answers {
            if (write++ == 0) succeededFuture() else failedFuture()
        }

        val error = assertFailsWith<IOException> { createSession(ctx).send("hello") }

        assertEquals("SSE write failed", error.message)
    }

    @Test
    fun `send formats event with type and id`() = runTest {
        val ctx = createMockCtx()
        val written = mutableListOf<Any>()
        every { ctx.writeAndFlush(capture(written)) } answers { succeededFuture() }

        val session = createSession(ctx)
        session.send(data = "payload", event = "message", id = "42")

        val dataContent = written[1] as DefaultHttpContent
        val text = dataContent.content().toString(StandardCharsets.UTF_8)
        assertTrue(text.contains("event: message\n"))
        assertTrue(text.contains("id: 42\n"))
        assertTrue(text.contains("data: payload\n"))
        assertTrue(text.endsWith("\n"))
    }

    @Test
    fun `send formats multiline data correctly`() = runTest {
        val ctx = createMockCtx()
        val written = mutableListOf<Any>()
        every { ctx.writeAndFlush(capture(written)) } answers { succeededFuture() }

        val session = createSession(ctx)
        session.send("line1\nline2\nline3")

        val dataContent = written[1] as DefaultHttpContent
        val text = dataContent.content().toString(StandardCharsets.UTF_8)
        assertTrue(text.contains("data: line1\n"))
        assertTrue(text.contains("data: line2\n"))
        assertTrue(text.contains("data: line3\n"))
    }

    @Test
    fun `send with event type only`() = runTest {
        val ctx = createMockCtx()
        val written = mutableListOf<Any>()
        every { ctx.writeAndFlush(capture(written)) } answers { succeededFuture() }

        val session = createSession(ctx)
        session.send(data = "test", event = "custom")

        val dataContent = written[1] as DefaultHttpContent
        val text = dataContent.content().toString(StandardCharsets.UTF_8)
        assertEquals("event: custom\ndata: test\n\n", text)
    }

    @Test
    fun `send with id only`() = runTest {
        val ctx = createMockCtx()
        val written = mutableListOf<Any>()
        every { ctx.writeAndFlush(capture(written)) } answers { succeededFuture() }

        val session = createSession(ctx)
        session.send(data = "test", id = "7")

        val dataContent = written[1] as DefaultHttpContent
        val text = dataContent.content().toString(StandardCharsets.UTF_8)
        assertEquals("id: 7\ndata: test\n\n", text)
    }

    @Test
    fun `headers sent only once for multiple sends`() = runTest {
        val ctx = createMockCtx()
        val written = mutableListOf<Any>()
        every { ctx.writeAndFlush(capture(written)) } answers { succeededFuture() }

        val session = createSession(ctx)
        session.send("first")
        session.send("second")
        session.send("third")

        // Only 1 header response + 3 data chunks
        val headerCount = written.count { it is DefaultHttpResponse }
        val dataCount = written.count { it is DefaultHttpContent }
        assertEquals(1, headerCount)
        assertEquals(3, dataCount)
    }

    @Test
    fun `failed before-write callback does not leave an SSE stream committed without headers`() = runTest {
        val ctx = createMockCtx()
        val written = mutableListOf<Any>()
        every { ctx.writeAndFlush(capture(written)) } answers { succeededFuture() }
        val session = createSession(ctx) { application ->
            application.install(object : CallMiddleware {
                override fun onBeforeWrite(call: ServerCall) {
                    error("expected before-write failure")
                }
            })
        }

        assertFailsWith<IllegalStateException> { session.send("first") }

        assertFalse(session.call.response.isCommitted)
        session.send("recovered")
        assertTrue(session.call.response.isCommitted)
        assertEquals(1, written.count { it is DefaultHttpResponse })
        assertEquals(1, written.count { it is DefaultHttpContent })
    }

    @Test
    fun `send ServerSentEvent object delegates to send`() = runTest {
        val ctx = createMockCtx()
        val written = mutableListOf<Any>()
        every { ctx.writeAndFlush(capture(written)) } answers { succeededFuture() }

        val session = createSession(ctx)
        session.send(ServerSentEvent(data = "payload", event = "update", id = "1"))

        val dataContent = written[1] as DefaultHttpContent
        val text = dataContent.content().toString(StandardCharsets.UTF_8)
        assertTrue(text.contains("event: update\n"))
        assertTrue(text.contains("id: 1\n"))
        assertTrue(text.contains("data: payload\n"))
    }

    @Test
    fun `close closes the channel context`() {
        val ctx = createMockCtx()
        val request = mockk<ServerRequest>(relaxed = true)
        val response = ServerResponse(ctx)
        val config = mockk<ApplicationConfig>(relaxed = true)
        every { config.propertyOrNull(any()) } returns null
        val app = BoscaApplication(config)
        val call = ServerCall(request, response, Parameters.Empty, app)
        val session = ServerSSESession(call, ctx)

        kotlinx.coroutines.runBlocking {
            session.close()
        }

        verify { ctx.close() }
    }

    @Test
    fun `close waits for a committed fallback response write before closing the channel`() = runTest {
        val ctx = createMockCtx()
        val channel = ctx.channel()
        val responseWrite = mockk<ChannelFuture>(relaxed = true)
        val closeListener = slot<GenericFutureListener<ChannelFuture>>()
        every { responseWrite.channel() } returns channel
        every { responseWrite.addListener(capture(closeListener)) } returns responseWrite
        every { ctx.writeAndFlush(any()) } returns responseWrite
        val session = createSession(ctx)

        session.call.response.respondText(
            "Internal Server Error",
            ContentType.Text.Plain,
            HttpStatusCode.InternalServerError,
        )
        session.close()

        verify(exactly = 0) { ctx.close() }
        verify(exactly = 0) { channel.close() }

        closeListener.captured.operationComplete(responseWrite)

        verify(exactly = 1) { channel.close() }
    }

    @Test
    fun `close terminates a started stream only once`() = runTest {
        val ctx = createMockCtx()
        val session = createSession(ctx)
        session.send("started")

        session.close()
        session.close()

        verify(exactly = 1) { ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT) }
        verify(exactly = 1) { ctx.close() }
    }

    @Test
    fun `close gives up on a final write the client never reads and still closes the stream`() = runTest {
        val ctx = createMockCtx()
        val session = createSession(ctx)
        session.send("started")
        // A client that stopped reading: the final chunk's write never completes.
        every { ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT) } returns mockk(relaxed = true)

        session.close()

        assertEquals(30_000, testScheduler.currentTime, "Bounded by the write timeout")
        verify(exactly = 1) { ctx.close() }
    }

    // --- ServerSentEvent data class tests ---

    @Test
    fun `ServerSentEvent with all fields`() {
        val event = ServerSentEvent(data = "d", event = "e", id = "i")
        assertEquals("d", event.data)
        assertEquals("e", event.event)
        assertEquals("i", event.id)
    }

    @Test
    fun `ServerSentEvent with defaults`() {
        val event = ServerSentEvent(data = "d")
        assertEquals("d", event.data)
        assertEquals(null, event.event)
        assertEquals(null, event.id)
    }
}
