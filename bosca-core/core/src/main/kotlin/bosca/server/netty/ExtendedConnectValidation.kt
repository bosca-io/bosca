package bosca.server.netty

import io.netty.handler.codec.http.DefaultHttpHeaders
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpHeaderValues
import io.netty.handler.codec.http.HttpHeaders
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpRequest
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http2.Http2Exception
import io.netty.handler.codec.http2.Http2Headers
import io.netty.handler.codec.http2.Http2HeadersFrame
import io.netty.handler.codec.http2.HttpConversionUtil

/** Validates RFC 8441 extended CONNECT requests that open a WebSocket over an HTTP/2 stream. */
internal object ExtendedConnectValidation {

    /** Why an extended CONNECT is refused: the response [status] and any [headers] it must carry. */
    data class Rejection(val status: HttpResponseStatus, val headers: HttpHeaders? = null)

    private const val WEBSOCKET_PROTOCOL = "websocket"
    private const val WEBSOCKET_VERSION = "13"

    private val CONNECTION_SPECIFIC_HEADERS = setOf(
        HttpHeaderNames.CONNECTION.toString(),
        "keep-alive",
        "proxy-connection",
        HttpHeaderNames.TRANSFER_ENCODING.toString(),
        HttpHeaderNames.UPGRADE.toString(),
    )

    /** Returns why [frame] cannot open a WebSocket, or null when it is a valid extended CONNECT. */
    fun rejection(frame: Http2HeadersFrame): Rejection? {
        val headers = frame.headers()
        val method = headers.method()
        val protocol = headers.get(Http2Headers.PseudoHeaderName.PROTOCOL.value())
        val scheme = headers.scheme()?.toString()
        val path = headers.path()
        val authority = headers.authority()
        return when {
            frame.isEndStream -> Rejection(HttpResponseStatus.BAD_REQUEST)
            method == null || !HttpMethod.CONNECT.asciiName().contentEqualsIgnoreCase(method) ->
                Rejection(HttpResponseStatus.BAD_REQUEST)
            protocol == null || !protocol.toString().equals(WEBSOCKET_PROTOCOL, ignoreCase = true) ->
                Rejection(HttpResponseStatus.NOT_IMPLEMENTED)
            scheme == null || !scheme.equals("http", ignoreCase = true) && !scheme.equals("https", ignoreCase = true) ->
                Rejection(HttpResponseStatus.BAD_REQUEST)
            path == null || path.isEmpty() || path[0] != '/' -> Rejection(HttpResponseStatus.BAD_REQUEST)
            authority == null || authority.isEmpty() -> Rejection(HttpResponseStatus.BAD_REQUEST)
            headers.get(HttpHeaderNames.SEC_WEBSOCKET_VERSION)?.toString() != WEBSOCKET_VERSION ->
                Rejection(
                    HttpResponseStatus.UPGRADE_REQUIRED,
                    DefaultHttpHeaders().set(HttpHeaderNames.SEC_WEBSOCKET_VERSION, WEBSOCKET_VERSION),
                )
            hasMalformedHeaders(headers) -> Rejection(HttpResponseStatus.BAD_REQUEST)
            else -> null
        }
    }

    /**
     * Converts a validated extended CONNECT into the HTTP request the application routes.
     *
     * Netty maps an ordinary CONNECT target to `:authority`, but RFC 8441 extended CONNECT keeps
     * `:path`, which is the route target Bosca must expose.
     *
     * @throws Http2Exception when the headers cannot be represented as an HTTP/1 request
     */
    fun toHttpRequest(frame: Http2HeadersFrame): HttpRequest {
        val headers = frame.headers()
        val path = requireNotNull(headers.path()) { "Extended CONNECT must be validated before conversion" }
        return HttpConversionUtil.toHttpRequest(frame.stream()?.id() ?: 0, headers, true).also { request ->
            request.setMethod(HttpMethod.CONNECT)
            request.setUri(path.toString())
        }
    }

    /** Rejects HTTP/1 connection-specific headers and any `TE` value other than `trailers` (RFC 9113 §8.2.2). */
    private fun hasMalformedHeaders(headers: Http2Headers): Boolean {
        if (CONNECTION_SPECIFIC_HEADERS.any(headers::contains)) return true
        return headers.getAll(HttpHeaderNames.TE).any { value ->
            value.toString().split(',').any { token ->
                !token.trim().equals(HttpHeaderValues.TRAILERS.toString(), ignoreCase = true)
            }
        }
    }
}
