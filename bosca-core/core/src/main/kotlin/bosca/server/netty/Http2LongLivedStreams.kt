package bosca.server.netty

import io.netty.channel.Channel
import io.netty.handler.codec.http2.Http2FrameCodec
import io.netty.handler.codec.http2.Http2StreamChannel

/**
 * Marks which streams of an HTTP/2 connection are long-lived and govern their own idleness: SSE
 * subscriptions (which send their own keep-alives) and RFC 8441 WebSockets (which have their own
 * idle handler, closing with 1001). The connection's idle policy leaves a connection carrying one
 * open, without exempting every stalled request, and without dropping a quiet WebSocket (and every
 * other stream) before its own idle close.
 *
 * Marks live on Netty's own stream objects and disappear with them, so no application-side
 * counter can drift from the HTTP/2 state machine. Both functions run on the connection's event
 * loop, which HTTP/2 stream channels share with their parent.
 */
internal object Http2LongLivedStreams {

    /** Marks [channel]'s stream as long-lived when it is an HTTP/2 stream; HTTP/1 channels are ignored. */
    fun track(channel: Channel) {
        val streamChannel = channel as? Http2StreamChannel ?: return
        val parent = streamChannel.parent()
        val frameCodec = parent.frameCodec() ?: return
        val propertyKeyAttribute = parent.attr(ChannelAttributes.HTTP2_LONG_LIVED_STREAM_PROPERTY)
        val propertyKey = propertyKeyAttribute.get() ?: run {
            val newPropertyKey = frameCodec.connection().newKey()
            propertyKeyAttribute.setIfAbsent(newPropertyKey) ?: newPropertyKey
        }
        frameCodec.connection().stream(streamChannel.stream().id())?.setProperty(propertyKey, true)
    }

    /** Returns whether any active stream on the HTTP/2 connection [channel] is marked long-lived. */
    fun hasActive(channel: Channel): Boolean {
        val frameCodec = channel.frameCodec() ?: return false
        val propertyKey = channel.attr(ChannelAttributes.HTTP2_LONG_LIVED_STREAM_PROPERTY).get() ?: return false
        var found = false
        frameCodec.connection().forEachActiveStream { stream ->
            found = stream.getProperty<Boolean>(propertyKey) == true
            !found
        }
        return found
    }

    private fun Channel.frameCodec(): Http2FrameCodec? =
        pipeline().get(PipelineHandlerNames.HTTP2_FRAME_CODEC) as? Http2FrameCodec
}
