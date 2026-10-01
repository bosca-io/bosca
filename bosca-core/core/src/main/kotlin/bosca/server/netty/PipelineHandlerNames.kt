package bosca.server.netty

/**
 * Names of every handler Bosca installs in a connection or HTTP/2 stream pipeline.
 *
 * Handlers are looked up, replaced, and removed by name during protocol negotiation, WebSocket
 * upgrades, and RFC 8441 stream setup, so each name has exactly one definition here.
 */
internal object PipelineHandlerNames {
    // Connection pipeline (HTTP/1, and the parent channel of an HTTP/2 connection).

    /** Idle-connection detector; observes raw traffic before any protocol decoding. */
    const val IDLE_STATE = "idleState"

    /** HTTP/1 decoder/encoder when HTTP/2 negotiation is disabled. */
    const val HTTP_CODEC = "httpCodec"

    /** Cleartext HTTP/2 prior-knowledge and h2c upgrade detector. */
    const val HTTP2_CLEARTEXT = "http2Cleartext"

    /** HTTP/2 connection frame codec. */
    const val HTTP2_FRAME_CODEC = "http2FrameCodec"

    /** HTTP/2 stream multiplexer. */
    const val HTTP2_MULTIPLEX = "http2Multiplex"

    /** One-shot handler that removes h2c upgrade machinery once HTTP/1 is selected. */
    const val HTTP1_SELECTED = "http1Selected"

    /** Per-connection HTTP/1 exchange ordering handler. */
    const val HTTP1_SEQUENCER = "http1Sequencer"

    /** Rejects new requests once server draining begins. */
    const val HTTP_DRAIN = "httpDrain"

    /** Answers `Expect: 100-continue`. */
    const val EXPECT_CONTINUE = "expectContinue"

    /** HTTP/1 keep-alive bookkeeping; removed when a WebSocket upgrade is accepted. */
    const val KEEP_ALIVE = "keepAlive"

    /** Response compressor; removed when a WebSocket upgrade is accepted. */
    const val COMPRESSOR = "compressor"

    /** Chunked-body writer; removed when a WebSocket upgrade is accepted. */
    const val CHUNKED_WRITER = "chunkedWriter"

    /** The shared [NettyHttpHandler]. */
    const val HTTP_HANDLER = "handler"

    /** WebSocket message-fragment aggregator installed after an HTTP/1 upgrade. */
    const val HTTP1_WEBSOCKET_AGGREGATOR = "http1WebSocketAggregator"

    /** Rejects text frames that are not valid UTF-8 (close 1007), ahead of the HTTP/1 aggregator. */
    const val HTTP1_WEBSOCKET_UTF8_VALIDATOR = "http1WebSocketUtf8Validator"

    /** Resumes streaming writers when the channel drains; installed on first backpressure. */
    const val WRITABILITY_WAITER = "writabilityWaiter"

    // HTTP/2 stream pipeline.

    /** Chooses ordinary HTTP or RFC 8441 extended CONNECT from the initial HEADERS frame. */
    const val HTTP2_PROTOCOL_SELECTOR = "http2ProtocolSelector"

    /** Converts HTTP/2 stream frames to HTTP/1-style message objects. */
    const val HTTP2_TO_HTTP = "http2ToHttp"

    /** RFC 8441 transport bridging DATA frames and WebSocket bytes. */
    const val HTTP2_WEBSOCKET_TRANSPORT = "http2WebSocketTransport"

    /** Idle detector for an open RFC 8441 WebSocket stream. */
    const val HTTP2_WEBSOCKET_IDLE = "http2WebSocketIdle"

    /** RFC 6455 frame encoder on an RFC 8441 stream. */
    const val HTTP2_WEBSOCKET_ENCODER = "http2WebSocketEncoder"

    /** Ends the stream after an outbound close frame reaches the wire. */
    const val HTTP2_WEBSOCKET_CLOSE = "http2WebSocketClose"

    /** RFC 6455 frame decoder on an RFC 8441 stream. */
    const val HTTP2_WEBSOCKET_DECODER = "http2WebSocketDecoder"

    /** Rejects text frames that are not valid UTF-8 on an RFC 8441 stream. */
    const val HTTP2_WEBSOCKET_UTF8_VALIDATOR = "http2WebSocketUtf8Validator"

    /** WebSocket message-fragment aggregator on an RFC 8441 stream. */
    const val HTTP2_WEBSOCKET_AGGREGATOR = "http2WebSocketAggregator"
}
