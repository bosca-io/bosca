package bosca.server.netty

import bosca.server.RequestBody
import bosca.server.sse.ServerSSESession
import bosca.server.websocket.WebSocketSession
import io.netty.channel.Channel
import io.netty.handler.codec.http2.Http2Connection
import io.netty.util.AttributeKey
import kotlinx.coroutines.CoroutineScope
import java.net.InetSocketAddress

/**
 * Per-channel state owned by the shared [NettyHttpHandler].
 *
 * The handler is `@Sharable`, so everything tied to one connection (or one HTTP/2 stream) lives
 * in these channel attributes. Netty attributes are safe to read and write from any thread.
 */
internal object ChannelAttributes {
    /**
     * Coroutine scope owned by the channel. Its job is a child of the engine scope and is
     * cancelled when the channel becomes inactive, cancelling every request on the connection.
     */
    val SCOPE: AttributeKey<CoroutineScope> = AttributeKey.valueOf("bosca.channelCoroutineScope")

    /** Body of the HTTP/1 request whose content chunks are currently arriving. */
    val CURRENT_BODY: AttributeKey<RequestBody> = AttributeKey.valueOf("bosca.currentBody")

    /** Open WebSocket session receiving this channel's frames. */
    val WS_SESSION: AttributeKey<WebSocketSession> = AttributeKey.valueOf("bosca.wsSession")

    /** Open SSE session; exempts the channel from idle closing. */
    val SSE_SESSION: AttributeKey<ServerSSESession> = AttributeKey.valueOf("bosca.sseSession")

    /** Remote IP address, resolved once when the channel becomes active. */
    val REMOTE_ADDRESS: AttributeKey<String> = AttributeKey.valueOf("bosca.remoteAddress")

    /** Whether the initial HTTP/2 HEADERS frame promised DATA; set before HTTP-object conversion. */
    val HTTP2_REQUEST_BODY_EXPECTED: AttributeKey<Boolean> =
        AttributeKey.valueOf("bosca.http2RequestBodyExpected")

    /** RFC 8441 transport of an extended-CONNECT stream. */
    val HTTP2_WEBSOCKET_TRANSPORT: AttributeKey<Http2WebSocketStreamHandler> =
        AttributeKey.valueOf("bosca.http2WebSocketTransport")

    /** Parent-connection property key marking which HTTP/2 streams are long-lived (see [Http2LongLivedStreams]). */
    val HTTP2_LONG_LIVED_STREAM_PROPERTY: AttributeKey<Http2Connection.PropertyKey> =
        AttributeKey.valueOf("bosca.http2LongLivedStreamPropertyKey")
}

/** Returns the channel-owned coroutine scope installed when the channel became active. */
internal fun Channel.boscaScope(): CoroutineScope =
    checkNotNull(attr(ChannelAttributes.SCOPE).get()) { "Channel coroutine scope was not initialized" }

/** Returns the remote IP address resolved at activation, resolving it now if activation was skipped. */
internal fun Channel.remoteIpAddress(): String? =
    attr(ChannelAttributes.REMOTE_ADDRESS).get() ?: resolveRemoteIpAddress()

/** Extracts the remote IP address from the channel's socket address. */
internal fun Channel.resolveRemoteIpAddress(): String? =
    (remoteAddress() as? InetSocketAddress)?.address?.hostAddress
