package bosca.server.netty

import bosca.server.BoscaApplication
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelFutureListener
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpHeaderValues
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpVersion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.nio.charset.StandardCharsets

/**
 * Writes fixed plain-text error responses for failures that happen before a [bosca.server.ServerCall]
 * exists (undecodable paths, route-resolution errors, unsupported WebSocket upgrades) and reports
 * any underlying exception to the application's error capture.
 */
internal class HttpErrorResponder(
    private val application: BoscaApplication,
    private val engineScope: CoroutineScope,
) {
    /**
     * Writes `<code> <reason>` with [status]. For HEAD requests ([suppressBody]) the body is omitted
     * while Content-Length still describes the GET representation.
     */
    fun respond(
        ctx: ChannelHandlerContext,
        status: HttpResponseStatus,
        cause: Exception? = null,
        suppressBody: Boolean = false,
        /** Closes the connection once the response is written, announcing it with `Connection: close`. */
        closeConnection: Boolean = false,
    ) {
        val responseBytes = "${status.code()} ${status.reasonPhrase()}".toByteArray(StandardCharsets.UTF_8)
        val response = DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1,
            status,
            if (suppressBody) Unpooled.EMPTY_BUFFER else Unpooled.wrappedBuffer(responseBytes),
        )
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8")
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, responseBytes.size)
        if (closeConnection) {
            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE)
            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE)
        } else {
            ctx.writeAndFlush(response)
        }
        cause?.let { report(it) }
    }

    /** Reports [cause] to error capture without a call context; capture runs off the event loop. */
    fun report(cause: Throwable) {
        engineScope.launch {
            application.errorCapture.capture(cause, null, emptyMap())
        }
    }
}
