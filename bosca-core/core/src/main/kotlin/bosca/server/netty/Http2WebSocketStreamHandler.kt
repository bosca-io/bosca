package bosca.server.netty

import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelDuplexHandler
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelPromise
import io.netty.handler.codec.http.FullHttpResponse
import io.netty.handler.codec.http.HttpContent
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpHeaders
import io.netty.handler.codec.http.HttpResponse
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.LastHttpContent
import io.netty.handler.codec.http2.DefaultHttp2DataFrame
import io.netty.handler.codec.http2.DefaultHttp2Headers
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame
import io.netty.handler.codec.http2.Http2DataFrame
import io.netty.handler.codec.http2.Http2Exception
import io.netty.handler.codec.http2.Http2Headers
import io.netty.handler.codec.http2.Http2HeadersFrame
import io.netty.handler.codec.http2.HttpConversionUtil
import io.netty.util.ReferenceCountUtil
import io.netty.util.concurrent.Future
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit

/**
 * Bridges one RFC 8441 extended CONNECT stream to Netty's RFC 6455 frame codecs.
 *
 * During the handshake, this child stream stops replenishing its receive window and retains only
 * DATA frames already in flight until the application accepts the connection. Once open, inbound
 * DATA payloads become raw WebSocket bytes and outbound encoded bytes become DATA frames. The
 * parent HTTP/2 connection and its other streams remain active throughout.
 */
