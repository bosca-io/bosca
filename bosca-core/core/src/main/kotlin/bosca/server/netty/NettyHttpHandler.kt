package bosca.server.netty

import bosca.server.BoscaApplication
import bosca.server.RequestBody
import bosca.server.websocket.CloseReason
import io.netty.channel.ChannelHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.handler.codec.TooLongFrameException
import io.netty.handler.codec.http.HttpContent
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpObject
import io.netty.handler.codec.http.HttpRequest
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.LastHttpContent
import io.netty.handler.codec.http.TooLongHttpHeaderException
import io.netty.handler.codec.http.TooLongHttpLineException
import io.netty.handler.codec.http.websocketx.CorruptedWebSocketFrameException
import io.netty.handler.codec.http.websocketx.WebSocketCloseStatus
import io.netty.handler.codec.http.websocketx.WebSocketFrame
import io.netty.handler.timeout.IdleStateEvent
import io.netty.util.ReferenceCountUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import org.slf4j.LoggerFactory
import java.io.IOException

/**
 * The pipeline entry point for HTTP traffic: owns per-channel state and hands each inbound
 * message to the component that serves it.
 *
 * - A request head starts a streaming [RequestBody] (when the request has one) and goes to
 *   [HttpRequestDispatcher], which selects ordinary routing, SSE, or a WebSocket upgrade.
 * - Body chunks feed the current [RequestBody], so route handlers can consume arbitrarily large
 *   bodies without the server buffering them.
 * - WebSocket frames go to [WebSocketFrameIntake].
 *
 * The handler is shared by every connection and HTTP/2 stream; all connection state lives in
 * [ChannelAttributes].
 */
