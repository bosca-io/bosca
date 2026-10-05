package bosca.server

import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.codec.http.DefaultHttpContent
import io.netty.handler.codec.http.DefaultLastHttpContent
import io.netty.handler.codec.http.LastHttpContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RequestBodyTest {

    private fun contentChunk(text: String): DefaultHttpContent {
        return DefaultHttpContent(Unpooled.copiedBuffer(text, StandardCharsets.UTF_8))
    }

    private fun lastChunk(): LastHttpContent = DefaultLastHttpContent(Unpooled.EMPTY_BUFFER)

    private fun lastChunkWithContent(text: String): DefaultLastHttpContent {
        return DefaultLastHttpContent(Unpooled.copiedBuffer(text, StandardCharsets.UTF_8))
    }

    @Test
    fun `readAllBytes returns complete body from single chunk`() = runTest {
        val body = RequestBody()
        body.addContent(lastChunkWithContent("hello world"))
        val bytes = body.readAllBytes()
        assertEquals("hello world", String(bytes, StandardCharsets.UTF_8))
    }

    @Test
    fun `readAllBytes returns empty for empty last chunk`() = runTest {
        val body = RequestBody()
        body.addContent(lastChunk())
        val bytes = body.readAllBytes()
        assertEquals(0, bytes.size)
    }

    @Test
    fun `readAllBytes concatenates multiple chunks`() = runTest {
        val body = RequestBody()
        body.addContent(contentChunk("hello "))
        body.addContent(contentChunk("world"))
        body.addContent(lastChunk())
        val bytes = body.readAllBytes()
        assertEquals("hello world", String(bytes, StandardCharsets.UTF_8))
    }

    @Test
    fun `readAllBytes releases retained chunks after assembly`() = runTest {
        val body = RequestBody()
        val first = contentChunk("hello ")
        val last = lastChunkWithContent("world")
        val firstBuffer = first.content()
        val lastBuffer = last.content()
        body.addContent(first)
        body.addContent(last)
        first.release()
        last.release()

        assertEquals("hello world", String(body.readAllBytes(), StandardCharsets.UTF_8))
        assertEquals(0, firstBuffer.refCnt())
        assertEquals(0, lastBuffer.refCnt())
    }

    @Test
    fun `readAllBytes handles many small chunks`() = runTest {
        val body = RequestBody()
        val expected = StringBuilder()
        for (i in 0 until 100) {
            val text = "chunk$i "
            body.addContent(contentChunk(text))
            expected.append(text)
        }
        body.addContent(lastChunk())
        val bytes = body.readAllBytes()
        assertEquals(expected.toString(), String(bytes, StandardCharsets.UTF_8))
    }

    @Test
    fun `readAllBytes handles binary data`() = runTest {
        val body = RequestBody()
        val binaryData = ByteArray(256) { it.toByte() }
        body.addContent(DefaultLastHttpContent(Unpooled.wrappedBuffer(binaryData)))
        val bytes = body.readAllBytes()
        assertContentEquals(binaryData, bytes)
    }

    @Test
    fun `consumeChunks delivers chunks and signals end`() = runTest {
        val body = RequestBody()
        body.addContent(contentChunk("hello "))
        body.addContent(contentChunk("world"))
        body.addContent(lastChunk())

        val received = mutableListOf<String>()
        var gotEnd = false
        body.consumeChunks { content ->
            if (content is LastHttpContent && content.content().readableBytes() == 0) {
                gotEnd = true
            } else {
                val buf = content.content()
                val bytes = ByteArray(buf.readableBytes())
                buf.readBytes(bytes)
                received.add(String(bytes, StandardCharsets.UTF_8))
            }
        }

        assertEquals(listOf("hello ", "world"), received)
        assertTrue(gotEnd)
    }

    @Test
    fun `consumeChunks works with single last chunk containing data`() = runTest {
        val body = RequestBody()
        body.addContent(lastChunkWithContent("all at once"))

        val received = mutableListOf<String>()
        var gotEnd = false
        body.consumeChunks { content ->
            if (content is LastHttpContent && content.content().readableBytes() == 0) {
                gotEnd = true
            } else {
                val buf = content.content()
                val bytes = ByteArray(buf.readableBytes())
                buf.readBytes(bytes)
                received.add(String(bytes, StandardCharsets.UTF_8))
            }
        }

        assertEquals(listOf("all at once"), received)
        assertTrue(gotEnd)
    }

    @Test
    fun `readAllBytes works when chunks arrive concurrently`() = runTest {
        val body = RequestBody()

        // Simulate async chunk delivery
        launch {
            body.addContent(contentChunk("async "))
            body.addContent(contentChunk("chunks"))
            body.addContent(lastChunk())
        }

        val bytes = body.readAllBytes()
        assertEquals("async chunks", String(bytes, StandardCharsets.UTF_8))
    }

    @Test
    fun `large body is reassembled correctly`() = runTest {
        val body = RequestBody()
        val chunkSize = 8192
        val numChunks = 50
        val data = ByteArray(chunkSize) { (it % 256).toByte() }

        for (i in 0 until numChunks) {
            body.addContent(DefaultHttpContent(Unpooled.wrappedBuffer(data.copyOf())))
        }
        body.addContent(lastChunk())

        val result = body.readAllBytes()
        assertEquals(chunkSize * numChunks, result.size)

        // Verify content integrity
        for (i in 0 until numChunks) {
            val slice = result.sliceArray(i * chunkSize until (i + 1) * chunkSize)
            assertContentEquals(data, slice)
        }
    }

    @Test
    fun `empty chunks are skipped`() = runTest {
        val body = RequestBody()
        body.addContent(contentChunk("a"))
        body.addContent(DefaultHttpContent(Unpooled.EMPTY_BUFFER))
        body.addContent(contentChunk("b"))
        body.addContent(lastChunk())
        val bytes = body.readAllBytes()
        assertEquals("ab", String(bytes, StandardCharsets.UTF_8))
    }

    @Test
    fun `reading a discarded body without an explicit cause reports closed channel`() = runTest {
        val body = RequestBody()
        body.discard()

        val error = assertFailsWith<IOException> { body.readAllBytes() }

        assertTrue(error.message.orEmpty().contains("closed unexpectedly"))
    }

    @Test
    fun `configured size limit discards the body and surfaces its cause`() = runTest {
        val channel = EmbeddedChannel(ChannelInboundHandlerAdapter())
        try {
            val body = RequestBody(channel.pipeline().firstContext(), maxBodySize = 3)
            val content = lastChunkWithContent("four")
            body.addContent(content)
            content.release()

            val error = assertFailsWith<RequestBodyTooLargeException> { body.readAllBytes() }

            assertTrue(error.message.orEmpty().contains("3 bytes"))
        } finally {
            channel.finishAndReleaseAll()
        }
    }

    @Test
    fun `content arriving after discard releases its attempted retention`() {
        val body = RequestBody()
        body.discard(IOException("abandoned"))
        val content = contentChunk("late")
        val buffer = content.content()

        body.addContent(content)

        assertEquals(1, buffer.refCnt())
        content.release()
        assertEquals(0, buffer.refCnt())
    }

    @Test
    fun `draining below the low watermark resumes paused reads`() = runTest {
        val channel = EmbeddedChannel(ChannelInboundHandlerAdapter())
        try {
            val body = RequestBody(channel.pipeline().firstContext(), Long.MAX_VALUE)
            repeat(16) {
                val content = contentChunk("x")
                body.addContent(content)
                content.release()
            }
            body.addContent(lastChunk())
            channel.runPendingTasks()
            assertFalse(channel.config().isAutoRead)

            assertEquals(16, body.readAllBytes().size)
            channel.runPendingTasks()

            assertTrue(channel.config().isAutoRead)
        } finally {
            channel.finishAndReleaseAll()
        }
    }

    @Test
    fun `discard releases request body backpressure and retained chunks`() {
        val channel = EmbeddedChannel(ChannelInboundHandlerAdapter())
        val buffers = mutableListOf<ByteBuf>()
        try {
            val body = RequestBody(channel.pipeline().firstContext(), Long.MAX_VALUE)
            repeat(16) {
                val content = contentChunk("chunk-$it")
                buffers += content.content()
                body.addContent(content)
                content.release()
            }
            channel.runPendingTasks()
            assertFalse(channel.config().isAutoRead, "high watermark must pause channel reads")

            body.discard()
            channel.runPendingTasks()

            assertTrue(channel.config().isAutoRead, "discarding an abandoned body must resume channel reads")
            buffers.forEach { buffer -> assertEquals(0, buffer.refCnt()) }
        } finally {
            channel.finishAndReleaseAll()
        }
    }

    @Test
    fun `inputStream reads the body across chunk boundaries and releases each chunk`() {
        val body = RequestBody()
        val first = contentChunk("hello ")
        val last = lastChunkWithContent("world")
        body.addContent(first)
        body.addContent(last)
        // addContent retained each buffer; drop the test's own references.
        first.release()
        last.release()

        val input = body.inputStream(Job())
        val head = ByteArray(4)
        assertEquals(4, input.read(head))
        assertEquals("hell", String(head, StandardCharsets.UTF_8))
        assertEquals('o'.code, input.read())
        assertEquals(" world", String(input.readAllBytes(), StandardCharsets.UTF_8))
        assertEquals(-1, input.read())
        input.close()

        assertEquals(0, first.refCnt())
        assertEquals(0, last.refCnt())
    }

    @Test
    fun `inputStream holds no pooled buffer even when the reader stops early without closing`() {
        val body = RequestBody()
        val chunk = contentChunk("{\"a\":1} trailing")
        body.addContent(chunk)
        body.addContent(lastChunk())
        chunk.release()

        // A parser reads only as much as it needs and never closes the stream.
        val input = body.inputStream(Job())
        assertEquals('{'.code, input.read())

        assertEquals(0, chunk.refCnt(), "The chunk is released as soon as it is read into the stream")
    }

    @Test
    fun `inputStream read blocks until data arrives`() {
        val body = RequestBody()
        val input = body.inputStream(Job())
        val read = AtomicReference<String>()
        val reader = thread { read.set(String(input.readAllBytes(), StandardCharsets.UTF_8)) }
        awaitWaiting(reader)

        body.addContent(lastChunkWithContent("late"))

        reader.join(5_000)
        assertEquals("late", read.get())
    }

    @Test
    fun `cancelling the job fails a waiting read`() {
        val body = RequestBody()
        val job = Job()
        val input = body.inputStream(job)
        val failure = AtomicReference<Throwable>()
        val reader = thread {
            try {
                input.read()
            } catch (e: Throwable) {
                failure.set(e)
            }
        }
        awaitWaiting(reader)

        job.cancel()

        reader.join(5_000)
        assertTrue(failure.get() is CancellationException, "Got ${failure.get()}")
    }

    @Test
    fun `a failed read is thrown to the reader and leaves the job running`() {
        val body = RequestBody()
        val job = Job()
        body.discard(IOException("client reset"))

        val failure = assertFailsWith<IOException> { body.inputStream(job).read() }

        assertEquals("client reset", failure.message)
        assertTrue(job.isActive, "The reader decides what a failed read means; the job is not failed for it")
    }

    @Test
    fun `closing inputStream early discards the rest of the body`() {
        val body = RequestBody()
        val unread = contentChunk("unread")
        body.addContent(unread)
        unread.release()

        body.inputStream(Job()).close()

        assertEquals(0, unread.refCnt(), "Buffered chunks are released")
        val late = contentChunk("late")
        body.addContent(late)
        late.release()
        assertEquals(0, late.refCnt(), "Later chunks are dropped")
    }

    @Test
    fun `inputStream refuses to block the channel's event loop`() {
        val channel = EmbeddedChannel(ChannelInboundHandlerAdapter())
        try {
            // EmbeddedChannel runs everything "on" its event loop.
            val body = RequestBody(channel.pipeline().firstContext(), 0)
            assertFailsWith<IllegalStateException> { body.inputStream(Job()).read() }
        } finally {
            channel.finishAndReleaseAll()
        }
    }

    /** Waits until [thread] is parked waiting for body data. */
    private fun awaitWaiting(thread: Thread) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (thread.state != Thread.State.WAITING && thread.state != Thread.State.TIMED_WAITING) {
            check(System.nanoTime() < deadline) { "The reader never blocked" }
            Thread.onSpinWait()
        }
    }
}
