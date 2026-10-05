package bosca.server

import bosca.server.netty.WritabilityWaiter
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.DefaultHttpContent
import io.netty.handler.codec.http.DefaultLastHttpContent
import io.netty.handler.codec.http.LastHttpContent
import java.io.InputStream
import java.io.OutputStream
import java.util.Objects
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.minutes

/**
 * A coroutine-native streaming writer that forwards bytes to a Netty channel with proper
 * backpressure.
 *
 * Writes accumulate in one buffer and reach Netty as a single message per flush: every message
 * handed to Netty from an application thread costs a cross-thread task (and, for a compressed
 * response, a compression-executor submission), so many small writes cost far more than one
 * large one. The buffer is flushed to the network once it holds [FLUSH_THRESHOLD] bytes or when
 * [flush] is called; bytes still pending when the response ends travel in its final message.
 * After a flush, the calling coroutine suspends, without blocking a thread, until Netty signals
 * that the channel is writable again.
 *
 * This avoids unbounded memory growth when streaming large responses and does not block
 * any thread while waiting for the network to drain.
 */
class StreamingResponse(private val ctx: ChannelHandlerContext) {

    // Used by one writer at a time: the streaming coroutine, or the blocking thread of its outputStream().
    private var pending: ByteBuf? = null

    /**
     * Writes a region of [bytes] to the response. The bytes are buffered; once
     * [FLUSH_THRESHOLD] bytes are pending they are flushed and the coroutine suspends while the
     * channel is not writable.
     */
    suspend fun write(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size) {
        if (append(bytes, offset, length)) flush()
    }

    /** Buffers a region of [bytes]; returns true once [FLUSH_THRESHOLD] bytes are pending. */
    private fun append(bytes: ByteArray, offset: Int, length: Int): Boolean {
        if (length == 0) return false
        val buffer = pending ?: ctx.alloc().buffer(maxOf(length, INITIAL_BUFFER_SIZE)).also { pending = it }
        buffer.writeBytes(bytes, offset, length)
        return buffer.readableBytes() >= FLUSH_THRESHOLD
    }

    /**
     * Sends all buffered data to the network and suspends until the channel is writable. A client
     * that reads nothing for five minutes ends the response (see [awaitWritable]).
     */
    suspend fun flush() {
        val buffer = takePending()
        if (buffer == null) {
            ctx.flush()
        } else {
            // Netty owns the buffer from here and releases it once written or failed.
            ctx.writeAndFlush(DefaultHttpContent(buffer))
        }
        awaitWritable()
    }

    /**
     * Copies the entire contents of [inputStream] to the response, reading in chunks of
     * [bufferSize] bytes. Backpressure is applied automatically between chunks. Each blocking
     * read runs on [Dispatchers.IO].
     */
    suspend fun copyFrom(inputStream: InputStream, bufferSize: Int = 65536) {
        copyFrom(inputStream, bufferSize, Dispatchers.IO)
    }

    /**
     * Copies the entire contents of [inputStream] to the response like [copyFrom], running each
     * blocking read on [readDispatcher].
     *
     * Use a dispatcher of your own when the stream is fed by another blocking task, such as a pipe
     * whose writer also needs a thread to make progress: a read waiting on the pipe must not hold a
     * thread from a bounded pool that the writer's side depends on.
     */
    suspend fun copyFrom(inputStream: InputStream, bufferSize: Int, readDispatcher: CoroutineDispatcher) {
        val buffer = ByteArray(bufferSize)
        while (true) {
            val n = withContext(readDispatcher) {
                inputStream.read(buffer)
            }
            if (n < 0) break
            write(buffer, 0, n)
        }
    }

    /**
     * Returns a blocking [OutputStream] onto this response, for a blocking library that writes one
     * on a thread of its own (never an event loop). Each write or flush blocks while [write] or
     * [flush] would suspend. Cancelling the calling coroutine fails a later write with a
     * [kotlinx.coroutines.CancellationException], including one waiting for the channel. Closing
     * the stream does not end the response; returning from the streaming block does.
     *
     * @throws IllegalStateException from a write or flush on the channel's event loop, which would
     *   otherwise block the loop that has to drain it.
     */
    suspend fun outputStream(): OutputStream {
        val job = currentCoroutineContext().job
        return object : OutputStream() {
            override fun write(b: Int) {
                write(byteArrayOf(b.toByte()), 0, 1)
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                Objects.checkFromIndexSize(off, len, b.size)
                checkNotOnEventLoop()
                job.ensureActive()
                // Most writes only buffer; only a write that fills the buffer waits for the channel.
                if (append(b, off, len)) flush()
            }

            override fun flush() {
                checkNotOnEventLoop()
                runBlocking(job) { this@StreamingResponse.flush() }
            }
        }
    }

    private fun checkNotOnEventLoop() {
        check(!ctx.executor().inEventLoop()) { "A blocking response stream must not be used on the channel's event loop" }
    }

    /** Returns the message that ends the response, carrying any bytes still pending. */
    internal fun lastContent(): LastHttpContent =
        takePending()?.let(::DefaultLastHttpContent) ?: LastHttpContent.EMPTY_LAST_CONTENT

    /** Releases bytes that will never be sent because the response failed. */
    internal fun discard() {
        takePending()?.release()
    }

    private fun takePending(): ByteBuf? = pending.also { pending = null }

    /**
     * Suspends until the channel's outbound buffer drains below the low water mark or the channel
     * closes. A client that reads nothing for [STALL_TIMEOUT] ends the response: otherwise a paused
     * download on an HTTP/2 connection kept alive by other streams would hold its storage stream
     * for as long as the client stays connected.
     */
    private suspend fun awaitWritable() {
        if (withTimeoutOrNull(STALL_TIMEOUT) { WritabilityWaiter.awaitWritable(ctx.channel()) } == null) {
            // Closed as well as failed, so the stall is final: a writer that catches the failure and
            // writes again (JGit reports errors to the client) fails at once instead of waiting again.
            ctx.close()
            throw StreamingTimeLimitException("Client stopped reading the streaming response for $STALL_TIMEOUT")
        }
    }

    companion object {
        /** Send buffered bytes to the network once this many are pending (64 KiB). */
        private const val FLUSH_THRESHOLD = 64 * 1024

        /** How long a response may wait for a client that reads nothing, as the default idle timeout. */
        private val STALL_TIMEOUT = 5.minutes

        /** Initial capacity of the pending buffer; it grows toward [FLUSH_THRESHOLD] as needed. */
        private const val INITIAL_BUFFER_SIZE = 16 * 1024
    }
}