@ChannelHandler.Sharable
class NettyHttpHandler @JvmOverloads constructor(
    application: BoscaApplication,
    private val scope: CoroutineScope,
    private val maxRequestSize: Long,
    /** Where this handler's request coroutines run; [RequestDispatcherMode.POOL] uses [scope]'s dispatcher. */
    private val requestDispatcher: RequestDispatcherMode = RequestDispatcherMode.POOL,
) : ChannelInboundHandlerAdapter() {

    private val errors = HttpErrorResponder(application, scope)
    private val dispatcher = CallLifecycle(application).let { lifecycle ->
        HttpRequestDispatcher(application, lifecycle, errors, WebSocketUpgrades(application, scope, lifecycle, errors))
    }

    override fun channelActive(ctx: ChannelHandlerContext) {
        val channel = ctx.channel()
        // Each request coroutine handles its own failures. One that still escapes (an Error, or a
        // throw from cleanup) must not cancel the connection's scope and strand later requests on
        // it: the supervisor keeps the scope alive, and the handler reports the failure and closes
        // the connection, whose response state is now unknown.
        val escapedFailure = CoroutineExceptionHandler { _, failure ->
            log.error("A request coroutine failed outside its error handling; closing the connection", failure)
            errors.report(failure)
            ctx.close()
        }
        val channelContext = scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]) + escapedFailure
        channel.attr(ChannelAttributes.SCOPE).set(
            CoroutineScope(
                when (requestDispatcher) {
                    // HTTP/2 stream channels share their parent connection's event loop.
                    RequestDispatcherMode.EVENT_LOOP -> channelContext + ChannelExecutorDispatcher(ctx.executor())
                    RequestDispatcherMode.POOL -> channelContext
                },
            ),
        )
        channel.attr(ChannelAttributes.REMOTE_ADDRESS).set(channel.resolveRemoteIpAddress())
        super.channelActive(ctx)
    }

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        try {
            if (msg is HttpObject && msg.decoderResult().isFailure) {
                rejectUndecodable(ctx, msg)
                return
            }
            when (msg) {
                is HttpRequest -> {
                    val body = if (hasRequestBody(ctx, msg)) RequestBody(ctx, maxRequestSize) else null
                    ctx.channel().attr(ChannelAttributes.CURRENT_BODY).set(body)
                    // A FullHttpRequest carries its body: queue it before dispatch so the handler
                    // cannot discard the body before the data arrives.
                    if (msg is HttpContent) body?.addContent(msg)
                    dispatcher.dispatch(ctx, msg, body)
                }

                is HttpContent -> {
                    ctx.channel().attr(ChannelAttributes.CURRENT_BODY).get()?.addContent(msg)
                    if (msg is LastHttpContent) {
                        ctx.channel().attr(ChannelAttributes.CURRENT_BODY).set(null)
                    }
                }

                is WebSocketFrame -> WebSocketFrameIntake.deliver(ctx, msg)
            }
        } catch (e: Exception) {
            log.error("Unexpected error in channelRead", e)
            errors.respond(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, e)
        } finally {
            ReferenceCountUtil.release(msg)
        }
    }

    /**
     * Handles a request Netty's HTTP/1 decoder could not decode: headers or request line over the
     * limit, or a malformed head or chunk. Netty then marks the message failed (a failed head may be
     * only partly parsed; a failed chunk arrives as an empty last chunk) and discards everything
     * else the connection sends, so the connection is closed either way.
     *
     * - A failed head never reaches a route: it is answered 431, 414 or 400.
     * - A failed body chunk fails the body, so the route reading it sees an error rather than a
     *   clean end, which would accept a truncated upload. The route has already started, so the
     *   connection is closed instead of answered.
     */
    private fun rejectUndecodable(ctx: ChannelHandlerContext, msg: HttpObject) {
        val cause = msg.decoderResult().cause()
        log.debug("Rejecting a request that could not be decoded: {}", cause?.message)
        if (msg is HttpRequest) {
            val status = when (cause) {
                is TooLongHttpHeaderException -> HttpResponseStatus.REQUEST_HEADER_FIELDS_TOO_LARGE
                is TooLongHttpLineException -> HttpResponseStatus.REQUEST_URI_TOO_LONG
                else -> HttpResponseStatus.BAD_REQUEST
            }
            errors.respond(ctx, status, closeConnection = true)
        } else {
            ctx.channel().attr(ChannelAttributes.CURRENT_BODY).getAndSet(null)
                ?.discard(IOException("The request body could not be decoded", cause))
            ctx.close()
        }
    }

    /**
     * Determines whether the request carries a body from the HTTP/2 stream state or the HTTP/1
     * Content-Length and Transfer-Encoding headers, so bodyless requests allocate no [RequestBody].
     */
    private fun hasRequestBody(ctx: ChannelHandlerContext, request: HttpRequest): Boolean {
        if (ctx.channel().attr(ChannelAttributes.HTTP2_REQUEST_BODY_EXPECTED).getAndSet(null) == true) return true
        val contentLength = request.headers().get(HttpHeaderNames.CONTENT_LENGTH)?.toLongOrNull()
        if (contentLength != null && contentLength > 0) return true
        return request.headers().get(HttpHeaderNames.TRANSFER_ENCODING) != null
    }

    /**
     * Applies the idle policy. An idle WebSocket is closed with `1001 Going Away`. An idle HTTP
     * connection is closed unless it carries a long-lived stream that manages its own idleness: an
     * SSE subscription, or an HTTP/2 stream marked by [Http2LongLivedStreams].
     */
    override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
        if (evt !is IdleStateEvent) {
            super.userEventTriggered(ctx, evt)
            return
        }
        val channel = ctx.channel()
        val wsSession = channel.attr(ChannelAttributes.WS_SESSION).get()
        if (wsSession != null) {
            // Already closing and idle again: the peer is not reading our close frame, so the write
            // will never finish. Close now instead of waiting on it.
            if (wsSession.closeWriteFuture.get() != null) {
                log.debug("Closing a WebSocket whose peer never read its close frame")
                ctx.close()
                return
            }
            log.debug("Closing idle WebSocket connection")
            wsSession.closeFromServer(CloseReason(CloseReason.Codes.GOING_AWAY, "Idle timeout"))
        } else if (channel.attr(ChannelAttributes.SSE_SESSION).get() == null && !Http2LongLivedStreams.hasActive(channel)) {
            ctx.close()
        }
    }

    override fun channelWritabilityChanged(ctx: ChannelHandlerContext) {
        WebSocketFrameIntake.answerDeferredPing(ctx)
        super.channelWritabilityChanged(ctx)
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        val channel = ctx.channel()
        // Record why a WebSocket session ended before waking it. In event-loop mode, closing its
        // frame channel or cancelling its scope runs the route and its cleanup immediately, and
        // that cleanup would otherwise record a normal close for a connection that was dropped.
        channel.attr(ChannelAttributes.WS_SESSION).getAndSet(null)?.let { session ->
            session.closeReason.complete(CloseReason(CloseReason.Codes.GOING_AWAY, "Channel closed"))
            session._incoming.close()
        }
        // Cancel every coroutine tied to this connection or stream.
        channel.attr(ChannelAttributes.SCOPE).getAndSet(null)
            ?.coroutineContext
            ?.get(Job)
            ?.cancel(CancellationException("Channel closed"))
        channel.attr(ChannelAttributes.SSE_SESSION).set(null)
        channel.attr(ChannelAttributes.REMOTE_ADDRESS).set(null)
        channel.attr(ChannelAttributes.CURRENT_BODY).getAndSet(null)?.discard(IOException("Channel closed"))
        super.channelInactive(ctx)
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        if (closeWebSocketAfterFrameViolation(ctx, cause)) return
        if (cause is CancellationException) {
            log.debug("Channel cancelled", cause)
        } else {
            log.error("Channel exception", cause)
        }
        // The reason is recorded before the session is woken, as in channelInactive.
        ctx.channel().attr(ChannelAttributes.WS_SESSION).getAndSet(null)?.let { session ->
            session.closeReason.complete(CloseReason(CloseReason.Codes.PROTOCOL_ERROR, "Channel exception"))
            session._incoming.close()
        }
        ctx.channel().attr(ChannelAttributes.CURRENT_BODY).getAndSet(null)?.discard(cause)
        ctx.close()
    }

    /**
     * Closes a WebSocket whose peer broke the frame rules with the violation's own close status,
     * instead of treating it as a generic channel error. These are client errors, logged at info.
     *
     * - A malformed frame ([CorruptedWebSocketFrameException]) from Netty's decoder or the UTF-8
     *   validator, both configured to only raise it: 1002, 1007, ...
     * - A message over [MAX_WEBSOCKET_FRAME_SIZE] ([TooLongFrameException] from the aggregator):
     *   1009 Message Too Big.
     *
     * Returns false when [cause] is not such a violation on a WebSocket.
     */
    private fun closeWebSocketAfterFrameViolation(ctx: ChannelHandlerContext, cause: Throwable): Boolean {
        val session = ctx.channel().attr(ChannelAttributes.WS_SESSION).get() ?: return false
        val status = when (cause) {
            is CorruptedWebSocketFrameException -> cause.closeStatus()
            is TooLongFrameException -> WebSocketCloseStatus.MESSAGE_TOO_BIG
            else -> return false
        }
        // Already closing: frames still decoded after our close change nothing, and logging each
        // would let a client write a log line per bad frame.
        if (session.closeWriteFuture.get() != null) return true
        val message = cause.message ?: status.reasonText()
        log.info("Closing a WebSocket after a frame violation ({}): {}", status.code(), message)
        session.closeFromServer(CloseReason(status.code(), message))
        return true
    }

    companion object {
        private val log = LoggerFactory.getLogger(NettyHttpHandler::class.java)

        /** Maximum size of a single (aggregated) WebSocket message: 1 MB. */
        internal const val MAX_WEBSOCKET_FRAME_SIZE = 1024 * 1024
    }
}
