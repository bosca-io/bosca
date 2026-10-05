package bosca.server.netty

import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.handler.codec.http.HttpContent
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpRequest
import io.netty.handler.codec.http.HttpServerUpgradeHandler
import io.netty.handler.codec.http.HttpUtil
import io.netty.handler.codec.http2.Http2FrameCodec
import io.netty.handler.codec.http2.Http2MultiplexHandler

/** Installs the HTTP/2 frame codec and stream multiplexer after prior-knowledge negotiation. */
internal class Http2PriorKnowledgeHandler(
    private val frameCodec: Http2FrameCodec,
    private val multiplexHandler: Http2MultiplexHandler,
) : ChannelInboundHandlerAdapter() {
    override fun handlerAdded(ctx: ChannelHandlerContext) {
        ctx.pipeline()
            .addAfter(ctx.name(), PipelineHandlerNames.HTTP2_FRAME_CODEC, frameCodec)
            .addAfter(
                PipelineHandlerNames.HTTP2_FRAME_CODEC,
                PipelineHandlerNames.HTTP2_MULTIPLEX,
                multiplexHandler,
            )
            .remove(this)
    }
}

/** Removes h2c upgrade aggregation from a connection once its first HTTP/1 request selects that protocol. */
internal class Http1ProtocolSelectionHandler(
    private val upgradeHandler: HttpServerUpgradeHandler,
) : ChannelInboundHandlerAdapter() {
    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        if (msg is HttpRequest) {
            // An initial h2c upgrade would have been consumed by upgradeHandler before reaching here.
            if (ctx.pipeline().context(upgradeHandler) != null) {
                ctx.pipeline().remove(upgradeHandler)
            }
            ctx.pipeline().remove(this)
        }
        ctx.fireChannelRead(msg)
    }
}

/**
 * Keeps body-bearing h2c upgrade requests on HTTP/1 instead of feeding them to Netty's zero-length
 * upgrade aggregator. RFC 7540 permits an upgrade request body, while Bosca's ordinary HTTP/1 path
 * already streams and bounds those bodies without first retaining the whole request in memory.
 */
internal class BodylessH2cUpgradeHandler(
    sourceCodec: HttpServerUpgradeHandler.SourceCodec,
    upgradeCodecFactory: HttpServerUpgradeHandler.UpgradeCodecFactory,
) : HttpServerUpgradeHandler(sourceCodec, upgradeCodecFactory) {
    // A request that failed to decode is never upgraded: its headers may be cut short, and it must
    // reach NettyHttpHandler, which rejects it, rather than be served as HTTP/2 stream 1.
    override fun shouldHandleUpgradeRequest(request: HttpRequest): Boolean =
        request.decoderResult().isSuccess && isBodylessH2cUpgradeRequest(request)
}

internal fun isBodylessH2cUpgradeRequest(request: HttpRequest): Boolean =
    (request !is HttpContent || !request.content().isReadable)
        && HttpUtil.getContentLength(request, 0L) == 0L
        && !request.headers().contains(HttpHeaderNames.TRANSFER_ENCODING)
