package bosca.server.netty

import bosca.server.BoscaApplication
import bosca.server.ContentType
import bosca.server.HttpMethod
import bosca.server.HttpStatusCode
import bosca.server.Parameters
import bosca.server.RequestBody
import bosca.server.ServerCall
import bosca.server.ServerRequest
import bosca.server.ServerResponse
import bosca.server.StreamingTimeLimitException
import bosca.server.routing.RoutingContext
import bosca.server.routing.SSERouteEntry
import bosca.server.sse.ServerSSESession
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpRequest
import io.netty.handler.codec.http.HttpResponseStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Routes a decoded request head to the protocol that serves it: an RFC 8441 or HTTP/1 WebSocket
 * upgrade, a Server-Sent Events stream, or an ordinary route.
 *
 * [dispatch] runs on the channel event loop and only selects the protocol. Application work runs in
 * the channel's coroutine scope: on the channel's own event loop by default
 * ([RequestDispatcherMode.EVENT_LOOP]), so a handler that blocks stalls every connection on that
 * loop and blocking calls belong on `Dispatchers.IO`; or on the engine's request pool in
 * [RequestDispatcherMode.POOL] mode.
 */
internal class HttpRequestDispatcher(
    private val application: BoscaApplication,
    private val lifecycle: CallLifecycle,
    private val errors: HttpErrorResponder,
    private val upgrades: WebSocketUpgrades,
) {

    fun dispatch(ctx: ChannelHandlerContext, request: HttpRequest, body: RequestBody?) {
        val method = HttpMethod.parse(request.method().name())
        val suppressBody = method == HttpMethod.Head
        val path = try {
            decodePath(request.uri())
        } catch (e: IllegalArgumentException) {
            // The client's malformed path: answered 400, not logged or reported as a server error,
            // which any client could otherwise trigger at will.
            log.debug("Rejecting a request path that does not decode: {}", e.message)
            body?.discard()
            errors.respond(ctx, HttpResponseStatus.BAD_REQUEST, suppressBody = suppressBody)
            return
        }

        // RFC 8441 extended CONNECT is selected from the original HTTP/2 headers before Netty's
        // generic HTTP-object conversion; the stream transport supplies this synthetic request.
        val http2WebSocketTransport = ctx.channel().attr(ChannelAttributes.HTTP2_WEBSOCKET_TRANSPORT).get()
        if (http2WebSocketTransport != null) {
            upgrades.connectHttp2(ctx, request, path, http2WebSocketTransport)
            return
        }

        val upgradeHeader = request.headers().get(HttpHeaderNames.UPGRADE)
        if (upgradeHeader != null && upgradeHeader.equals("websocket", ignoreCase = true)) {
            if (method != HttpMethod.Get) {
                body?.discard()
                errors.respond(ctx, HttpResponseStatus.BAD_REQUEST, suppressBody = suppressBody)
                return
            }
            upgrades.upgradeHttp1(ctx, request, path, body)
            return
        }

        // SSE is a long-lived GET transport. HEAD continues through ordinary routing so a matching
        // GET route can provide equivalent headers without starting the stream or emitting content.
        if (method == HttpMethod.Get && acceptsEventStream(request)) {
            val sseRoute = try {
                application.router.resolveSSE(path)
            } catch (e: Exception) {
                log.error("SSE route resolution failed", e)
                // Discard first so further chunks stop accumulating while the error is written.
                body?.discard()
                errors.respond(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, e, suppressBody)
                return
            }
            if (sseRoute != null) {
                dispatchSse(ctx, request, body, sseRoute)
                return
            }
        }

        dispatchRoute(ctx, request, body, method, path, suppressBody)
    }

    private fun dispatchRoute(
        ctx: ChannelHandlerContext,
        request: HttpRequest,
        body: RequestBody?,
        method: HttpMethod,
        path: String,
        suppressBody: Boolean,
    ) {
        ctx.channel().boscaScope().launch {
            var call: ServerCall? = null
            // Whether the request ended in a state that can still be answered. An Error or a real
            // cancellation leaves it false, so no default 200 goes out for a request that failed.
            var answerable = false
            try {
                val resolved = application.router.resolve(method, path)
                val routeCall = ServerCall(
                    ServerRequest(request, body, ctx.channel().remoteIpAddress(), parsedMethod = method),
                    ServerResponse(ctx).also { response -> response.suppressBody = suppressBody },
                    Parameters.fromSingleValueMap(resolved?.pathParameters ?: emptyMap()),
                    application,
                )
                call = routeCall

                // beforeCall runs before route matching so CORS can answer preflight OPTIONS
                // requests even when no route matches.
                lifecycle.beforeCall(routeCall)
                if (resolved == null) {
                    respondUnmatched(routeCall, path)
                } else {
                    routeCall.routePattern = resolved.routePattern
                    lifecycle.authenticate(routeCall, resolved.authConfig)
                    if (!routeCall.response.isCommitted) {
                        application.onHandler(routeCall) { resolved.handler(RoutingContext(routeCall, application)) }
                    }
                }
                answerable = true
            } catch (e: CancellationException) {
                // Deliberately caught: the connection closing cancels this coroutine, and that is
                // rethrown here; any other cancellation escaped the handler itself.
                if (e is TimeoutCancellationException && !currentCoroutineContext().isActive) {
                    // A withTimeout in the handler expired, and the connection closing (which a
                    // failed stream triggers) has since cancelled this coroutine too, as can happen
                    // in pool mode. The timeout is still a failure: report it, then end as cancelled.
                    withContext(NonCancellable) { failRequest(ctx, call, e, suppressBody) }
                    throw e
                }
                currentCoroutineContext().ensureActive()
                if (e is StreamingTimeLimitException) {
                    // A streaming response reached a time limit (its own, or a client that stopped
                    // reading): long-lived watch routes end that way by design. Not a failure.
                    log.debug("Streaming response for {} ended: {}", call?.request?.uri, e.message)
                } else {
                    // Typically a withTimeout that expired in the handler: a failure, reported like
                    // any other (and never answered 200).
                    closingOnMiddlewareCancellation(ctx, call) { failRequest(ctx, call, e, suppressBody) }
                }
                answerable = true
            } catch (e: Exception) {
                closingOnMiddlewareCancellation(ctx, call) { failRequest(ctx, call, e, suppressBody) }
                answerable = true
            } finally {
                // Release unconsumed body chunks before after-call middleware allocates more.
                try {
                    body?.discard()
                } catch (e: Exception) {
                    log.error("Failed to discard the request body", e)
                }
                call?.let { finishedCall ->
                    closingOnMiddlewareCancellation(ctx, finishedCall) { lifecycle.finish(finishedCall) }
                    if (answerable) commitIfUnanswered(finishedCall)
                }
            }
        }
    }

    private fun dispatchSse(
        ctx: ChannelHandlerContext,
        request: HttpRequest,
        body: RequestBody?,
        route: SSERouteEntry,
    ) {
        Http2LongLivedStreams.track(ctx.channel())
        ctx.channel().boscaScope().launch {
            val call = ServerCall(
                ServerRequest(request, body, ctx.channel().remoteIpAddress(), parsedMethod = HttpMethod.Get),
                ServerResponse(ctx),
                Parameters.fromSingleValueMap(route.pathParameters ?: emptyMap()),
                application,
            )
            val session = ServerSSESession(call, ctx)
            ctx.channel().attr(ChannelAttributes.SSE_SESSION).set(session)
            try {
                if (lifecycle.admit(call, route.authConfig)) {
                    application.onHandler(call) { route.handler(session) }
                }
            } catch (e: CancellationException) {
                // Still active: the handler's own timeout, not the connection closing. A failure,
                // reported like any other (as ordinary routes and WebSocket routes do), then rethrown.
                if (currentCoroutineContext().isActive) lifecycle.fail(call, e)
                throw e
            } catch (e: Exception) {
                lifecycle.fail(call, e)
            } finally {
                ctx.channel().attr(ChannelAttributes.SSE_SESSION).set(null)
                withContext(NonCancellable) {
                    try {
                        session.close()
                    } finally {
                        try {
                            lifecycle.finish(call)
                        } finally {
                            body?.discard()
                        }
                    }
                }
            }
        }
    }

    /** Answers a request no route matched: 405 with `Allow` when another method matches, else 404. */
    private fun respondUnmatched(call: ServerCall, path: String) {
        if (call.response.isCommitted) return
        val allowed = application.router.allowedMethods(path)
        if (allowed.isNotEmpty()) {
            call.response.header("Allow", allowed.joinToString(", ") { it.value })
            call.response.respondText("405 Method Not Allowed", ContentType.Text.Plain, HttpStatusCode.MethodNotAllowed)
        } else {
            call.response.respondText("404 Not Found", ContentType.Text.Plain, HttpStatusCode.NotFound)
        }
    }

    /**
     * Runs middleware unwinding ([block]). A middleware that throws `CancellationException` while
     * this request is still active (its own timeout) stops the unwind, as [CallLifecycle] intends,
     * and ends this coroutine as cancelled. If nothing was sent yet, nothing ever will be, so the
     * connection is closed rather than leaving the client waiting. A response that already went out
     * is left to finish: closing would cut it off, along with any pipelined responses behind it.
     */
    private suspend inline fun closingOnMiddlewareCancellation(
        ctx: ChannelHandlerContext,
        call: ServerCall?,
        block: () -> Unit,
    ) {
        // The catch rethrows: a middleware's cancellation still ends this coroutine as the project's
        // cancellation rule requires; it is caught only to keep the client from waiting forever.
        try {
            block()
        } catch (e: CancellationException) {
            if (currentCoroutineContext().isActive) {
                if (call?.response?.isCommitted == true) {
                    log.warn("Middleware cancelled while finishing {}; its response was already sent", call.request.uri, e)
                } else {
                    log.warn("Middleware cancelled before a response was sent; closing the connection", e)
                    ctx.close()
                }
            }
            throw e
        }
    }

    private suspend fun failRequest(ctx: ChannelHandlerContext, call: ServerCall?, e: Exception, suppressBody: Boolean) {
        if (call != null) {
            lifecycle.fail(call, e)
        } else {
            log.error("Request processing failed before the call was created", e)
            if (ctx.channel().isActive) {
                errors.respond(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, e, suppressBody)
            }
        }
    }

    /** Commits a response the handler never wrote, defaulting a missing status to 200 OK. */
    private fun commitIfUnanswered(call: ServerCall) {
        if (call.response.isCommitted) return
        if (call.response.status() == null) {
            log.warn("Handler returned without responding, sending default 200 OK for {}", call.request.uri)
            call.response.status(HttpStatusCode.OK)
        }
        call.response.commit()
    }

    private companion object {
        private val log = LoggerFactory.getLogger(HttpRequestDispatcher::class.java)

        private fun acceptsEventStream(request: HttpRequest): Boolean =
            request.headers().get(HttpHeaderNames.ACCEPT)?.contains("text/event-stream") == true

        /**
         * Returns the percent-decoded path of [uri], without its query string.
         *
         * Segments are decoded one at a time with [URLDecoder], so `+` also decodes to a space.
         * An encoded slash (`%2F`) still becomes `/` in the result and therefore acts as a path
         * separator during routing; the npm scoped-package routes (`/npm/@{scope}/{name}`) rely on
         * clients sending `@scope%2Fname`.
         *
         * @throws IllegalArgumentException when a percent escape is malformed
         */
        private fun decodePath(uri: String): String {
            val queryStart = uri.indexOf('?')
            val rawPath = if (queryStart >= 0) uri.substring(0, queryStart) else uri
            if ('%' !in rawPath && '+' !in rawPath) return rawPath
            return rawPath.split('/').joinToString("/") { segment ->
                if (segment.isEmpty()) segment else URLDecoder.decode(segment, StandardCharsets.UTF_8)
            }
        }
    }
}
