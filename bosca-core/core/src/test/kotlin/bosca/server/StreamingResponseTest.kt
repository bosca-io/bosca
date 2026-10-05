package bosca.server

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufAllocator
import io.netty.buffer.Unpooled
import io.netty.buffer.UnpooledByteBufAllocator
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.WriteBufferWaterMark
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.codec.http.HttpContent
import io.netty.handler.codec.http.LastHttpContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class StreamingResponseTest {

    private val channel = EmbeddedChannel(object : ChannelInboundHandlerAdapter() {})
    private val stream = StreamingResponse(channel.pipeline().firstContext())

    @AfterTest
    fun tearDown() {
        channel.finishAndReleaseAll()
    }

    /** Reads every message the stream handed to Netty, releasing each after copying its bytes. */
    private fun drainOutbound(): List<ByteArray> = generateSequence { channel.readOutbound<HttpContent>() }
        .map { content ->
            try {
                ByteArray(content.content().readableBytes()).also { content.content().readBytes(it) }
            } finally {
                content.release()
            }
        }
        .toList()

    @Test
    fun `writes below the threshold are held until flush`() = runTest {
        stream.write("hello".toByteArray())
        assertNull(channel.readOutbound<Any>(), "Nothing reaches Netty before a flush")

        stream.flush()

        val messages = drainOutbound()
        assertEquals(1, messages.size)
        assertEquals("hello", String(messages.single(), StandardCharsets.UTF_8))
    }

    @Test
    fun `write honors offset and length`() = runTest {
        stream.write("hello world".toByteArray(), offset = 6, length = 5)
        stream.flush()

        assertEquals("world", String(drainOutbound().single(), StandardCharsets.UTF_8))
    }

    @Test
    fun `many small writes reach Netty as one message`() = runTest {
        repeat(10) { stream.write("small".toByteArray()) }
        stream.flush()

        val messages = drainOutbound()
        assertEquals(1, messages.size, "Writes between flushes must be coalesced")
        assertEquals("small".repeat(10), String(messages.single(), StandardCharsets.UTF_8))
    }

    @Test
    fun `reaching the threshold sends one message without an explicit flush`() = runTest {
        val chunk = ByteArray(8 * 1024) { 0x42 }
        repeat(8) { stream.write(chunk) }

        val messages = drainOutbound()
        assertEquals(1, messages.size)
        assertEquals(64 * 1024, messages.single().size)

        stream.write(chunk)
        assertNull(channel.readOutbound<Any>(), "Bytes after the threshold wait for the next flush")
    }

    @Test
    fun `a write larger than the threshold is sent as one message`() = runTest {
        stream.write(ByteArray(100 * 1024) { 0x42 })

        assertEquals(listOf(100 * 1024), drainOutbound().map { it.size })
    }

    @Test
    fun `large streams keep every byte in order`() = runTest {
        val source = ByteArray(256 * 1024) { (it % 251).toByte() }
        for (offset in source.indices step 8 * 1024) stream.write(source, offset, 8 * 1024)
        stream.flush()

        val messages = drainOutbound()
        assertEquals(4, messages.size, "256 KiB in 8 KiB writes should leave in four 64 KiB messages")
        assertTrue(source.contentEquals(messages.reduce { all, next -> all + next }))
    }

    @Test
    fun `last content carries pending bytes`() = runTest {
        stream.write("tail".toByteArray())

        val last = stream.lastContent()
        try {
            assertEquals("tail", last.content().toString(StandardCharsets.UTF_8))
        } finally {
            last.release()
        }
        assertNull(channel.readOutbound<Any>())
    }

    @Test
    fun `last content is empty once everything was flushed`() = runTest {
        stream.write("body".toByteArray())
        stream.flush()
        drainOutbound()

        assertSame(LastHttpContent.EMPTY_LAST_CONTENT, stream.lastContent())
    }

    @Test
    fun `zero-length writes and empty input send nothing`() = runTest {
        stream.write(ByteArray(0))
        stream.copyFrom(ByteArrayInputStream(ByteArray(0)))

        assertNull(channel.readOutbound<Any>())
        assertSame(LastHttpContent.EMPTY_LAST_CONTENT, stream.lastContent())
    }

    @Test
    fun `copyFrom coalesces its reads`() = runTest {
        val input = ByteArray(100) { it.toByte() }
        stream.copyFrom(ByteArrayInputStream(input), bufferSize = 25)
        stream.flush()

        val messages = drainOutbound()
        assertEquals(1, messages.size, "Four 25-byte reads should reach Netty as one message")
        assertTrue(input.contentEquals(messages.single()))
    }

    @Test
    fun `flush with nothing pending flushes the channel`() = runTest {
        val ctx = mockk<ChannelHandlerContext>(relaxed = true)
        val mockChannel = mockk<Channel>(relaxed = true)
        every { ctx.channel() } returns mockChannel
        every { mockChannel.isWritable } returns true

        StreamingResponse(ctx).flush()

        verify(exactly = 1) { ctx.flush() }
        verify(exactly = 0) { ctx.writeAndFlush(any()) }
    }

    @Test
    fun `discard releases pending bytes`() = runTest {
        val buffer = Unpooled.buffer(16 * 1024)
        val ctx = mockk<ChannelHandlerContext>(relaxed = true)
        val allocator = mockk<ByteBufAllocator>()
        every { ctx.alloc() } returns allocator
        every { allocator.buffer(any()) } returns buffer
        val response = StreamingResponse(ctx)

        response.write("unsent".toByteArray())
        response.discard()

        assertEquals(0, buffer.refCnt())
        verify(exactly = 0) { ctx.write(any()) }
        verify(exactly = 0) { ctx.writeAndFlush(any()) }
    }

    @Test
    fun `a streaming response that fails after writing releases its buffered bytes and closes the connection`() = runTest {
        val failing = EmbeddedChannel(object : ChannelInboundHandlerAdapter() {})
        val allocated = mutableListOf<ByteBuf>()
        failing.config().allocator = object : ByteBufAllocator by UnpooledByteBufAllocator.DEFAULT {
            override fun buffer(initialCapacity: Int): ByteBuf =
                UnpooledByteBufAllocator.DEFAULT.buffer(initialCapacity).also { allocated += it }
        }
        try {
            val response = ServerResponse(failing.pipeline().firstContext())
            val failure = assertFailsWith<IllegalStateException> {
                response.respondStreamingWithTimeout(ContentType.Text.Plain) { stream ->
                    stream.write("partial".toByteArray())
                    error("producer failed")
                }
            }
            assertEquals("producer failed", failure.message)
            assertEquals(1, allocated.size, "The stream buffers its first write")
            assertEquals(0, allocated.single().refCnt(), "Bytes that will never be sent must be released")
            assertTrue(!failing.isOpen, "A half-written response leaves the connection unusable")
        } finally {
            failing.finishAndReleaseAll()
        }
    }

    @Test
    fun `a failed write keeps the pending buffer for discard`() = runTest {
        val buffer = Unpooled.buffer(4, 4)
        val ctx = mockk<ChannelHandlerContext>(relaxed = true)
        val allocator = mockk<ByteBufAllocator>()
        every { ctx.alloc() } returns allocator
        every { allocator.buffer(any()) } returns buffer
        val response = StreamingResponse(ctx)

        assertFailsWith<IndexOutOfBoundsException> { response.write(ByteArray(8)) }
        response.discard()

        assertEquals(0, buffer.refCnt())
    }

    /**
     * A response on [channel] as seen from a blocking thread: EmbeddedChannel's own executor always
     * reports being on its event loop, which the blocking stream refuses.
     */
    private fun offLoopStream(): StreamingResponse {
        val context = mockk<ChannelHandlerContext> {
            every { channel() } returns channel
            every { alloc() } returns channel.alloc()
            every { writeAndFlush(any()) } answers { channel.writeAndFlush(firstArg()) }
            every { flush() } answers { channel.flush(); self as ChannelHandlerContext }
            every { executor() } returns mockk { every { inEventLoop() } returns false }
        }
        return StreamingResponse(context)
    }

    @Test
    fun `outputStream writes through to the response from blocking code`() {
        val offLoop = offLoopStream()
        runBlocking {
            val output = offLoop.outputStream()
            output.write("hello ".toByteArray())
            output.write('w'.code)
            output.write("--orld--".toByteArray(), 2, 4)
            output.flush()
        }

        assertEquals("hello world", drainOutbound().joinToString("") { String(it, StandardCharsets.UTF_8) })
    }

    @Test
    fun `outputStream sends a full buffer without an explicit flush`() {
        val offLoop = offLoopStream()
        runBlocking { offLoop.outputStream().write(ByteArray(64 * 1024)) }

        assertEquals(64 * 1024, drainOutbound().sumOf { it.size })
    }

    @Test
    fun `outputStream writes fail once the calling coroutine is cancelled`() {
        val offLoop = offLoopStream()
        var writeFailure: Throwable? = null
        var flushFailure: Throwable? = null
        // The coroutine ends cancelled, so runBlocking itself rethrows the cancellation.
        assertFailsWith<CancellationException> {
            runBlocking {
                val output = offLoop.outputStream()
                coroutineContext.job.cancel()
                writeFailure = runCatching { output.write(1) }.exceptionOrNull()
                flushFailure = runCatching { output.flush() }.exceptionOrNull()
            }
        }
        assertTrue(writeFailure is CancellationException, "Got $writeFailure")
        assertTrue(flushFailure is CancellationException, "Got $flushFailure")
    }

    @Test
    fun `outputStream refuses to block the channel's event loop`() {
        // EmbeddedChannel runs everything "on" its event loop.
        runBlocking {
            val output = stream.outputStream()
            assertFailsWith<IllegalStateException> { output.write(1) }
            assertFailsWith<IllegalStateException> { output.flush() }
        }
    }

    @Test
    fun `a client that stops reading ends the response after the stall timeout`() = runTest {
        // Flushes never reach the transport, so written bytes stay queued as a stalled client leaves them.
        val stalled = EmbeddedChannel(
            object : ChannelOutboundHandlerAdapter() {
                override fun flush(ctx: ChannelHandlerContext) = Unit
            },
            object : ChannelInboundHandlerAdapter() {},
        )
        stalled.config().writeBufferWaterMark = WriteBufferWaterMark(1, 2)
        try {
            val response = StreamingResponse(stalled.pipeline().lastContext())
            response.write(ByteArray(16))

            assertFailsWith<StreamingTimeLimitException> { response.flush() }
            assertEquals(5.minutes.inWholeMilliseconds, testScheduler.currentTime, "Ended exactly at the stall timeout")

            // The stall is final: the stream is closed, and a writer that tries again (JGit reporting
            // the error to the client) is not held for another stall timeout.
            assertFalse(stalled.isActive)
            response.write(ByteArray(16))
            response.flush()
            assertEquals(5.minutes.inWholeMilliseconds, testScheduler.currentTime, "No second wait")
        } finally {
            stalled.finishAndReleaseAll()
        }
    }
}
