package bosca.server.websocket

import bosca.server.BoscaApplication
import bosca.server.Parameters
import bosca.server.ServerCall
import bosca.server.ServerRequest
import bosca.server.ServerResponse
import bosca.server.config.ApplicationConfig
import bosca.server.netty.ChannelReadPauseReason
import bosca.server.netty.pauseReads
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.netty.buffer.UnpooledByteBufAllocator
import io.netty.channel.Channel
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelPromise
import io.netty.channel.DefaultChannelPromise
import io.netty.util.AttributeKey
import io.netty.util.DefaultAttributeMap
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame
import io.netty.util.concurrent.GenericFutureListener
import io.netty.util.concurrent.ImmediateEventExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebSocketSessionTest {

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
        // Real attributes: closing looks up the channel's read-pause controller through one.
        val attributes = DefaultAttributeMap()
        every { channel.attr(any<AttributeKey<Any>>()) } answers { attributes.attr(firstArg<AttributeKey<Any>>()) }
        every { channel.isActive } returns active
        every { channel.writeAndFlush(any()) } answers { createSuccessfulFuture() }
        // close() reserves its write with a promise so a session sends at most one close frame.
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

    @Test
    fun `isActive delegates to channel`() {
        val channel = createChannel(active = true)
        val session = WebSocketSession(createCall(), channel, Dispatchers.Default)
        assertTrue(session.isActive)
    }

    @Test
    fun `isActive returns false when channel is inactive`() {
        val channel = createChannel(active = false)
        val session = WebSocketSession(createCall(), channel, Dispatchers.Default)
        assertFalse(session.isActive)
    }

    @Test
    fun `send writes TextWebSocketFrame to channel`() = runTest {
        val channel = createChannel()
        val session = WebSocketSession(createCall(), channel, Dispatchers.Default)

        session.send("hello")

        verify { channel.writeAndFlush(match { it is TextWebSocketFrame }, any()) }
    }

    @Test
    fun `send throws IOException when channel is inactive`() = runTest {
        val channel = createChannel(active = false)
        val session = WebSocketSession(createCall(), channel, Dispatchers.Default)

        assertFailsWith<IOException> {
            session.send("hello")
        }

        verify(exactly = 0) { channel.writeAndFlush(any(), any()) }
    }

    @Test
    fun `send propagates the channel write failure`() = runTest {
        val channel = createChannel()
        val failure = IOException("connection reset")
        every { channel.writeAndFlush(any(), any()) } answers { secondArg<ChannelPromise>().setFailure(failure) }
        val session = WebSocketSession(createCall(), channel, Dispatchers.Default)

        val error = assertFailsWith<IOException> { session.send("hello") }

        assertEquals(failure.message, error.message)
    }

    @Test
    fun `flush delegates to channel`() = runTest {
        val channel = createChannel()
        val session = WebSocketSession(createCall(), channel, Dispatchers.Default)

        session.flush()

        verify { channel.flush() }
    }

    @Test
    fun `close sends CloseWebSocketFrame and completes closeReason`() = runTest {
        val channel = createChannel()
        val session = WebSocketSession(createCall(), channel, Dispatchers.Default)

        session.close(1000, "normal")

        verify { channel.writeAndFlush(match { it is CloseWebSocketFrame }, any()) }
        val reason = session.closeReason.await()
        assertEquals(1000, reason?.code)
        assertEquals("normal", reason?.message)
    }

    @Test
    fun `close with default parameters`() = runTest {
        val channel = createChannel()
        val session = WebSocketSession(createCall(), channel, Dispatchers.Default)

        session.close()

        val reason = session.closeReason.await()
        assertEquals(1000, reason?.code)
        assertEquals("", reason?.message)
    }

    @Test
    fun `close on inactive channel still completes closeReason`() = runTest {
        val channel = createChannel(active = false)
        val session = WebSocketSession(createCall(), channel, Dispatchers.Default)

        session.close(1001, "going away")

        verify(exactly = 0) { channel.writeAndFlush(any(), any()) }
        val reason = session.closeReason.await()
        assertEquals(1001, reason?.code)
    }

    @Test
    fun `close completes when writing the close frame fails`() = runTest {
        val channel = createChannel()
        every { channel.writeAndFlush(any(), any()) } answers {
            secondArg<ChannelPromise>().setFailure(IOException("connection reset"))
        }
        val session = WebSocketSession(createCall(), channel, Dispatchers.Default)

        session.close(1001, "going away")

        assertEquals(1001, session.closeReason.await()?.code)
    }

    @Test
    fun `close after a close was already sent waits for it instead of sending a second frame`() = runTest {
        val channel = createChannel()
        val session = WebSocketSession(createCall(), channel, Dispatchers.Default)
        val alreadySent = DefaultChannelPromise(channel, ImmediateEventExecutor.INSTANCE).setSuccess()
        session.closeWriteFuture.set(alreadySent)

        session.close(1000, "normal")

        verify(exactly = 0) { channel.writeAndFlush(any(), any()) }
    }

    @Test
    fun `a cancelled close never cancels the close write`() = runTest {
        val channel = createChannel()
        val pendingWrite = DefaultChannelPromise(channel, ImmediateEventExecutor.INSTANCE)
        every { channel.newPromise() } returns pendingWrite
        every { channel.writeAndFlush(any(), any()) } answers { secondArg<ChannelPromise>() }
        val session = WebSocketSession(createCall(), channel, Dispatchers.Default)

        val closing = launch { session.close(1000, "normal") }
        testScheduler.runCurrent()
        closing.cancel()
        closing.join()

        assertFalse(pendingWrite.isCancelled, "Abandoning a close frame midway would leave the peer without one")
    }

    @Test
    fun `close reasons are cut to what a close frame can carry without splitting a character`() {
        assertEquals("short", closeFrameReason("short"))
        val long = "é".repeat(100) // 200 bytes in UTF-8
        val cut = closeFrameReason(long)
        assertTrue(cut.toByteArray(Charsets.UTF_8).size <= 123)
        assertEquals("é".repeat(61), cut, "122 bytes: the 62nd character would need bytes 123-124")
    }

    @Test
    fun `close accepts protocol and application code boundaries`() = runTest {
        listOf(1000, 1003, 1007, 1014, 3000, 4999).forEach { code ->
            val session = WebSocketSession(createCall(), createChannel(), Dispatchers.Default)
            session.close(code)
            assertEquals(code, session.closeReason.await()?.code)
        }
    }

    @Test
    fun `close rejects codes outside protocol and application ranges`() = runTest {
        // 1004-1006 and 1015 are reserved by RFC 6455 and must never be sent.
        listOf(999, 1004, 1005, 1006, 1015, 2999, 5000).forEach { code ->
            val session = WebSocketSession(createCall(), createChannel(), Dispatchers.Default)
            assertFailsWith<IllegalArgumentException> { session.close(code) }
        }
    }

    @Test
    fun `send after a close frame went out is refused`() = runTest {
        val channel = createChannel()
        val session = WebSocketSession(createCall(), channel, Dispatchers.Default)
        session.closeWriteFuture.set(DefaultChannelPromise(channel, ImmediateEventExecutor.INSTANCE).setSuccess())

        val failure = assertFailsWith<IOException> { session.send("after close") }

        assertEquals("WebSocket is closing", failure.message)
        verify(exactly = 0) { channel.writeAndFlush(ofType<TextWebSocketFrame>(), any()) }
    }

    @Test
    fun `sending a close resumes reads paused for backpressure so the peer's reply is read`() {
        val channel = EmbeddedChannel()
        try {
            val session = WebSocketSession(createCall(), channel, Dispatchers.Default)
            channel.pauseReads(ChannelReadPauseReason.WEBSOCKET)
            channel.runPendingTasks()
            assertFalse(channel.config().isAutoRead)

            session.writeClose(CloseReason.Codes.NORMAL, "")
            channel.runPendingTasks()

            assertTrue(channel.config().isAutoRead)
        } finally {
            channel.finishAndReleaseAll()
        }
    }

    @Test
    fun `frame consumption resumes reads only below the watermark`() {
        val channel = EmbeddedChannel()
        try {
            val session = WebSocketSession(createCall(), channel, Dispatchers.Default)
            channel.pauseReads(ChannelReadPauseReason.WEBSOCKET)
            channel.runPendingTasks()

            session.pendingFrameCount.set(200)
            session.frameConsumed()
            channel.runPendingTasks()
            assertFalse(channel.config().isAutoRead)

            session.pendingFrameCount.set(1)
            session.frameConsumed()
            channel.runPendingTasks()
            assertTrue(channel.config().isAutoRead)

            session.pendingFrameCount.set(0)
            session.frameConsumed()
            assertEquals(0, session.pendingFrameCount.get())
        } finally {
            channel.finishAndReleaseAll()
        }
    }

    @Test
    fun `frame consumption preserves an already readable uncoordinated channel`() {
        val channel = EmbeddedChannel()
        try {
            val session = WebSocketSession(createCall(), channel, Dispatchers.Default)
            session.pendingFrameCount.set(1)

            session.frameConsumed()

            assertTrue(channel.config().isAutoRead)
        } finally {
            channel.finishAndReleaseAll()
        }
    }

    @Test
    fun `incoming channel receives sent frames`() = runTest {
        val channel = createChannel()
        val session = WebSocketSession(createCall(), channel, Dispatchers.Default)

        session._incoming.send(WebSocketFrame.Text("msg1"))
        session._incoming.send(WebSocketFrame.Text("msg2"))

        val frame1 = session.incoming.receive()
        val frame2 = session.incoming.receive()
        assertTrue(frame1 is WebSocketFrame.Text)
        assertEquals("msg1", frame1.readText())
        assertTrue(frame2 is WebSocketFrame.Text)
        assertEquals("msg2", frame2.text)
    }

    @Test
    fun `incoming channel receives close frame`() = runTest {
        val channel = createChannel()
        val session = WebSocketSession(createCall(), channel, Dispatchers.Default)

        session._incoming.send(WebSocketFrame.Close(1000, "bye"))

        val frame = session.incoming.receive()
        assertTrue(frame is WebSocketFrame.Close)
        assertEquals(1000, frame.code)
        assertEquals("bye", frame.reason)
    }

    @Test
    fun `closeReason starts incomplete`() {
        val channel = createChannel()
        val session = WebSocketSession(createCall(), channel, Dispatchers.Default)

        assertFalse(session.closeReason.isCompleted)
    }

    // --- WebSocketFrame tests ---

    @Test
    fun `Text frame readText returns text`() {
        val frame = WebSocketFrame.Text("hello")
        assertEquals("hello", frame.readText())
        assertEquals("hello", frame.text)
    }

    @Test
    fun `Binary frame holds data`() {
        val data = byteArrayOf(1, 2, 3)
        val frame = WebSocketFrame.Binary(data)
        assertEquals(3, frame.data.size)
        assertEquals(frame, frame)
        assertFalse(frame.equals("binary"))
    }

    @Test
    fun `Ping frame holds data`() {
        val frame = WebSocketFrame.Ping(byteArrayOf(0))
        assertEquals(1, frame.data.size)
        assertEquals(frame, frame)
        assertFalse(frame.equals("ping"))
    }

    @Test
    fun `Pong frame holds data`() {
        val frame = WebSocketFrame.Pong(byteArrayOf(0, 1))
        assertEquals(2, frame.data.size)
        assertEquals(frame, frame)
        assertFalse(frame.equals("pong"))
    }

    // --- CloseReason tests ---

    @Test
    fun `CloseReason holds code and message`() {
        val reason = CloseReason(1000, "normal closure")
        assertEquals(1000, reason.code)
        assertEquals("normal closure", reason.message)
    }

    @Test
    fun `CloseReason codes are correct`() {
        assertEquals(1000, CloseReason.Codes.NORMAL)
        assertEquals(1001, CloseReason.Codes.GOING_AWAY)
        assertEquals(1002, CloseReason.Codes.PROTOCOL_ERROR)
    }
}
