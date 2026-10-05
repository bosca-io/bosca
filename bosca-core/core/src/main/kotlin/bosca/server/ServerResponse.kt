package bosca.server

import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.DefaultHttpResponse
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpHeaderValues
import io.netty.handler.codec.http.HttpHeaders as NettyHttpHeaders
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpVersion
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Wraps a Netty channel to provide a higher-level API for building and sending HTTP responses.
 *
 * Handles status codes, headers, cookies, and body content, then flushes the complete response
 * through the Netty pipeline when [commit] is called (either explicitly or via the convenience
 * respond methods).
 *
 * Thread-safe: handler coroutines, middleware, and event-loop callbacks may all touch a response,
 * so every mutable field is volatile, atomic, or a concurrent collection, and [commit]'s
 * compare-and-set guarantees a response is written at most once.
 */
class ServerResponse internal constructor(private val ctx: ChannelHandlerContext) {

    @Volatile
    private var _status: HttpStatusCode? = null
    private val _headers = ConcurrentHashMap<String, MutableList<String>>()
    private val commitState = AtomicReference(CommitState.OPEN)
    private val lastWriteFuture = AtomicReference<ChannelFuture?>(null)

    /**
     * When true, response methods emit headers but suppress body content.
     * Set by the server engine for HEAD requests per RFC 7231 §4.3.2.
     */
    @Volatile
    internal var suppressBody: Boolean = false

    private val beforeWriteCallback = AtomicReference<(() -> Unit)?>(null)

    /**
     * Registers a callback that fires once, immediately before the response is written to
     * the network. Called from all response paths (commit, respondBytes, respondStreaming)
     * before the response is committed and before headers and cookies are serialized.
     */
    fun onBeforeWrite(callback: () -> Unit) {
        beforeWriteCallback.set(callback)
    }

    private fun prepareCommit(): Boolean {
        if (!commitState.compareAndSet(CommitState.OPEN, CommitState.PREPARING)) return false

        val callback = beforeWriteCallback.getAndSet(null)
        return try {
            callback?.invoke()
            commitState.set(CommitState.COMMITTED)
            true
        } catch (cause: Throwable) {
            commitState.compareAndSet(CommitState.PREPARING, CommitState.OPEN)
            throw cause
        }
    }

    /** The response cookies that will be serialized as Set-Cookie headers. */
    val cookies = ResponseCookies()

    /** The current status code, or null if not yet set. */
    fun status(): HttpStatusCode? = _status

    /** Sets the HTTP status code for this response. */
    fun status(code: HttpStatusCode) {
        _status = code
    }

    /**
     * Adds a response header. Names are lowercased per RFC 7230 to prevent case-sensitive duplicates.
     * Values are sanitized to strip CR/LF characters, preventing HTTP response splitting attacks.
     */
    fun header(name: String, value: String) {
        val sanitized = sanitizeHeaderValue(value)
        _headers.computeIfAbsent(name.lowercase()) { CopyOnWriteArrayList() }.add(sanitized)
    }

    private fun sanitizeHeaderValue(value: String): String =
        value.replace("\r", "").replace("\n", "").replace("\t", " ")

    /**
     * Copies all accumulated response headers and cookies into the given Netty [headers].
     * Used by SSE and WebSocket upgrade paths that build their own Netty response objects
     * but still need middleware-set headers (CORS, tracing, etc.) applied.
     */
    internal fun applyHeadersTo(headers: NettyHttpHeaders) {
        applyHeadersAndCookies(headers)
    }

    /**
     * Closes the response channel after its most recently submitted write completes. Direct
     * transports that did not write through this response close immediately.
     */
    internal fun closeChannelAfterWrite() {
        val future = lastWriteFuture.get()
        if (future == null) {
            ctx.close()
        } else {
            future.addListener(ChannelFutureListener.CLOSE)
        }
    }

    private fun writeAndFlush(message: Any) {
        lastWriteFuture.set(ctx.writeAndFlush(message))
    }

    private fun applyHeadersAndCookies(headers: NettyHttpHeaders) {
        _headers.forEach { (name, values) ->
            values.forEach { value -> headers.add(name, value) }
        }
        cookies.cookies.forEach { cookie ->
            headers.add(HttpHeaderNames.SET_COOKIE, cookie.toSetCookieString())
        }
    }

    /** Returns whether the response has already been committed (sent to the client). */
    val isCommitted: Boolean get() = commitState.get() != CommitState.OPEN

