package bosca.server.sse

import bosca.server.ContentType
import bosca.server.HttpStatusCode
import bosca.server.ServerCall
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.DefaultHttpContent
import io.netty.handler.codec.http.DefaultHttpResponse
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpVersion
import io.netty.handler.codec.http.LastHttpContent
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration.Companion.seconds

/**
 * Manages a Server-Sent Events (SSE) connection, allowing the server to push events
 * to the client over a long-lived HTTP connection using chunked transfer encoding.
 *
 * Sends events formatted according to the SSE specification with event type, data,
 * and optional ID fields.
 *
 * Call [send] and [close] from one coroutine at a time: the first send writes the response
 * headers, and a concurrent send could queue its event ahead of them.
 */
class ServerSSESession(
    val call: ServerCall,
    private val ctx: ChannelHandlerContext
) {
    private val headersSent = AtomicBoolean(false)
    private val headersPrepared = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    private fun ensureHeadersSent() {
        if (!headersSent.compareAndSet(false, true)) return

        // Prepare the shared response before adding transport framing so a failed callback can
        // leave a clean response for the HTTP error path.
        try {
            call.response.markCommitted()
        } catch (cause: Throwable) {
            headersSent.set(false)
            throw cause
        }
        if (headersPrepared.compareAndSet(false, true)) {
            call.response.status(HttpStatusCode.OK)
            call.response.header(HttpHeaderNames.CONTENT_TYPE.toString(), ContentType.Text.EventStream.toString())
            call.response.header(HttpHeaderNames.CACHE_CONTROL.toString(), "no-cache")
            call.response.header(HttpHeaderNames.CONNECTION.toString(), "keep-alive")
            call.response.header(HttpHeaderNames.TRANSFER_ENCODING.toString(), "chunked")
        }

        val response = DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK)
        // Apply all headers set by middleware (CORS, tracing, etc.) and above
        call.response.applyHeadersTo(response.headers())
        ctx.writeAndFlush(response)
    }

    /**
     * Sends an SSE event with the given [data] payload and optional [event] type and [id].
     * Suspends until the write completes and throws on failure (e.g., client disconnect).
     */
    suspend fun send(data: String, event: String? = null, id: String? = null) {
        ensureHeadersSent()
        val sb = StringBuilder()
        // Strip CRLF sequences first, then individual CR and LF to prevent SSE field injection
        event?.let { sb.append("event: ${it.replace("\r\n", "").replace("\r", "").replace("\n", "")}\n") }
        id?.let { sb.append("id: ${it.replace("\r\n", "").replace("\r", "").replace("\n", "")}\n") }
        // Each data line must be prefixed with "data:" — empty lines within the data
        // must still carry the prefix to prevent premature event termination.
        // A bare empty line (\n\n) in SSE terminates the event and could allow injection.
        data.lines().forEach { line -> sb.append("data:${if (line.isNotEmpty()) " $line" else ""}\n") }
        sb.append("\n")
        val bytes = sb.toString().toByteArray(StandardCharsets.UTF_8)
        val buf = ctx.alloc().buffer(bytes.size)
        try {
            buf.writeBytes(bytes)
        } catch (e: Exception) {
            buf.release()
            throw e
        }
        val future = ctx.writeAndFlush(DefaultHttpContent(buf))
        val result = withTimeoutOrNull(WRITE_TIMEOUT) {
            suspendCancellableCoroutine { cont ->
                future.addListener { f ->
                    if (f.isSuccess) {
                        cont.resume(true)
                    } else {
                        cont.resumeWithException(f.cause() ?: IOException("SSE write failed"))
                    }
                }
                cont.invokeOnCancellation {
                    future.cancel(false)
                }
            }
        }
        if (result == null) {
            // Close the channel to prevent partially-written buffer corruption
            ctx.close()
            throw IOException("SSE write timeout")
        }
    }

    /**
     * Sends an SSE event using the structured [ServerSentEvent] object.
     */
    suspend fun send(event: ServerSentEvent) {
        send(data = event.data, event = event.event, id = event.id)
    }

    /** Closes the SSE connection, properly terminating the chunked transfer encoding. */
    suspend fun close() {
        if (!closed.compareAndSet(false, true)) return
        if (headersSent.get()) {
            val future = ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT)
            try {
                // Bounded like every event write: a client that stopped reading would otherwise hold
                // this close (which runs in non-cancellable cleanup) and its stream open forever.
                withTimeoutOrNull(WRITE_TIMEOUT) {
                    suspendCancellableCoroutine { cont ->
                        future.addListener { cont.resume(Unit) }
                        cont.invokeOnCancellation { future.cancel(false) }
                    }
                }
            } finally {
                ctx.close()
            }
        } else if (call.response.isCommitted) {
            call.response.closeChannelAfterWrite()
        } else {
            ctx.close()
        }
    }

    private companion object {
        /** A client that cannot accept one event within this window is disconnected. */
        val WRITE_TIMEOUT = 30.seconds
    }
}

/**
 * Represents a structured Server-Sent Event with optional event type, ID, and data payload.
 */
data class ServerSentEvent(
    val data: String,
    val event: String? = null,
    val id: String? = null
)
