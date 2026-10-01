package bosca.server.websocket

import bosca.server.ServerCall
import bosca.server.netty.ChannelAttributes
import bosca.server.netty.ChannelReadPauseReason
import bosca.server.netty.resumeReads
import io.netty.channel.Channel
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelFutureListener
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.channels.Channel as KChannel
import kotlinx.coroutines.channels.ReceiveChannel
import java.io.IOException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Represents an active WebSocket connection, providing methods to send and receive
 * text frames, check connection status, and close the session.
 *
 * Backed by a Netty channel with coroutine-based message passing for integration
 * with the suspend-based server framework.
 */
class WebSocketSession(
    val call: ServerCall,
    private val channel: Channel,
    coroutineContext: CoroutineContext
) : CoroutineScope {
    // Replaced once when the route starts (inheritHandlerContext) and read by any coroutine that
    // launches on the session, possibly on another thread.
    @Volatile
    override var coroutineContext: CoroutineContext = coroutineContext
        private set

    /** Carries handler context into session-launched operations while retaining the connection's job. */
    internal fun inheritHandlerContext(context: CoroutineContext) {
        coroutineContext += context.minusKey(Job)
    }

    /**
     * Incoming frames: text, binary, pong, and the peer's close. Call [frameConsumed] after
     * processing each text, binary or pong frame so read backpressure can release (calling it for
     * the close too is harmless).
     */
    val incoming: ReceiveChannel<WebSocketFrame> get() = _incoming

    // Unbounded; paused socket reads bound it (see WebSocketFrameIntake.deliverData).
    internal val _incoming = KChannel<WebSocketFrame>(KChannel.UNLIMITED)

    /** The latest ping's payload, awaiting its pong until the channel is writable; event loop only. */
    internal var deferredPingPayload: ByteArray? = null

    /** Tracks the approximate number of frames buffered in [_incoming] for backpressure decisions. */
    internal val pendingFrameCount = AtomicInteger(0)

    /** Deferred that completes when the WebSocket connection is closed, providing the close reason. */
    val closeReason = CompletableDeferred<CloseReason?>()

    /** The session's one close-frame write, once sent; see [writeClose]. */
    internal val closeWriteFuture = AtomicReference<ChannelFuture?>()

    /** Returns true if the WebSocket connection is still active. */
    val isActive: Boolean get() = channel.isActive

    /**
     * Signals that a frame received from [incoming] has been processed.
     * Decrements the pending frame counter and releases WebSocket backpressure
     * if the buffer has drained below the low watermark. Other independent
     * pause reasons continue to hold the channel in manual-read mode.
     */
    fun frameConsumed() {
        val count = pendingFrameCount.updateAndGet { pending ->
            if (pending > 0) pending - 1 else 0
        }
        if (count < RESUME_READ_WATERMARK) {
            channel.resumeReads(ChannelReadPauseReason.WEBSOCKET)
        }
    }

    companion object {
        /** Re-enable auto-read when the pending frame count drops below this threshold. */
        private const val RESUME_READ_WATERMARK = 128
    }

    /**
     * Sends a text message over the WebSocket connection, suspending until the write completes.
     *
     * @throws IOException if the channel is no longer active, or a close frame was already sent
     *   (nothing may follow it, RFC 6455 §5.5.1)
     */
    suspend fun send(text: String) {
        if (!channel.isActive) throw IOException("WebSocket channel is no longer active")
        val frame = TextWebSocketFrame(text)
        val promise = channel.newPromise()
        // The check runs on the event loop together with the write, so a close frame written there
        // meanwhile (by the peer's close or a server close) can never be followed by this frame.
        val write = Runnable {
            if (closeWriteFuture.get() != null) {
                frame.release()
                promise.tryFailure(IOException("WebSocket is closing"))
            } else {
                channel.writeAndFlush(frame, promise)
            }
        }
        val eventLoop = channel.eventLoop()
        if (eventLoop.inEventLoop()) {
            write.run()
        } else {
            try {
                eventLoop.execute(write)
            } catch (e: RejectedExecutionException) {
                frame.release()
                throw IOException("WebSocket channel is no longer active", e)
            }
        }
        suspendCancellableCoroutine { cont ->
            promise.addListener { f ->
                if (f.isSuccess) {
                    cont.resume(Unit)
                } else {
                    cont.resumeWithException(f.cause() ?: IOException("WebSocket write failed"))
                }
            }
            cont.invokeOnCancellation { promise.cancel(false) }
        }
    }

    /** Flushes any pending writes on the underlying channel. No-op since [send] already uses writeAndFlush. */
    fun flush() {
        channel.flush()
    }

    /**
     * Closes the WebSocket connection with the given [code] and [reason],
     * suspending until the close frame has been written to the wire.
     */
    suspend fun close(code: Int = 1000, reason: String = "") {
        // RFC 6455 §7.4: 1004-1006 and 1015 are reserved and never sent; Netty rejects them too.
        require(code in 1000..1003 || code in 1007..1014 || code in 3000..4999) {
            "Invalid WebSocket close code: $code (must be 1000-1003, 1007-1014, 3000-3999, or 4000-4999)"
        }
        if (channel.isActive) {
            val written = writeClose(code, reason)
            // Wait for the write, successful or not. It is never cancelled: it may be a close another
            // path sent, and abandoning a close frame midway would leave the peer without one.
            suspendCancellableCoroutine { cont -> written.addListener { cont.resume(Unit) } }
        }
        closeReason.complete(CloseReason(code, reason))
    }

    /**
     * Writes and flushes a close frame. RFC 6455 allows one per session, so if one was already sent
     * (by [close], the echo of the peer's close, or [closeFromServer]), this sends nothing and
     * returns that earlier write.
     */
    internal fun writeClose(code: Int, reason: String): ChannelFuture {
        // Built first, so a frame that cannot be built never leaves a recorded write that won't complete.
        // 1005 means "no status" and is never sent: answer with a close frame that has no body.
        val frame = if (code == CloseReason.Codes.NO_STATUS_RECEIVED) {
            CloseWebSocketFrame()
        } else {
            CloseWebSocketFrame(code, closeFrameReason(reason))
        }
        val promise = channel.newPromise()
        if (!closeWriteFuture.compareAndSet(null, promise)) {
            frame.release()
            return checkNotNull(closeWriteFuture.get()) { "A recorded close write is never cleared" }
        }
        // Reads paused for backpressure must resume, or the peer's closing reply is never read. Data
        // frames that arrive meanwhile are discarded once the close reason is recorded.
        channel.resumeReads(ChannelReadPauseReason.WEBSOCKET)
        return channel.writeAndFlush(frame, promise)
    }

    /**
     * Ends the session from the server side (idle timeout, backpressure overflow, a frame violation):
     * sends and records [reason], then wakes the route by ending [incoming]. HTTP/1 closes the
     * connection once the frame is written; an RFC 8441 stream is ended by its close observer.
     *
     * Waking the route comes last because in event-loop mode it runs the route's cleanup at once,
     * and that cleanup must find the close frame sent and the reason recorded.
     */
    internal fun closeFromServer(reason: CloseReason) {
        val written = writeClose(reason.code, reason.message)
        closeReason.complete(reason)
        if (channel.attr(ChannelAttributes.HTTP2_WEBSOCKET_TRANSPORT).get() == null) {
            written.addListener(ChannelFutureListener.CLOSE)
        }
        _incoming.close()
    }
}

