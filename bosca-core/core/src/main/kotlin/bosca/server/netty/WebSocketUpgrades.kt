package bosca.server.netty

import bosca.server.BoscaApplication
import bosca.server.ContentType
import bosca.server.HttpHeaders
import bosca.server.HttpStatusCode
import bosca.server.Parameters
import bosca.server.RequestBody
import bosca.server.ServerCall
import bosca.server.ServerRequest
import bosca.server.ServerResponse
import bosca.server.routing.WebSocketRouteEntry
import bosca.server.websocket.CloseReason
import bosca.server.websocket.WebSocketSession
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.DefaultFullHttpRequest
import io.netty.handler.codec.http.DefaultHttpHeaders
import io.netty.handler.codec.http.DefaultHttpRequest
import io.netty.handler.codec.http.EmptyHttpHeaders
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpHeaderValues
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpRequest
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.websocketx.Utf8FrameValidator
import io.netty.handler.codec.http.websocketx.WebSocketDecoderConfig
import io.netty.handler.codec.http.websocketx.WebSocketFrameAggregator
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshaker
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshaker13
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshakerFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import org.slf4j.LoggerFactory
import java.nio.channels.ClosedChannelException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Accepts WebSocket connections over HTTP/1.1 (RFC 6455 upgrade) and HTTP/2 (RFC 8441 extended
 * CONNECT).
 *
 * Both transports authorize through the ordinary [CallLifecycle] while the connection still speaks
 * HTTP, so a rejection is an ordinary 4xx response instead of an accepted socket that immediately
 * closes. Only the handshake, the failure response, and teardown differ per transport; those live
 * in [TransportUpgrade] implementations.
 */
