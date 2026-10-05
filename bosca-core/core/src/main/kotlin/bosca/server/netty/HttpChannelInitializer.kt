package bosca.server.netty

import io.netty.channel.Channel
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelPipeline
import io.netty.channel.socket.SocketChannel
import io.netty.handler.codec.http.HttpServerCodec
import io.netty.handler.codec.http.HttpServerExpectContinueHandler
import io.netty.handler.codec.http.HttpServerKeepAliveHandler
import io.netty.handler.codec.http2.CleartextHttp2ServerUpgradeHandler
import io.netty.handler.codec.http2.Http2CodecUtil
import io.netty.handler.codec.http2.Http2FrameCodecBuilder
import io.netty.handler.codec.http2.Http2MultiplexHandler
import io.netty.handler.codec.http2.Http2ServerUpgradeCodec
import io.netty.handler.codec.http2.Http2Settings
import io.netty.handler.stream.ChunkedWriteHandler
import io.netty.handler.timeout.IdleStateHandler
import io.netty.util.concurrent.EventExecutorGroup
import java.util.concurrent.TimeUnit

/**
 * Builds the pipeline of every accepted connection.
 *
 * ```
 * idleState → [http2Cleartext → http1Selected]   (HTTP/2 enabled; replaced by the frame codec and
 *           | httpCodec                            multiplexer once HTTP/2 is negotiated)
 *           → http1Sequencer → httpDrain → expectContinue → keepAlive → [compressor]
 *           → chunkedWriter → handler
 * ```
 *
 * HTTP/2 streams get their own pipeline from [Http2StreamInitializer].
 */
internal class HttpChannelInitializer(
    private val settings: NettyServerSettings,
    private val codecGroup: EventExecutorGroup,
    private val httpHandler: NettyHttpHandler,
    private val isDraining: () -> Boolean,
    private val onConnectionAccepted: (Channel) -> Unit,
    private val onStreamOpened: () -> Unit,
    private val onStreamClosed: () -> Unit,
) : ChannelInitializer<SocketChannel>() {

    override fun initChannel(ch: SocketChannel) {
        onConnectionAccepted(ch)
        val pipeline = ch.pipeline()
        // Observe raw connection traffic before HTTP/1 decoding or HTTP/2 multiplexing so active
        // HTTP/2 streams reset the parent connection's idle timer.
        pipeline.addLast(
            PipelineHandlerNames.IDLE_STATE,
            IdleStateHandler(0, 0, settings.idleTimeoutSeconds, TimeUnit.SECONDS),
        )
        val httpCodec = HttpServerCodec(MAX_INITIAL_LINE_LENGTH, MAX_HEADER_SIZE, MAX_CHUNK_SIZE)
        if (settings.http2Enabled) {
            pipeline.addCleartextHttp2Negotiation(httpCodec)
        } else {
            pipeline.addLast(PipelineHandlerNames.HTTP_CODEC, httpCodec)
        }
        pipeline.addHttp1Handlers(httpCodec)
    }

    /**
     * Accepts HTTP/2 by prior knowledge or by an h2c upgrade from HTTP/1.1. Until one of those
     * happens the connection speaks HTTP/1.1 through [httpCodec].
     */
    private fun ChannelPipeline.addCleartextHttp2Negotiation(httpCodec: HttpServerCodec) {
        val frameCodec = Http2FrameCodecBuilder.forServer()
            .initialSettings(http2InitialSettings(settings.http2MaxConcurrentStreams))
            .build()
        val multiplexHandler = Http2MultiplexHandler(
            Http2StreamInitializer(
                settings.compressionEnabled,
                codecGroup,
                httpHandler,
                settings.idleTimeoutSeconds,
                isDraining,
                onStreamOpened,
                onStreamClosed,
            ),
        )
        val upgradeHandler = BodylessH2cUpgradeHandler(httpCodec) { protocol ->
            if (protocol.toString().equals(Http2CodecUtil.HTTP_UPGRADE_PROTOCOL_NAME.toString(), true)) {
                Http2ServerUpgradeCodec(PipelineHandlerNames.HTTP2_FRAME_CODEC, frameCodec, multiplexHandler)
            } else {
                null
            }
        }
        addLast(
            PipelineHandlerNames.HTTP2_CLEARTEXT,
            CleartextHttp2ServerUpgradeHandler(
                httpCodec,
                upgradeHandler,
                Http2PriorKnowledgeHandler(frameCodec, multiplexHandler),
            ),
        )
        addLast(PipelineHandlerNames.HTTP1_SELECTED, Http1ProtocolSelectionHandler(upgradeHandler))
    }

    private fun ChannelPipeline.addHttp1Handlers(httpCodec: HttpServerCodec) {
        addLast(PipelineHandlerNames.HTTP1_SEQUENCER, Http1ExchangeSequencer(httpCodec))
        addLast(PipelineHandlerNames.HTTP_DRAIN, DrainingHttpHandler(isDraining, markConnectionClose = true))
        addLast(PipelineHandlerNames.EXPECT_CONTINUE, HttpServerExpectContinueHandler())
        addLast(PipelineHandlerNames.KEEP_ALIVE, HttpServerKeepAliveHandler())
        // The HTTP handler stays on the event loop, where an accepted WebSocket handshake removes
        // the HTTP-only response handlers (keep-alive above; compression and chunked writing
        // below) before the protocol switches.
        if (settings.compressionEnabled) {
            addSelectiveCompression(codecGroup)
        }
        addLast(PipelineHandlerNames.CHUNKED_WRITER, ChunkedWriteHandler())
        addLast(PipelineHandlerNames.HTTP_HANDLER, httpHandler)
    }

    companion object {
        /** Longest accepted HTTP/1 request line, in bytes. */
        private const val MAX_INITIAL_LINE_LENGTH = 16 * 1024

        /** Largest accepted HTTP/1 header block, in bytes. */
        private const val MAX_HEADER_SIZE = 16 * 1024

        /** Largest body chunk the HTTP/1 decoder emits, in bytes. */
        private const val MAX_CHUNK_SIZE = 64 * 1024

        /** Advertises RFC 8441 extended CONNECT support and the stream limit on every HTTP/2 connection. */
        internal fun http2InitialSettings(
            maxConcurrentStreams: Long = NettyServerSettings.DEFAULT_HTTP2_MAX_CONCURRENT_STREAMS,
        ): Http2Settings = Http2Settings.defaultSettings()
            .connectProtocolEnabled(true)
            .maxConcurrentStreams(maxConcurrentStreams)
    }
}