/**
 * Represents a single WebSocket frame received from the client.
 */
sealed class WebSocketFrame {
    /** A text frame containing a UTF-8 string payload. */
    data class Text(val text: String) : WebSocketFrame() {
        /** Reads the text content of this frame. */
        fun readText(): String = text
    }

    /** A binary frame containing a byte array payload. */
    data class Binary(val data: ByteArray) : WebSocketFrame() {
        override fun equals(other: Any?) = other is Binary && data.contentEquals(other.data)
        override fun hashCode() = data.contentHashCode()
    }

    /** A close frame indicating the client wants to end the connection. */
    data class Close(val code: Int, val reason: String) : WebSocketFrame()

    /** A ping frame for connection keep-alive. */
    data class Ping(val data: ByteArray) : WebSocketFrame() {
        override fun equals(other: Any?) = other is Ping && data.contentEquals(other.data)
        override fun hashCode() = data.contentHashCode()
    }

    /** A pong frame in response to a ping. */
    data class Pong(val data: ByteArray) : WebSocketFrame() {
        override fun equals(other: Any?) = other is Pong && data.contentEquals(other.data)
        override fun hashCode() = data.contentHashCode()
    }
}

/**
 * The reason a WebSocket connection was closed, containing the status [code] and a human-readable [message].
 */
data class CloseReason(val code: Int, val message: String) {
    /** Standard WebSocket close codes. */
    object Codes {
        const val NORMAL = 1000
        const val GOING_AWAY = 1001
        const val PROTOCOL_ERROR = 1002

        /** Reported when the peer's close frame carried no status; never sent in a frame (RFC 6455 §7.4.1). */
        const val NO_STATUS_RECEIVED = 1005
        const val POLICY_VIOLATION = 1008
        const val INTERNAL_ERROR = 1011
    }
}

/** The most reason text a close frame can carry: its payload is at most 125 bytes, 2 of them the code. */
private const val MAX_CLOSE_REASON_BYTES = 123

/**
 * Returns [reason] cut to what a close frame can carry, without splitting a UTF-8 character. A
 * longer reason makes Netty's encoder fail the close write, so the peer would get no close frame.
 */
internal fun closeFrameReason(reason: String): String {
    val bytes = reason.toByteArray(Charsets.UTF_8)
    if (bytes.size <= MAX_CLOSE_REASON_BYTES) return reason
    var end = MAX_CLOSE_REASON_BYTES
    // bytes[end] is the first byte cut off; while it continues a character, cut before that character.
    while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end--
    return String(bytes, 0, end, Charsets.UTF_8)
}
