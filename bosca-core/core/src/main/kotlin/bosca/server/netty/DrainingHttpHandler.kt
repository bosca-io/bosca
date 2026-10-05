package bosca.server.netty

import io.netty.buffer.Unpooled
import io.netty.channel.ChannelDuplexHandler
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelPromise
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpHeaderValues
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpRequest
import io.netty.handler.codec.http.HttpResponse
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpStatusClass
import io.netty.handler.codec.http.HttpVersion
import io.netty.handler.codec.http.LastHttpContent
import io.netty.util.ReferenceCountUtil

/**
 * Rejects requests that arrive after server draining begins and marks active HTTP/1 responses to
 * close their connection. Installed after HTTP/1 exchange sequencing so a pipelined rejection
 * cannot overtake the response already in flight. HTTP/2 installs one instance per stream.
 */
internal class DrainingHttpHandler(
    private val isDraining: () -> Boolean,
    private val markConnectionClose: Boolean,
) : ChannelDuplexHandler() {

    // Event loop only; one instance per connection (HTTP/1) or per stream (HTTP/2).
    private var discardingRejectedRequest = false
    private var closeAfterResponse = false

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        if (discardingRejectedRequest) {
            if (msg is LastHttpContent) discardingRejectedRequest = false
            ReferenceCountUtil.release(msg)
            return
        }

        if (msg is HttpRequest && isDraining()) {
            discardingRejectedRequest = msg !is LastHttpContent
            val suppressBody = msg.method() == HttpMethod.HEAD
            ReferenceCountUtil.release(msg)
            val responseText = "503 Service Unavailable"
            val responseBytes = responseText.toByteArray(Charsets.UTF_8)
            val content = if (suppressBody) {
                Unpooled.EMPTY_BUFFER
            } else {
                Unpooled.wrappedBuffer(responseBytes)
            }
            val response = DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                HttpResponseStatus.SERVICE_UNAVAILABLE,
                content,
            )
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8")
            response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, responseBytes.size)
            if (markConnectionClose) {
                response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE)
            }
            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE)
            return
        }

        ctx.fireChannelRead(msg)
    }

    override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
        if (markConnectionClose &&
            isDraining() &&
            msg is HttpResponse &&
            msg.status().codeClass() != HttpStatusClass.INFORMATIONAL
        ) {
            msg.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE)
            closeAfterResponse = true
        }
        val writePromise = if (closeAfterResponse && msg is LastHttpContent) {
            closeAfterResponse = false
            promise.unvoid().addListener(ChannelFutureListener.CLOSE)
        } else {
            promise
        }
        ctx.write(msg, writePromise)
    }
}

