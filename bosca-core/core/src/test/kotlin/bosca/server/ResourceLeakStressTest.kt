package bosca.server

import io.mockk.every
import io.mockk.mockk
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelConfig
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.codec.http.DefaultHttpContent
import io.netty.handler.codec.http.DefaultLastHttpContent
import io.netty.handler.codec.http.LastHttpContent
import io.netty.util.ReferenceCountUtil
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Stress tests for [RequestBody] resource lifecycle under high concurrency,
 * early close, error paths, and overflow conditions.
 *
 * Uses a [BufferTracker] to verify all [ByteBuf] instances are properly released
 * (refCnt == 0) at test end, detecting memory leaks from missing release calls.
 */
class ResourceLeakStressTest {

    /**
     * Tracks all [ByteBuf] instances created during a test and asserts they are all
     * released after the test scenario completes.
     */
    private class BufferTracker {
        private val buffers = mutableListOf<ByteBuf>()

        fun copiedBuffer(text: String): ByteBuf {
            val buf = Unpooled.copiedBuffer(text, StandardCharsets.UTF_8)
            buffers.add(buf)
            return buf
        }

        fun patternBuffer(size: Int, pattern: Byte = 0x42): ByteBuf {
            val bytes = ByteArray(size) { pattern }
            val buf = Unpooled.wrappedBuffer(bytes)
            buffers.add(buf)
            return buf
        }

        fun contentChunk(text: String): DefaultHttpContent =
            DefaultHttpContent(copiedBuffer(text))

        fun contentChunk(size: Int, pattern: Byte = 0x42): DefaultHttpContent =
            DefaultHttpContent(patternBuffer(size, pattern))

        fun lastChunk(): LastHttpContent =
            DefaultLastHttpContent(Unpooled.EMPTY_BUFFER)

        fun lastChunkWithContent(text: String): DefaultLastHttpContent =
            DefaultLastHttpContent(copiedBuffer(text))

        /**
         * Adds a content chunk to the body and releases the original message,
         * simulating the production lifecycle where [NettyHttpHandler.channelRead]
         * releases the message in its finally block after [RequestBody.addContent] retains the buffer.
         */
        fun feedChunk(body: RequestBody, text: String) {
            val chunk = contentChunk(text)
            body.addContent(chunk)
            ReferenceCountUtil.release(chunk)
        }

        fun feedChunk(body: RequestBody, size: Int, pattern: Byte = 0x42) {
            val chunk = contentChunk(size, pattern)
            body.addContent(chunk)
            ReferenceCountUtil.release(chunk)
        }

        fun feedLastChunk(body: RequestBody) {
            body.addContent(lastChunk())
        }

        fun assertAllReleased() {
            for ((index, buf) in buffers.withIndex()) {
                assertEquals(
                    0, buf.refCnt(),
                    "Buffer $index was not released (refCnt=${buf.refCnt()})"
                )
            }
        }

        val size: Int get() = buffers.size
    }

    private fun createMockCtx(): ChannelHandlerContext {
        val ctx = mockk<ChannelHandlerContext>(relaxed = true)
        val channel = mockk<Channel>(relaxed = true)
        val config = mockk<ChannelConfig>(relaxed = true)
        every { ctx.channel() } returns channel
        every { channel.config() } returns config
        every { config.isAutoRead } returns true
        return ctx
    }

    // --- readAllBytes tests ---

    @Test
    fun `readAllBytes releases all buffers under high chunk count`() = runTest {
        val tracker = BufferTracker()
        val body = RequestBody()

        repeat(500) { i ->
            tracker.feedChunk(body, "chunk-$i-")
        }
        tracker.feedLastChunk(body)

        val bytes = body.readAllBytes()
        assertTrue(bytes.isNotEmpty())
        tracker.assertAllReleased()
    }

    @Test
    fun `readAllBytes after overflow condition releases all buffers`() = runTest {
        val tracker = BufferTracker()
        val ctx = mockk<ChannelHandlerContext>(relaxed = true)
        val channel = EmbeddedChannel()
        every { ctx.channel() } returns channel

        try {
            val body = RequestBody(ctx, 10_000_000L)

            // Feed HIGH_WATERMARK + 5 chunks to trigger backpressure
            repeat(21) { i ->
                tracker.feedChunk(body, "overflow-$i-")
            }
            tracker.feedLastChunk(body)
            channel.runPendingTasks()

            assertTrue(!channel.config().isAutoRead, "Backpressure should have been applied")

            val bytes = body.readAllBytes()
            channel.runPendingTasks()
            assertTrue(bytes.isNotEmpty())
            assertEquals(21, tracker.size)
            tracker.assertAllReleased()
        } finally {
            channel.finishAndReleaseAll()
        }
    }

    // --- consumeChunks tests ---

    @Test
    fun `consumeChunks releases current buffer when handler throws`() = runTest {
        val tracker = BufferTracker()
        val body = RequestBody()

        repeat(10) { i ->
            tracker.feedChunk(body, "throw-$i-")
        }
        tracker.feedLastChunk(body)

        var processedCount = 0
        try {
            body.consumeChunks { content ->
                processedCount++
                if (processedCount == 5) {
                    error("Intentional exception on chunk 5")
                }
            }
        } catch (_: IllegalStateException) {
            // Expected
        }

        // The first 5 chunks (including the one that threw) should be released
        // via the finally block in consumeChunks
        for (i in 0 until 5) {
            assertEquals(
                0, tracker.buffers[i].refCnt(),
                "Buffer $i should be released (processed before/at throw)"
            )
        }
    }

    // --- Concurrent readAllBytes tests ---

    @Test
    fun `concurrent readAllBytes across many bodies`() = runTest {
        val trackers = mutableListOf<BufferTracker>()
        val jobs = mutableListOf<Job>()
        coroutineScope {
            repeat(200) {
                jobs += launch {
                    val tracker = BufferTracker()
                    synchronized(trackers) { trackers.add(tracker) }

                    val body = RequestBody()
                    repeat(20) { i ->
                        tracker.feedChunk(body, "conc-$it-$i-")
                    }
                    tracker.feedLastChunk(body)

                    val bytes = body.readAllBytes()
                    assertTrue(bytes.isNotEmpty())
                }
            }
        }

        jobs.joinAll()

        assertEquals(200, trackers.size)
        trackers.forEach { it.assertAllReleased() }
    }

    // --- Large streaming test ---

    @Test
    fun `large streaming body completes without OOM`() = runTest {
        val body = RequestBody()
        val chunkSize = 8192
        val numChunks = 10_000
        val pattern = ByteArray(chunkSize) { (it % 251).toByte() }

        // Launch producer in parallel with consumer
        val producer = launch {
            repeat(numChunks) {
                body.addContent(DefaultHttpContent(Unpooled.wrappedBuffer(pattern.copyOf())))
            }
            body.addContent(DefaultLastHttpContent(Unpooled.EMPTY_BUFFER))
        }

        val bytes = body.readAllBytes()
        producer.join()

        assertEquals(chunkSize.toLong() * numChunks, bytes.size.toLong())
    }

    // Expose the private buffers list for consumeChunks test assertion
    private val BufferTracker.buffers: List<ByteBuf>
        get() {
            val field = BufferTracker::class.java.getDeclaredField("buffers")
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            return field.get(this) as List<ByteBuf>
        }
}
