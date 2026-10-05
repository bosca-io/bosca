package bosca.server

import bosca.server.netty.ChannelReadPauseReason
import bosca.server.netty.pauseReads
import bosca.server.netty.resumeReads
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.DefaultHttpContent
import io.netty.handler.codec.http.HttpContent
import io.netty.handler.codec.http.LastHttpContent
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Objects
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Bridges Netty's chunk-based HTTP request body delivery with coroutine-based consumers,
 * applying backpressure to prevent unbounded memory growth from fast clients or slow handlers.
 *
 * Chunks are queued into an unbounded coroutine [Channel]. When the number of buffered chunks
 * exceeds [HIGH_WATERMARK], Netty's auto-read is disabled on the socket, pausing reads from the
 * client until the consumer drains enough chunks. This ensures the server uses bounded memory
 * regardless of upload size or client speed.
 *
 * This design removes the need for [io.netty.handler.codec.http.HttpObjectAggregator],
 * allowing arbitrarily large request bodies without buffering the entire body in memory.
 */
class RequestBody internal constructor(
    private val ctx: ChannelHandlerContext?,
    private val maxBodySize: Long
) {

    /** Test-only constructor that disables backpressure and size limits (no Netty channel to control). */
    internal constructor() : this(null, 0)

    // A chunk the channel drops (a receive cancelled as it was handed one) is released here.
    // Chunks refused by a closed channel are not passed here; addContent releases those.
    private val chunks = Channel<BodyChunk>(Channel.UNLIMITED) { chunk -> (chunk as? BodyChunk.Data)?.buf?.release() }
    private val chunkCount = AtomicInteger(0)
    private val accumulatedBytes = AtomicLong(0L)

    /**
     * Records why the body was discarded so that consumers blocked on [receiveNext]
     * receive a meaningful exception instead of a bare [ClosedReceiveChannelException].
     */
    @Volatile
    private var closedCause: Throwable? = null

    /**
     * Adds an incoming HTTP content chunk to the body channel.
     * Called from the Netty event loop thread as chunks arrive.
     *
     * Uses [Channel.trySend] which is non-blocking and always succeeds on an unlimited channel.
     * When the buffered chunk count exceeds [HIGH_WATERMARK], auto-read is disabled on the
     * Netty channel to stop reading from the socket until the consumer catches up.
     *
     * Enforces [maxBodySize] for every consumer: once the running total exceeds it, the body is
     * discarded and consumers receive [RequestBodyTooLargeException]. [readAllBytes] additionally
     * caps what it will hold in a single array.
     *
     * @param content the HTTP content chunk; its buffer is retained for later consumption
     */
    fun addContent(content: HttpContent) {
        val buf = content.content()
        if (buf.readableBytes() > 0) {
            val newTotal = accumulatedBytes.addAndGet(buf.readableBytes().toLong())
            if (maxBodySize > 0 && newTotal > maxBodySize) {
                discard(RequestBodyTooLargeException(maxBodySize))
                return
            }
            val retained = buf.retain()
            val result = chunks.trySend(BodyChunk.Data(retained))
            if (result.isFailure) {
                retained.release()
                return
            }
            if (chunkCount.incrementAndGet() >= HIGH_WATERMARK) {
                ctx?.channel()?.pauseReads(ChannelReadPauseReason.REQUEST_BODY)
            }
        }
        if (content is LastHttpContent) {
            chunks.trySend(BodyChunk.End)
        }
    }

    /**
     * Closes the body to new chunks, then drains and releases everything already buffered.
     * Call this when the request body is abandoned (e.g., due to an error or connection close)
     * to prevent ByteBuf leaks from unconsumed queued chunks.
     *
     * @param cause optional reason for the discard, surfaced to consumers via [receiveNext]
     */
    fun discard(cause: Throwable? = null) {
        if (cause != null) {
            closedCause = cause
        }
        chunks.close()
        var discardedChunks = 0
        try {
            while (true) {
                val chunk = chunks.tryReceive().getOrNull() ?: break
                if (chunk is BodyChunk.Data) {
                    chunk.buf.release()
                    discardedChunks++
                }
            }
        } finally {
            if (discardedChunks > 0) {
                chunkCount.addAndGet(-discardedChunks)
            }
            ctx?.channel()?.resumeReads(ChannelReadPauseReason.REQUEST_BODY)
        }
    }

    /**
     * Reads the entire request body into a byte array, suspending while waiting for chunks.
     *
     * Enforces the configured [maxBodySize] limit to prevent out-of-memory errors
     * from accumulating an unbounded body in memory.
     *
     * For small request bodies (JSON, form data, etc.) this is the simplest approach.
     * For large uploads, prefer [consumeChunks] to avoid loading the full body into memory.
     *
     * This method consumes the body — it can only be called once per request.
     */
    suspend fun readAllBytes(): ByteArray {
        val buffers = ArrayList<ByteBuf>(chunkCount.get().coerceAtLeast(1))
        try {
            while (true) {
                when (val chunk = receiveNext()) {
                    is BodyChunk.Data -> {
                        val buf = chunk.buf
                        buffers.add(buf)
                        val bodySize = accumulatedBytes.get()
                        if (maxBodySize > 0 && bodySize > maxBodySize) {
                            discard(RequestBodyTooLargeException(maxBodySize))
                            throw RequestBodyTooLargeException(maxBodySize)
                        }
                        if (bodySize > MAX_BUFFER_SIZE) {
                            val limit = if (maxBodySize > 0) {
                                minOf(maxBodySize, MAX_BUFFER_SIZE.toLong())
                            } else {
                                MAX_BUFFER_SIZE.toLong()
                            }
                            val exception = RequestBodyTooLargeException(limit)
                            discard(exception)
                            throw exception
                        }
                    }
                    is BodyChunk.End -> break
                }
            }

            val bodySize = accumulatedBytes.get()
            if (bodySize == 0L) return EMPTY_BYTES
            val bytes = ByteArray(bodySize.toInt())
            var offset = 0
            for (buf in buffers) {
                val readableBytes = buf.readableBytes()
                buf.readBytes(bytes, offset, readableBytes)
                offset += readableBytes
            }
            return bytes
        } finally {
            buffers.forEach { it.release() }
        }
    }

    /**
     * Consumes body chunks by wrapping each one as a [DefaultHttpContent] and passing
     * it to the given [handler]. Signals completion by passing [LastHttpContent.EMPTY_LAST_CONTENT].
     *
     * Designed for use with Netty's [io.netty.handler.codec.http.multipart.HttpPostRequestDecoder],
     * which accepts [HttpContent] objects via its `offer` method for incremental multipart parsing.
     *
     * Each chunk is processed and released immediately, so the full body is never held in memory;
     * backpressure ([HIGH_WATERMARK]) bounds the number of in-flight chunks. The total size is
     * still bounded by [maxBodySize], enforced as chunks arrive in [addContent].
     *
     * Each chunk's buffer is released after the handler processes it.
     * This method consumes the body — it can only be called once per request.
     */
    suspend fun consumeChunks(handler: (HttpContent) -> Unit) {
        while (true) {
            when (val chunk = receiveNext()) {
                is BodyChunk.Data -> {
                    try {
                        handler(DefaultHttpContent(chunk.buf))
                    } finally {
                        chunk.buf.release()
                    }
                }
                is BodyChunk.End -> {
                    handler(LastHttpContent.EMPTY_LAST_CONTENT)
                    return
                }
            }
        }
    }

    /**
     * Receives the next chunk from the channel, suspending until one is available,
     * and re-enables auto-read on the Netty channel after each data chunk is consumed.
     *
     * If the channel has been closed (e.g., by [discard] due to connection loss or error),
     * the [closedCause] is thrown to provide meaningful context to the consumer instead of
     * the bare [ClosedReceiveChannelException].
     */
    private suspend fun receiveNext(): BodyChunk {
        val chunk = try {
            chunks.receive()
        } catch (e: ClosedReceiveChannelException) {
            throw closedCause ?: IOException("Request body channel closed unexpectedly", e)
        }
        if (chunk is BodyChunk.Data) {
            chunkCount.decrementAndGet()
        }
        resumeReadsIfPaused()
        return chunk
    }

    /**
     * Re-enables auto-read on the Netty channel once the buffered chunk count drops
     * below [LOW_WATERMARK], preventing rapid toggling of auto-read under sustained load.
     * Without this hysteresis, a single [receiveNext] call at [HIGH_WATERMARK] would
     * immediately re-enable reads only to hit the high watermark again on the next chunk.
     */
    private fun resumeReadsIfPaused() {
        val channel = ctx?.channel() ?: return
        if (chunkCount.get() < LOW_WATERMARK) {
            channel.resumeReads(ChannelReadPauseReason.REQUEST_BODY)
        }
    }

    /**
     * Streams the request body chunk by chunk into the given [output], suspending
     * between chunks. Unlike [readAllBytes], this does not accumulate the full body
     * in memory — each chunk is written and released before the next is fetched.
     *
     * Returns the total number of bytes written.
     *
     * This method consumes the body — it can only be called once per request.
     */
    suspend fun streamTo(output: OutputStream): Long = streamTo(output, Dispatchers.IO)

    /**
     * Streams the request body into [output] like [streamTo], running each blocking write on
     * [writeDispatcher].
     *
     * Use a dispatcher of your own when [output] blocks until another task consumes it, such as a
     * pipe: a write waiting on the pipe must not hold a thread from a bounded pool that the
     * consumer's side depends on.
     */
    suspend fun streamTo(output: OutputStream, writeDispatcher: CoroutineDispatcher): Long {
        var totalBytes = 0L
        while (true) {
            when (val chunk = receiveNext()) {
                is BodyChunk.Data -> {
                    val buf = chunk.buf
                    try {
                        val bytes = ByteArray(buf.readableBytes())
                        buf.readBytes(bytes)
                        withContext(writeDispatcher) {
                            output.write(bytes)
                        }
                        totalBytes += bytes.size
                    } finally {
                        buf.release()
                    }
                }
                is BodyChunk.End -> break
            }
        }
        return totalBytes
    }

    /**
     * The body as a blocking [InputStream] whose reads bridge to [receiveNext] as children of [job];
     * see [ServerRequest.bodyInputStream].
     */
    internal fun inputStream(job: Job): InputStream = object : InputStream() {
        // The unread rest of the chunk being read, copied out of its pooled buffer as it arrives so a
        // caller that neither reads to the end nor closes the stream holds nothing of Netty's.
        // Used by one reading thread at a time.
        private var current = EMPTY_BYTES
        private var position = 0
        private var ended = false

        override fun read(): Int {
            val single = ByteArray(1)
            return if (read(single, 0, 1) < 0) -1 else single[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            Objects.checkFromIndexSize(off, len, b.size)
            if (len == 0) return 0
            if (position == current.size && !nextChunk()) return -1
            val count = minOf(len, current.size - position)
            System.arraycopy(current, position, b, off, count)
            position += count
            return count
        }

        /** Loads the next non-empty chunk; returns false once the body has ended. */
        private fun nextChunk(): Boolean {
            // Waiting for data on the loop that has to deliver it would block that loop for good.
            check(ctx?.executor()?.inEventLoop() != true) { "A blocking request body must not be read on the channel's event loop" }
            while (!ended) {
                when (val chunk = receiveBlocking()) {
                    is BodyChunk.Data -> {
                        val bytes = try {
                            ByteArray(chunk.buf.readableBytes()).also(chunk.buf::readBytes)
                        } finally {
                            chunk.buf.release()
                        }
                        if (bytes.isNotEmpty()) {
                            current = bytes
                            position = 0
                            return true
                        }
                    }
                    is BodyChunk.End -> ended = true
                }
            }
            return false
        }

        /**
         * Receives the next chunk on this thread. A chunk received just as [job] is cancelled is
         * released, since the cancellation then replaces it as runBlocking's result.
         */
        private fun receiveBlocking(): BodyChunk {
            var received: BodyChunk? = null
            try {
                return runBlocking(job) { receiveNext().also { received = it } }
            } catch (e: CancellationException) {
                (received as? BodyChunk.Data)?.buf?.release()
                throw e
            }
        }

        override fun close() {
            current = EMPTY_BYTES
            position = 0
            if (!ended) {
                ended = true
                discard()
            }
        }
    }

    private sealed class BodyChunk {
        class Data(val buf: ByteBuf) : BodyChunk()
        data object End : BodyChunk()
    }

    companion object {
        private val EMPTY_BYTES = ByteArray(0)

        /** Largest practical JVM byte array, leaving room for VM-specific array headers. */
        private const val MAX_BUFFER_SIZE = Int.MAX_VALUE - 8

        /**
         * Number of chunks to buffer before applying backpressure.
         * With typical 8KB–64KB Netty chunks, 16 slots allows 128KB–1MB of buffering
         * before pausing socket reads.
         */
        private const val HIGH_WATERMARK = 16

        /**
         * Number of buffered chunks below which auto-read is re-enabled after backpressure.
         * This hysteresis band between [LOW_WATERMARK] and [HIGH_WATERMARK] prevents rapid
         * toggling of auto-read under sustained load.
         */
        private const val LOW_WATERMARK = 4
    }
}

/**
 * Thrown when the request body exceeds the configured maximum size,
 * indicating the server should respond with HTTP 413 Payload Too Large.
 */
class RequestBodyTooLargeException(maxSize: Long) :
    IllegalStateException("Request body exceeds maximum size of $maxSize bytes")