    /**
     * Marks the response as committed and runs before-write middleware without writing through the
     * normal commit path. Direct response transports must call this before serializing headers.
     */
    fun markCommitted() {
        prepareCommit()
    }

    /**
     * Commits the response by writing the status, headers, cookies, and body to the Netty channel.
     * After this call, no further modifications to the response are possible.
     */
    fun commit(body: ByteBuf = Unpooled.EMPTY_BUFFER) {
        val shouldCommit = try {
            prepareCommit()
        } catch (cause: Throwable) {
            if (body !== Unpooled.EMPTY_BUFFER) body.release()
            throw cause
        }
        if (!shouldCommit) {
            if (body !== Unpooled.EMPTY_BUFFER) body.release()
            return
        }

        val status = _status?.toNetty() ?: HttpResponseStatus.OK
        val contentLength = body.readableBytes()
        val actualBody = if (suppressBody) {
            if (body !== Unpooled.EMPTY_BUFFER) body.release()
            Unpooled.EMPTY_BUFFER
        } else {
            body
        }
        val response = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, actualBody)

        applyHeadersAndCookies(response.headers())

        if (suppressBody) {
            // A HEAD answer declares the length the GET would have; keep one the route set.
            if (!response.headers().contains(HttpHeaderNames.CONTENT_LENGTH)) {
                response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, contentLength)
            }
        } else {
            // The body is complete, so its own length is the truth: a length the route set for a
            // body it never sent (it failed first, and this is its error response) must not stand.
            response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, contentLength)
        }

        writeAndFlush(response)
    }

    /**
     * Responds with the given status code and byte content, specifying the content type.
     * No-op if the response has already been committed by another coroutine.
     */
    fun respondBytes(bytes: ByteArray, contentType: ContentType, statusCode: HttpStatusCode = HttpStatusCode.OK) {
        if (!prepareCommit()) return

        _status = statusCode
        val contentTypeValue = sanitizeHeaderValue(contentType.toString())

        val status = _status?.toNetty() ?: HttpResponseStatus.OK
        if (suppressBody) {
            val response = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, Unpooled.EMPTY_BUFFER)
            applyHeadersAndCookies(response.headers())
            response.headers().add(HttpHeaderNames.CONTENT_TYPE, contentTypeValue)
            if (!response.headers().contains(HttpHeaderNames.CONTENT_LENGTH)) {
                response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, bytes.size)
            }
            writeAndFlush(response)
            return
        }

        val buf = ctx.alloc().buffer(bytes.size)
        try {
            buf.writeBytes(bytes)
            val response = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, buf)
            applyHeadersAndCookies(response.headers())
            response.headers().add(HttpHeaderNames.CONTENT_TYPE, contentTypeValue)
            // The body is complete, so its own length is the truth (see commit).
            response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, buf.readableBytes())
            writeAndFlush(response)
        } catch (e: Exception) {
            buf.release()
            throw e
        }
    }

    /**
     * Responds with the given status code and string content, specifying the content type.
     */
    fun respondText(text: String, contentType: ContentType = ContentType.Text.Plain, statusCode: HttpStatusCode = HttpStatusCode.OK) {
        respondBytes(text.toByteArray(StandardCharsets.UTF_8), contentType, statusCode)
    }

    fun respond(statusCode: HttpStatusCode = HttpStatusCode.OK) {
        if (isCommitted) return
        _status = statusCode
        commit()
    }

    /**
     * Responds by streaming content to the client with coroutine-native backpressure.
     * The provided [block] receives a [StreamingResponse] whose suspend [write][StreamingResponse.write]
     * method forwards bytes to the Netty channel, suspending the coroutine when Netty's
     * outbound buffer is full rather than blocking a thread or accumulating data in memory.
     *
     * If a Content-Length header has already been set the response uses fixed-length encoding;
     * otherwise chunked transfer encoding is used.
     */
    suspend fun respondStreaming(
        contentType: ContentType,
        statusCode: HttpStatusCode = HttpStatusCode.OK,
        block: suspend (StreamingResponse) -> Unit,
    ) {
        respondStreamingWithTimeout(contentType, statusCode, STREAMING_TIMEOUT_MS, block)
    }

    /**
     * Like [respondStreaming], with [timeLimit] in place of the default 5-minute limit. `null` streams
     * until [block] returns, bounded only by the connection's idle timeout: for exchanges whose length
     * legitimately exceeds the default, such as git clones and pushes.
     *
     * @throws IllegalArgumentException if [timeLimit] is zero or negative.
     */
    suspend fun respondStreaming(
        contentType: ContentType,
        statusCode: HttpStatusCode,
        timeLimit: Duration?,
        block: suspend (StreamingResponse) -> Unit,
    ) {
        require(timeLimit == null || timeLimit.isPositive()) { "timeLimit must be positive, or null for no limit: $timeLimit" }
        // Whole milliseconds, at least one, so a limit under a millisecond still lets the stream run.
        val limitMillis = timeLimit?.let { maxOf(1L, it.inWholeMilliseconds) }
        respondStreamingWithTimeout(contentType, statusCode, limitMillis, block)
    }

    /**
     * Responds by streaming content, ending the response with [StreamingTimeLimitException] once it
     * has streamed for [timeoutMs] (no limit when null) so slow clients cannot hold coroutines forever.
     */
    internal suspend fun respondStreamingWithTimeout(
        contentType: ContentType,
        statusCode: HttpStatusCode = HttpStatusCode.OK,
        timeoutMs: Long? = STREAMING_TIMEOUT_MS,
        block: suspend (StreamingResponse) -> Unit,
    ) {
        if (!prepareCommit()) return

        status(statusCode)
        val contentTypeValue = sanitizeHeaderValue(contentType.toString())

        if (suppressBody) {
            val response = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, _status?.toNetty() ?: HttpResponseStatus.OK, Unpooled.EMPTY_BUFFER)
            applyHeadersAndCookies(response.headers())
            response.headers().add(HttpHeaderNames.CONTENT_TYPE, contentTypeValue)
            writeAndFlush(response)
            return
        }

        val response = DefaultHttpResponse(HttpVersion.HTTP_1_1, _status?.toNetty() ?: HttpResponseStatus.OK)

        applyHeadersAndCookies(response.headers())
        response.headers().add(HttpHeaderNames.CONTENT_TYPE, contentTypeValue)

        if (!response.headers().contains(HttpHeaderNames.CONTENT_LENGTH)) {
            response.headers().set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED)
        }

        val stream = StreamingResponse(ctx)
        var error: Throwable? = null
        try {
            writeAndFlush(response)
            if (timeoutMs == null) {
                block(stream)
            } else {
                // Only this limit's own expiry becomes StreamingTimeLimitException; a timeout inside
                // [block] propagates unchanged and is reported as the failure it is.
                val finished = withTimeoutOrNull(timeoutMs.milliseconds) {
                    block(stream)
                    true
                }
                if (finished == null) throw StreamingTimeLimitException("Streaming response reached its $timeoutMs ms time limit")
            }
        } catch (e: Throwable) {
            error = e
            throw e
        } finally {
            if (error != null) {
                stream.discard()
                ctx.close()
            } else {
                // Bytes still buffered by the stream travel in the final message.
                writeAndFlush(stream.lastContent())
            }
        }
    }

    /**
     * Responds with a 302 Found redirect to the given [url].
     * If [permanent] is true, uses 301 Moved Permanently instead.
     *
     * Only relative URLs and URLs with http(s) schemes are allowed.
     * URLs with other schemes (javascript:, data:, etc.) are rejected to prevent open redirect attacks.
     */
    fun respondRedirect(url: String, permanent: Boolean = false) {
        if (isCommitted) return
        // Validate that redirect URLs use safe schemes to prevent open redirect via javascript: or data: URIs
        if (url.contains(":") && !url.startsWith("/")) {
            val scheme = url.substringBefore(":").lowercase()
            require(scheme == "http" || scheme == "https") {
                "Redirect URL must use http or https scheme, got: $scheme"
            }
        }
        status(if (permanent) HttpStatusCode.MovedPermanently else HttpStatusCode.Found)
        header(HttpHeaders.Location, url)
        commit()
    }

    /** Returns the underlying Netty [ChannelHandlerContext] for advanced use cases. */
    internal fun channelContext(): ChannelHandlerContext = ctx

    companion object {
        /** Default timeout (5 minutes) for streaming responses to prevent slow clients from holding coroutines indefinitely. */
        private const val STREAMING_TIMEOUT_MS = 300_000L
    }

    private enum class CommitState {
        OPEN,
        PREPARING,
        COMMITTED,
    }
}