internal class Http2WebSocketStreamHandler(
    private val idleTimeoutSeconds: Long,
) : ChannelDuplexHandler() {

    private enum class State {
        AWAITING_HEADERS,
        HANDSHAKING,
        OPEN,
        REJECTING,
        REJECTED,
    }

    // Set on the event loop in handlerAdded, before this handler is published to other threads
    // through ChannelAttributes.HTTP2_WEBSOCKET_TRANSPORT.
    private lateinit var context: ChannelHandlerContext

    // Event loop only. Methods called from application coroutines (accept, reject,
    // failHandshake, endStream) hop onto the event loop before touching this state.
    private val pendingReads = ArrayDeque<Http2DataFrame>()
    private var state = State.AWAITING_HEADERS
    private var localEndStream = false
    private var remoteEndStream = false

    override fun handlerAdded(ctx: ChannelHandlerContext) {
        context = ctx
        ctx.channel().attr(ChannelAttributes.HTTP2_WEBSOCKET_TRANSPORT).set(this)
    }

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        when (state) {
            State.AWAITING_HEADERS -> readInitialHeaders(ctx, msg)
            State.HANDSHAKING -> bufferDuringHandshake(ctx, msg)
            State.OPEN -> readOpenStream(ctx, msg)
            State.REJECTING,
            State.REJECTED,
            -> readRejectedStream(ctx, msg)
        }
    }

    private fun readInitialHeaders(ctx: ChannelHandlerContext, msg: Any) {
        if (msg !is Http2HeadersFrame) {
            ReferenceCountUtil.release(msg)
            reject(HttpResponseStatus.BAD_REQUEST)
            return
        }

        try {
            val rejection = ExtendedConnectValidation.rejection(msg)
            if (rejection != null) {
                reject(rejection.status, rejection.headers)
                return
            }
            val request = try {
                ExtendedConnectValidation.toHttpRequest(msg)
            } catch (_: Http2Exception) {
                reject(HttpResponseStatus.BAD_REQUEST)
                return
            }
            state = State.HANDSHAKING
            ctx.channel().pauseReads(ChannelReadPauseReason.HTTP2_WEBSOCKET_HANDSHAKE)
            ctx.fireChannelRead(request)
        } finally {
            ReferenceCountUtil.release(msg)
        }
    }

    private fun bufferDuringHandshake(ctx: ChannelHandlerContext, msg: Any) {
        when (msg) {
            is Http2DataFrame -> pendingReads.addLast(msg.retain())
            is Http2HeadersFrame -> {
                ReferenceCountUtil.release(msg)
                reject(HttpResponseStatus.BAD_REQUEST)
                return
            }
            else -> ctx.fireChannelRead(ReferenceCountUtil.retain(msg))
        }
        ReferenceCountUtil.release(msg)
    }

    private fun readOpenStream(ctx: ChannelHandlerContext, msg: Any) {
        when (msg) {
            is Http2DataFrame -> {
                val endStream = msg.isEndStream
                val content = msg.content().retain()
                ReferenceCountUtil.release(msg)
                if (content.isReadable) {
                    ctx.fireChannelRead(content)
                } else {
                    content.release()
                }
                if (endStream) {
                    completeRemoteSide(ctx)
                }
            }
            is Http2HeadersFrame -> {
                val endStream = msg.isEndStream
                ReferenceCountUtil.release(msg)
                if (endStream) {
                    completeRemoteSide(ctx)
                } else {
                    ctx.fireExceptionCaught(IllegalStateException("Unexpected HTTP/2 headers on an open WebSocket stream"))
                }
            }
            else -> ctx.fireChannelRead(msg)
        }
    }

    private fun readRejectedStream(ctx: ChannelHandlerContext, msg: Any) {
        val endStream = when (msg) {
            is Http2DataFrame -> msg.isEndStream
            is Http2HeadersFrame -> msg.isEndStream
            else -> false
        }
        ReferenceCountUtil.release(msg)
        if (endStream) {
            remoteEndStream = true
            if (localEndStream) ctx.close()
        }
    }

    override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
        when {
            state == State.HANDSHAKING && msg is FullHttpResponse -> writeFullRejection(ctx, msg, promise)
            state == State.HANDSHAKING && msg is HttpResponse -> writeRejectionHeaders(ctx, msg, promise)
            state == State.REJECTING && msg is HttpContent -> writeRejectionContent(ctx, msg, promise)
            state == State.OPEN && msg is ByteBuf -> {
                if (localEndStream) {
                    ReferenceCountUtil.release(msg)
                    promise.tryFailure(IllegalStateException("HTTP/2 WebSocket stream is already half-closed"))
                } else {
                    ctx.write(DefaultHttp2DataFrame(msg, false), promise)
                }
            }
            else -> ctx.write(msg, promise)
        }
    }

    private fun writeFullRejection(ctx: ChannelHandlerContext, response: FullHttpResponse, promise: ChannelPromise) {
        state = State.REJECTED
        releasePendingReads()
        val content = response.content().retain()
        val hasContent = content.isReadable
        try {
            val headers = HttpConversionUtil.toHttp2Headers(response, true)
            ctx.write(DefaultHttp2HeadersFrame(headers, !hasContent), if (hasContent) ctx.voidPromise() else promise)
            if (hasContent) {
                ctx.write(DefaultHttp2DataFrame(content, true), promise)
            } else {
                content.release()
            }
            localEndStream = true
            promise.addListener { future -> completeRejection(future) }
        } catch (cause: Throwable) {
            content.release()
            promise.tryFailure(cause)
            ctx.fireExceptionCaught(cause)
        } finally {
            response.release()
        }
    }

    private fun writeRejectionHeaders(ctx: ChannelHandlerContext, response: HttpResponse, promise: ChannelPromise) {
        state = State.REJECTING
        releasePendingReads()
        try {
            val headers = HttpConversionUtil.toHttp2Headers(response, true)
            ctx.write(DefaultHttp2HeadersFrame(headers, false), promise)
        } catch (cause: Throwable) {
            ReferenceCountUtil.release(response)
            promise.tryFailure(cause)
            ctx.fireExceptionCaught(cause)
        }
    }

    private fun writeRejectionContent(ctx: ChannelHandlerContext, content: HttpContent, promise: ChannelPromise) {
        val last = content is LastHttpContent
        val data = content.content().retain()
        ReferenceCountUtil.release(content)
        if (last) {
            state = State.REJECTED
            localEndStream = true
        }
        ctx.write(DefaultHttp2DataFrame(data, last), promise)
        if (last) promise.addListener { future -> completeRejection(future) }
    }

    /** Accepts the extended CONNECT and switches this stream to WebSocket framing. */
    fun accept(responseHeaders: HttpHeaders, selectedSubprotocol: String?): ChannelFuture {
        val promise = context.newPromise()
        onEventLoop {
            if (state != State.HANDSHAKING) {
                promise.tryFailure(IllegalStateException("HTTP/2 WebSocket handshake is no longer pending"))
                return@onEventLoop
            }

            installWebSocketCodecs()
            state = State.OPEN
            // Its own idle handler closes it gracefully; the connection's must not drop it first.
            Http2LongLivedStreams.track(context.channel())
            val headers = DefaultHttp2Headers().status(HttpResponseStatus.OK.codeAsText())
            copyResponseHeaders(responseHeaders, headers)
            selectedSubprotocol?.let { headers.set(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL, it) }
            context.writeAndFlush(DefaultHttp2HeadersFrame(headers, false), promise)
            promise.addListener { future ->
                if (future.isSuccess) {
                    replayPendingReads()
                    context.channel().resumeReads(ChannelReadPauseReason.HTTP2_WEBSOCKET_HANDSHAKE)
                } else {
                    releasePendingReads()
                    context.close()
                }
            }
        }
        return promise
    }

    /** Rejects the extended CONNECT with an HTTP/2 response and closes only this stream. */
    fun reject(status: HttpResponseStatus, responseHeaders: HttpHeaders? = null): ChannelFuture {
        val promise = context.newPromise()
        onEventLoop {
            if (state != State.AWAITING_HEADERS && state != State.HANDSHAKING) {
                promise.tryFailure(IllegalStateException("HTTP/2 WebSocket handshake is no longer pending"))
                return@onEventLoop
            }
            writeTerminalRejection(status, responseHeaders, promise)
        }
        return promise
    }

    /**
     * Terminates a failed application handshake even when response commitment already began.
     *
     * Application response callbacks run after the committed flag is set, so a callback failure
     * cannot safely be retried through [reject]. This method sends a fallback rejection while the
     * handshake is pending, treats an existing rejection as complete, and closes any partially
     * opened or streamed response.
     */
    fun failHandshake(status: HttpResponseStatus, responseHeaders: HttpHeaders? = null): ChannelFuture {
        val promise = context.newPromise()
        onEventLoop {
            when (state) {
                State.AWAITING_HEADERS,
                State.HANDSHAKING,
                -> writeTerminalRejection(status, responseHeaders, promise)
                State.REJECTED -> promise.trySuccess()
                State.REJECTING,
                State.OPEN,
                -> closeFailedHandshake(promise)
            }
        }
        return promise
    }

    private fun writeTerminalRejection(
        status: HttpResponseStatus,
        responseHeaders: HttpHeaders?,
        promise: ChannelPromise,
    ) {
        state = State.REJECTED
        localEndStream = true
        releasePendingReads()
        try {
            val headers = DefaultHttp2Headers().status(status.codeAsText())
            responseHeaders?.let { copyResponseHeaders(it, headers) }
            context.writeAndFlush(DefaultHttp2HeadersFrame(headers, true), promise)
            promise.addListener { future -> completeRejection(future) }
        } catch (cause: Throwable) {
            promise.tryFailure(cause)
            context.close()
        }
    }

    private fun closeFailedHandshake(promise: ChannelPromise) {
        releasePendingReads()
        if (!context.channel().isOpen) {
            promise.trySuccess()
            return
        }
        context.close(promise)
    }

    /** Half-closes the local side after the final encoded WebSocket frame has been written. */
    fun endStream() {
        onEventLoop {
            if (state != State.OPEN || localEndStream) return@onEventLoop
            localEndStream = true
            val future = context.writeAndFlush(DefaultHttp2DataFrame(Unpooled.EMPTY_BUFFER, true))
            future.addListener {
                if (remoteEndStream) {
                    context.close()
                } else {
                    scheduleForcedClose()
                }
            }
        }
    }

    private fun completeRemoteSide(ctx: ChannelHandlerContext) {
        remoteEndStream = true
        if (localEndStream) {
            ctx.close()
        } else {
            endStream()
        }
    }

    private fun installWebSocketCodecs() {
        context.pipeline().installHttp2WebSocketCodecs(context.name(), this, idleTimeoutSeconds)
    }

    private fun replayPendingReads() {
        while (pendingReads.isNotEmpty() && state == State.OPEN) {
            readOpenStream(context, pendingReads.removeFirst())
        }
    }

    private fun releasePendingReads() {
        while (pendingReads.isNotEmpty()) {
            val pending = pendingReads.removeFirst()
            if (pending.isEndStream) {
                remoteEndStream = true
            }
            ReferenceCountUtil.release(pending)
        }
    }

    private fun completeRejection(future: Future<*>) {
        if (!future.isSuccess) {
            context.close()
            return
        }
        if (!context.channel().isActive) return
        if (remoteEndStream) {
            context.close()
        } else {
            context.channel().resumeReads(ChannelReadPauseReason.HTTP2_WEBSOCKET_HANDSHAKE)
            scheduleForcedClose()
        }
    }

    private fun copyResponseHeaders(source: HttpHeaders, target: Http2Headers) {
        val sanitized = source.copy()
        sanitized.names()
            .filter { it.startsWith(':') }
            .forEach(sanitized::remove)
        HttpConversionUtil.toHttp2Headers(sanitized, target)
    }

    private fun scheduleForcedClose() {
        if (!context.channel().isActive || remoteEndStream) return
        context.executor().schedule(
            {
                if (context.channel().isActive) context.close()
            },
            CLOSE_GRACE_PERIOD_SECONDS,
            TimeUnit.SECONDS,
        )
    }

    private fun onEventLoop(block: () -> Unit) {
        if (context.executor().inEventLoop()) {
            block()
        } else {
            context.executor().execute(block)
        }
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        releasePendingReads()
        ctx.fireChannelInactive()
    }

    override fun handlerRemoved(ctx: ChannelHandlerContext) {
        releasePendingReads()
        ctx.channel().attr(ChannelAttributes.HTTP2_WEBSOCKET_TRANSPORT).compareAndSet(this, null)
    }

    private companion object {
        /** How long a half-closed stream waits for the peer's END_STREAM before it is closed. */
        const val CLOSE_GRACE_PERIOD_SECONDS = 5L
    }
}
