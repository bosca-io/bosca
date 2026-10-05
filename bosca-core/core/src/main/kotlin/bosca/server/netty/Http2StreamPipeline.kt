package bosca.server.netty

import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelInitializer
import io.netty.handler.codec.http.HttpServerExpectContinueHandler
import io.netty.handler.codec.http2.Http2Headers
import io.netty.handler.codec.http2.Http2HeadersFrame
import io.netty.handler.codec.http2.Http2StreamFrameToHttpObjectCodec
import io.netty.handler.stream.ChunkedWriteHandler
import io.netty.util.concurrent.EventExecutorGroup

/**
 * Selects the ordinary HTTP pipeline or RFC 8441 extended CONNECT before converting HTTP/2
 * headers into HTTP/1-style objects.
 *
 * Netty's generic [Http2StreamFrameToHttpObjectCodec] intentionally discards the `:protocol`
 * pseudo-header and maps a CONNECT target to `:authority`. The selection therefore has to happen
 * while the original [Http2HeadersFrame] is still available.
 */
internal class Http2StreamProtocolSelector(
    private val compressionEnabled: Boolean,
    private val codecGroup: EventExecutorGroup,
    private val idleTimeoutSeconds: Long,
) : ChannelInboundHandlerAdapter() {

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        if (msg !is Http2HeadersFrame) {
            ctx.fireChannelRead(msg)
            return
        }

        val protocol = msg.headers().get(Http2Headers.PseudoHeaderName.PROTOCOL.value())
        if (protocol != null) {
            selectExtendedConnect(ctx, msg)
        } else {
            selectOrdinaryHttp(ctx, msg)
        }
    }

    private fun selectOrdinaryHttp(ctx: ChannelHandlerContext, headers: Http2HeadersFrame) {
        // HTTP/2 forbids Transfer-Encoding and does not require Content-Length, so those headers
        // cannot tell NettyHttpHandler whether DATA frames will follow. Preserve the original
        // end-stream signal before the generic HTTP-object codec discards it.
        ctx.channel().attr(ChannelAttributes.HTTP2_REQUEST_BODY_EXPECTED).set(!headers.isEndStream)
        val pipeline = ctx.pipeline()
        var previous = ctx.name()
        pipeline.addAfter(previous, PipelineHandlerNames.HTTP2_TO_HTTP, Http2StreamFrameToHttpObjectCodec(true))
        previous = PipelineHandlerNames.HTTP2_TO_HTTP
        pipeline.addAfter(previous, PipelineHandlerNames.EXPECT_CONTINUE, HttpServerExpectContinueHandler())
        previous = PipelineHandlerNames.EXPECT_CONTINUE
        if (compressionEnabled) {
            pipeline.addAfter(
                previous,
                PipelineHandlerNames.COMPRESSOR,
                CompressionOffloadHandler(codecGroup.next()),
            )
            previous = PipelineHandlerNames.COMPRESSOR
        }
        pipeline.addAfter(previous, PipelineHandlerNames.CHUNKED_WRITER, ChunkedWriteHandler())
        pipeline.remove(this)
        pipeline.fireChannelRead(headers)
    }

    private fun selectExtendedConnect(ctx: ChannelHandlerContext, headers: Http2HeadersFrame) {
        val pipeline = ctx.pipeline()
        pipeline.replace(
            this,
            PipelineHandlerNames.HTTP2_WEBSOCKET_TRANSPORT,
            Http2WebSocketStreamHandler(idleTimeoutSeconds),
        )
        pipeline.fireChannelRead(headers)
    }
}

/** Builds the Bosca protocol selector on each independent HTTP/2 stream channel. */
internal class Http2StreamInitializer(
    private val compressionEnabled: Boolean,
    private val codecGroup: EventExecutorGroup,
    private val httpHandler: NettyHttpHandler,
    private val idleTimeoutSeconds: Long,
    private val isDraining: () -> Boolean,
    private val onStreamOpened: () -> Unit = {},
    private val onStreamClosed: () -> Unit = {},
) : ChannelInitializer<Channel>() {
    override fun initChannel(ch: Channel) {
        onStreamOpened()
        ch.closeFuture().addListener { onStreamClosed() }
        val pipeline = ch.pipeline()
        pipeline.addLast(
            PipelineHandlerNames.HTTP2_PROTOCOL_SELECTOR,
            Http2StreamProtocolSelector(compressionEnabled, codecGroup, idleTimeoutSeconds),
        )
        pipeline.addLast(PipelineHandlerNames.HTTP_DRAIN, DrainingHttpHandler(isDraining, markConnectionClose = false))
        pipeline.addLast(PipelineHandlerNames.HTTP_HANDLER, httpHandler)
    }
}
