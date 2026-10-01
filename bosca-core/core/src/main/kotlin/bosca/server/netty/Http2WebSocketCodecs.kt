package bosca.server.netty

import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelPipeline
import io.netty.channel.ChannelPromise
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame
import io.netty.handler.codec.http.websocketx.Utf8FrameValidator
import io.netty.handler.codec.http.websocketx.WebSocket13FrameDecoder
import io.netty.handler.codec.http.websocketx.WebSocket13FrameEncoder
import io.netty.handler.codec.http.websocketx.WebSocketDecoderConfig
import io.netty.handler.codec.http.websocketx.WebSocketFrameAggregator
import io.netty.handler.timeout.IdleStateHandler
import java.util.concurrent.TimeUnit

/**
 * Installs RFC 6455 framing after the RFC 8441 [transport] handler named [transportName]:
 *
 * ```
 * transport → [idle] → encoder → close observer → decoder → UTF-8 validator → aggregator
 * ```
 *
 * The outbound encoder sits before the inbound decoder, matching Netty's HTTP/1 WebSocket
 * pipeline. Decoding failures are only raised; NettyHttpHandler sends the close.
 */
internal fun ChannelPipeline.installHttp2WebSocketCodecs(
    transportName: String,
    transport: Http2WebSocketStreamHandler,
    idleTimeoutSeconds: Long,
) {
    var previous = transportName
    if (idleTimeoutSeconds > 0) {
        addAfter(
            previous,
            PipelineHandlerNames.HTTP2_WEBSOCKET_IDLE,
            IdleStateHandler(0, 0, idleTimeoutSeconds, TimeUnit.SECONDS),
        )
        previous = PipelineHandlerNames.HTTP2_WEBSOCKET_IDLE
    }
    val decoderConfig = WebSocketDecoderConfig.newBuilder()
        .expectMaskedFrames(true)
        .allowMaskMismatch(false)
        .allowExtensions(false)
        .maxFramePayloadLength(NettyHttpHandler.MAX_WEBSOCKET_FRAME_SIZE)
        // Netty's built-in violation handling attaches ChannelFutureListener.CLOSE to the
        // close-frame write. Closing a multiplexed child that way can reset it before the close
        // frame reaches the peer, so NettyHttpHandler sends the close and the close observer below
        // ends the stream.
        .closeOnProtocolViolation(false)
        .build()
    addAfter(previous, PipelineHandlerNames.HTTP2_WEBSOCKET_ENCODER, WebSocket13FrameEncoder(false))
    addAfter(
        PipelineHandlerNames.HTTP2_WEBSOCKET_ENCODER,
        PipelineHandlerNames.HTTP2_WEBSOCKET_CLOSE,
        Http2WebSocketCloseFrameHandler(transport),
    )
    addAfter(
        PipelineHandlerNames.HTTP2_WEBSOCKET_CLOSE,
        PipelineHandlerNames.HTTP2_WEBSOCKET_DECODER,
        WebSocket13FrameDecoder(decoderConfig),
    )
    // Netty's decoder does not check text encoding (withUTF8Validator only takes effect through its
    // protocol handler, unused here). Invalid UTF-8 raises 1007 for NettyHttpHandler to send.
    addAfter(
        PipelineHandlerNames.HTTP2_WEBSOCKET_DECODER,
        PipelineHandlerNames.HTTP2_WEBSOCKET_UTF8_VALIDATOR,
        Utf8FrameValidator(false),
    )
    addAfter(
        PipelineHandlerNames.HTTP2_WEBSOCKET_UTF8_VALIDATOR,
        PipelineHandlerNames.HTTP2_WEBSOCKET_AGGREGATOR,
        WebSocketFrameAggregator(NettyHttpHandler.MAX_WEBSOCKET_FRAME_SIZE),
    )
}

/** Ends the HTTP/2 stream after an encoded WebSocket close frame reaches the wire. */
internal class Http2WebSocketCloseFrameHandler(
    private val transport: Http2WebSocketStreamHandler,
) : ChannelOutboundHandlerAdapter() {
    override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
        if (msg !is CloseWebSocketFrame) {
            ctx.write(msg, promise)
            return
        }

        val observedPromise = if (promise.isVoid) ctx.newPromise() else promise
        ctx.write(msg, observedPromise)
        observedPromise.addListener { future ->
            if (future.isSuccess) {
                transport.endStream()
            } else {
                ctx.close()
            }
        }
    }
}