internal class WebSocketUpgrades(
    private val application: BoscaApplication,
    private val engineScope: CoroutineScope,
    private val lifecycle: CallLifecycle,
    private val errors: HttpErrorResponder,
) {

    /** Handles an HTTP/1.1 `Upgrade: websocket` GET. Runs on the channel event loop. */
    fun upgradeHttp1(ctx: ChannelHandlerContext, request: HttpRequest, path: String, body: RequestBody?) {
        val route = application.router.resolveWebSocket(path) ?: run {
            body?.discard()
            errors.respond(ctx, HttpResponseStatus.NOT_FOUND)
            return
        }

        if (body != null) {
            body.discard()
            errors.respond(ctx, HttpResponseStatus.BAD_REQUEST)
            return
        }

        // The decoded request may be reference-counted and is released when channelRead returns.
        // Middleware and authentication can suspend, so retain only a detached header copy.
        val detachedRequest = DefaultHttpRequest(
            request.protocolVersion(),
            request.method(),
            request.uri(),
            request.headers().copy(),
        ).also { copy -> copy.decoderResult = request.decoderResult() }

        val forwardedProto = detachedRequest.headers().get(HttpHeaders.XForwardedProto)
        val scheme = if (forwardedProto.equals("https", ignoreCase = true)) "wss" else "ws"
        val handshaker = WebSocketServerHandshakerFactory(
            "$scheme://${detachedRequest.headers().get(HttpHeaderNames.HOST)}$path",
            route.protocol,
            HTTP1_DECODER_CONFIG,
        ).newHandshaker(detachedRequest)
        // Only RFC 6455 (version 13): without a version header Netty picks its pre-standard
        // (Hixie) handshaker, which would accept the obsolete protocol or fail after the call is
        // committed. Both get the standard 426 answer naming version 13.
        if (handshaker !is WebSocketServerHandshaker13) {
            log.debug("WebSocket upgrade rejected: unsupported WebSocket version")
            WebSocketServerHandshakerFactory.sendUnsupportedVersionResponse(ctx.channel())
            return
        }

        val call = newCall(ctx, detachedRequest, route)
        launchSession(ctx, call, route, Http1Upgrade(ctx, detachedRequest, handshaker, route))
    }

    /** Handles an RFC 8441 extended CONNECT on an HTTP/2 stream. Runs on the stream's event loop. */
    fun connectHttp2(
        ctx: ChannelHandlerContext,
        request: HttpRequest,
        path: String,
        transport: Http2WebSocketStreamHandler,
    ) {
        val route = application.router.resolveWebSocket(path) ?: run {
            transport.reject(HttpResponseStatus.NOT_FOUND)
            return
        }
        val selectedSubprotocol = selectSubprotocol(request, route)
        if (route.protocol != null && selectedSubprotocol == null) {
            transport.reject(HttpResponseStatus.BAD_REQUEST)
            return
        }

        val call = newCall(ctx, request, route)
        launchSession(ctx, call, route, Http2Upgrade(ctx, transport, selectedSubprotocol))
    }

    private fun newCall(ctx: ChannelHandlerContext, request: HttpRequest, route: WebSocketRouteEntry) = ServerCall(
        ServerRequest(request, null, ctx.channel().remoteIpAddress()),
        ServerResponse(ctx),
        Parameters.fromSingleValueMap(route.pathParameters ?: emptyMap()),
        application,
    )

    /**
     * Admits the call, performs the transport handshake, runs the route, and tears the connection
     * down. The session's job is a child of the channel's job, so closing the connection cancels
     * the route and anything it launched on the session.
     */
    private fun launchSession(
        ctx: ChannelHandlerContext,
        call: ServerCall,
        route: WebSocketRouteEntry,
        upgrade: TransportUpgrade,
    ) {
        val channelScope = ctx.channel().boscaScope()
        val channelJob = channelScope.coroutineContext.job
        channelScope.launch {
            var accepted = false
            var session: WebSocketSession? = null
            var terminationReason = CloseReason(CloseReason.Codes.NORMAL, "")
            try {
                if (!lifecycle.admit(call, route.authConfig) || !upgrade.validate(call)) return@launch

                val acceptedSession = WebSocketSession(
                    call,
                    ctx.channel(),
                    engineScope.coroutineContext + SupervisorJob(channelJob),
                )
                session = acceptedSession
                upgrade.accept(call, acceptedSession)
                accepted = true
                application.onHandler(call) {
                    acceptedSession.inheritHandlerContext(currentCoroutineContext())
                    route.handler(acceptedSession)
                }
            } catch (e: CancellationException) {
                if (!accepted) {
                    // A request-local timeout can cancel middleware without closing the connection.
                    // Close a half-finished handshake so it cannot linger or retain buffered frames.
                    if (ctx.channel().isActive) ctx.close()
                } else if (currentCoroutineContext().isActive) {
                    // The route's own timeout (withTimeout), not the connection closing: a failure,
                    // reported and sent as one, like any other the route throws.
                    log.error("WebSocket call timed out for {}", call.request.uri, e)
                    terminationReason = CloseReason(CloseReason.Codes.INTERNAL_ERROR, "Internal Server Error")
                    lifecycle.unwindException(call, e)
                } else if (ctx.channel().isActive) {
                    // Cancelled from outside while the connection is still up: the server is stopping.
                    terminationReason = CloseReason(CloseReason.Codes.GOING_AWAY, "Server shutting down")
                }
                throw e
            } catch (e: Exception) {
                log.error("WebSocket call failed for {}", call.request.uri, e)
                terminationReason = CloseReason(CloseReason.Codes.INTERNAL_ERROR, "Internal Server Error")
                try {
                    lifecycle.unwindException(call, e)
                } finally {
                    // Answered even when an onException middleware is itself cancelled (its own timeout):
                    // otherwise the client would wait for a response that never comes.
                    if (!accepted) withContext(NonCancellable) { upgrade.rejectFailure(call) }
                }
            } finally {
                // Torn down even when an afterCall middleware is cancelled (its own timeout): otherwise
                // the connection would stay open with no route behind it.
                try {
                    lifecycle.finish(call)
                } finally {
                    val opened = session.takeIf { accepted }
                    if (opened != null) {
                        upgrade.terminate(opened, terminationReason)
                    } else {
                        // A rejected or failed attempt records no reason and never runs the route.
                        session?.closeReason?.complete(null)
                        session?.coroutineContext?.get(Job)?.cancel()
                        upgrade.abandon()
                    }
                }
            }
        }
    }

    /** The transport-specific steps of accepting a WebSocket. One instance serves one connection attempt. */
    private interface TransportUpgrade {
        /** Validates the admitted call. Returns false after responding when the upgrade cannot proceed. */
        suspend fun validate(call: ServerCall): Boolean = true

        /** Commits the call and completes the protocol handshake; throws if the handshake fails. */
        suspend fun accept(call: ServerCall, session: WebSocketSession)

        /** Answers a failure that happened before [accept] completed. */
        suspend fun rejectFailure(call: ServerCall)

        /** Closes an accepted session once its route has finished, sending [reason] unless a close already went out. */
        fun terminate(session: WebSocketSession, reason: CloseReason)

        /** Releases the transport after an attempt that was never accepted. */
        fun abandon() {}
    }

    private class Http1Upgrade(
        private val ctx: ChannelHandlerContext,
        private val request: HttpRequest,
        private val handshaker: WebSocketServerHandshaker,
        private val route: WebSocketRouteEntry,
    ) : TransportUpgrade {

        /** Set once the handshake starts rewriting the HTTP-only pipeline. */
        private var handshakeStarted = false

        override suspend fun validate(call: ServerCall): Boolean {
            // The checks Netty's handshake makes, made here while the call can still answer 400:
            // failing them in the handshake, after the call is committed, could only drop the
            // connection and report a server error for a client's malformed request.
            val headers = request.headers()
            if (request.method() != HttpMethod.GET ||
                !headers.containsValue(HttpHeaderNames.CONNECTION, HttpHeaderValues.UPGRADE, true) ||
                !headers.contains(HttpHeaderNames.UPGRADE, HttpHeaderValues.WEBSOCKET, true) ||
                headers.get(HttpHeaderNames.SEC_WEBSOCKET_KEY) == null
            ) {
                call.response.respondText("Malformed WebSocket upgrade request", ContentType.Text.Plain, HttpStatusCode.BadRequest)
                return false
            }
            if (route.protocol != null && selectSubprotocol(request, route) == null) {
                call.response.respondText(
                    "Required WebSocket subprotocol was not offered",
                    ContentType.Text.Plain,
                    HttpStatusCode.BadRequest,
                )
                return false
            }
            return true
        }

        override suspend fun accept(call: ServerCall, session: WebSocketSession) {
            val responseHeaders = DefaultHttpHeaders()
            call.response.markCommitted()
            call.response.applyHeadersTo(responseHeaders)
            handshakeStarted = true
            beginHandshake(responseHeaders, session).awaitCompletion()
        }

        override suspend fun rejectFailure(call: ServerCall) {
            if (ctx.channel().isActive) {
                call.response.respondText(
                    "Internal Server Error",
                    ContentType.Text.Plain,
                    HttpStatusCode.InternalServerError,
                )
            }
        }

        override fun terminate(session: WebSocketSession, reason: CloseReason) {
            // Close the connection once the frame is out, waiting for it under backpressure
            // instead of closing the channel underneath it.
            val written = session.writeClose(reason.code, reason.message)
            session.closeReason.complete(reason)
            written.addListener(ChannelFutureListener.CLOSE)
        }

        override fun abandon() {
            // A rejection before the handshake leaves an ordinary, reusable HTTP connection. Once
            // the handshake began rewriting the pipeline, the connection cannot be reused.
            if (handshakeStarted) ctx.close()
        }

        /**
         * Removes HTTP-only response handlers and runs Netty's handshake on the event loop, then
         * installs the frame aggregator in front of the HTTP handler.
         */
        private fun beginHandshake(responseHeaders: DefaultHttpHeaders, session: WebSocketSession): ChannelFuture {
            val promise = ctx.newPromise()
            val begin = Runnable {
                if (!ctx.channel().isActive) {
                    promise.tryFailure(ClosedChannelException())
                    return@Runnable
                }

                val pipeline = ctx.pipeline()
                for (name in HTTP_ONLY_RESPONSE_HANDLERS) {
                    if (pipeline.get(name) != null) pipeline.remove(name)
                }
                ctx.channel().attr(ChannelAttributes.WS_SESSION).set(session)

                val handshakeRequest = DefaultFullHttpRequest(
                    request.protocolVersion(),
                    request.method(),
                    request.uri(),
                    Unpooled.EMPTY_BUFFER,
                    request.headers().copy(),
                    EmptyHttpHeaders.INSTANCE,
                ).also { copy -> copy.decoderResult = request.decoderResult() }
                try {
                    handshaker.handshake(ctx.channel(), handshakeRequest, responseHeaders, ctx.newPromise())
                        .addListener { future ->
                            if (future.isSuccess) {
                                if (pipeline.get(PipelineHandlerNames.HTTP1_WEBSOCKET_AGGREGATOR) == null) {
                                    pipeline.addBefore(
                                        ctx.name(),
                                        PipelineHandlerNames.HTTP1_WEBSOCKET_AGGREGATOR,
                                        WebSocketFrameAggregator(NettyHttpHandler.MAX_WEBSOCKET_FRAME_SIZE),
                                    )
                                    // Netty's decoder does not check text encoding; only its protocol
                                    // handler (unused here) installs this. Like the decoder, it only
                                    // raises the violation: NettyHttpHandler sends the close (1007).
                                    pipeline.addBefore(
                                        PipelineHandlerNames.HTTP1_WEBSOCKET_AGGREGATOR,
                                        PipelineHandlerNames.HTTP1_WEBSOCKET_UTF8_VALIDATOR,
                                        Utf8FrameValidator(false),
                                    )
                                }
                                promise.trySuccess()
                            } else {
                                ctx.channel().attr(ChannelAttributes.WS_SESSION).compareAndSet(session, null)
                                promise.tryFailure(future.cause() ?: IllegalStateException("WebSocket handshake failed"))
                            }
                        }
                } catch (cause: Throwable) {
                    ctx.channel().attr(ChannelAttributes.WS_SESSION).compareAndSet(session, null)
                    promise.tryFailure(cause)
                } finally {
                    handshakeRequest.release()
                }
            }
            if (ctx.executor().inEventLoop()) {
                begin.run()
            } else {
                try {
                    ctx.executor().execute(begin)
                } catch (cause: Throwable) {
                    promise.tryFailure(cause)
                }
            }
            return promise
        }
    }

    private inner class Http2Upgrade(
        private val ctx: ChannelHandlerContext,
        private val transport: Http2WebSocketStreamHandler,
        private val selectedSubprotocol: String?,
    ) : TransportUpgrade {

        override suspend fun accept(call: ServerCall, session: WebSocketSession) {
            ctx.channel().attr(ChannelAttributes.WS_SESSION).set(session)
            call.response.status(HttpStatusCode.OK)
            call.response.markCommitted()
            val responseHeaders = DefaultHttpHeaders()
            call.response.applyHeadersTo(responseHeaders)
            transport.accept(responseHeaders, selectedSubprotocol).awaitCompletion()
        }

        override suspend fun rejectFailure(call: ServerCall) {
            // A stream the client already reset has no one to answer, and writing to it would fail.
            if (!ctx.channel().isActive) return
            val responseHeaders = DefaultHttpHeaders()
            if (!call.response.isCommitted) {
                call.response.status(HttpStatusCode.InternalServerError)
                try {
                    call.response.markCommitted()
                    call.response.applyHeadersTo(responseHeaders)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.error("Failed to prepare HTTP/2 WebSocket error response", e)
                    application.errorCapture.capture(e, call, emptyMap())
                }
            }
            val written = transport.failHandshake(HttpResponseStatus.INTERNAL_SERVER_ERROR, responseHeaders)
            // Waited for, but not rethrown: the write fails only when the client reset the stream
            // meanwhile, and a client that left is not a server failure.
            suspendCancellableCoroutine { continuation ->
                written.addListener { future ->
                    if (!future.isSuccess) log.debug("HTTP/2 WebSocket error response not delivered", future.cause())
                    continuation.resume(Unit)
                }
            }
        }

        override fun terminate(session: WebSocketSession, reason: CloseReason) {
            // The close observer ends the stream once the frame is written.
            session.writeClose(reason.code, reason.message)
            session.closeReason.complete(reason)
        }
    }

    private companion object {
        private val log = LoggerFactory.getLogger(WebSocketUpgrades::class.java)

        /**
         * HTTP/1 frame decoding, as the handshaker's defaults (masked client frames, extensions
         * allowed) with one difference: a protocol violation only raises the error instead of
         * Netty sending its own close frame, which it would do even after we had already sent one.
         * NettyHttpHandler sends that close, at most once per session (see WebSocketSession.writeClose).
         */
        private val HTTP1_DECODER_CONFIG: WebSocketDecoderConfig = WebSocketDecoderConfig.newBuilder()
            .allowExtensions(true)
            .maxFramePayloadLength(NettyHttpHandler.MAX_WEBSOCKET_FRAME_SIZE)
            .closeOnProtocolViolation(false)
            .build()

        /** HTTP/1 response handlers that must leave the pipeline before WebSocket frames flow. */
        private val HTTP_ONLY_RESPONSE_HANDLERS = listOf(
            PipelineHandlerNames.KEEP_ALIVE,
            PipelineHandlerNames.COMPRESSOR,
            PipelineHandlerNames.CHUNKED_WRITER,
        )

        /** Returns the route's required subprotocol when the client offered it. */
        private fun selectSubprotocol(request: HttpRequest, route: WebSocketRouteEntry): String? {
            val required = route.protocol ?: return null
            val offered = request.headers()
                .getAll(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL)
                .flatMap { value -> value.split(',') }
                .map(String::trim)
            return required.takeIf { it in offered }
        }

        /** Suspends until the future completes; cancelling the coroutine cancels the future. */
        private suspend fun ChannelFuture.awaitCompletion() {
            suspendCancellableCoroutine { continuation ->
                addListener { future ->
                    if (!continuation.isActive) return@addListener
                    if (future.isSuccess) {
                        continuation.resume(Unit)
                    } else {
                        continuation.resumeWithException(
                            future.cause() ?: IllegalStateException("Netty channel operation failed"),
                        )
                    }
                }
                continuation.invokeOnCancellation { cancel(false) }
            }
        }
    }
}
