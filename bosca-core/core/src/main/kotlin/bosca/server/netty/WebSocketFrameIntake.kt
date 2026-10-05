package bosca.server.netty

import bosca.server.websocket.CloseReason
import bosca.server.websocket.WebSocketFrame
import bosca.server.websocket.WebSocketSession
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame
import io.netty.handler.codec.http.websocketx.WebSocketFrame as NettyWebSocketFrame
import org.slf4j.LoggerFactory

/**
 * Delivers decoded (and aggregated) Netty WebSocket frames to the channel's [WebSocketSession].
 *
 * Runs on the channel event loop. Frame payloads are copied out before returning because the
 * caller releases the Netty frame afterwards.
 */
internal object WebSocketFrameIntake {
    private val log = LoggerFactory.getLogger(WebSocketFrameIntake::class.java)

    /** Pause socket reads once more than this many data frames are waiting for the route. */
    private const val HIGH_WATERMARK = 384

    fun deliver(ctx: ChannelHandlerContext, frame: NettyWebSocketFrame) {
        val session = ctx.channel().attr(ChannelAttributes.WS_SESSION).get() ?: return
        when (frame) {
            is TextWebSocketFrame -> deliverData(ctx, session, WebSocketFrame.Text(frame.text()))
            is BinaryWebSocketFrame -> deliverData(ctx, session, WebSocketFrame.Binary(frame.content().copyBytes()))
            is PingWebSocketFrame -> answerPing(ctx, session, frame.content().copyBytes())
            // Counted like data: an uncounted frame could grow the queue without ever pausing reads.
            is PongWebSocketFrame -> deliverData(ctx, session, WebSocketFrame.Pong(frame.content().copyBytes()))
            is CloseWebSocketFrame -> {
                // A close frame may carry no status (a plain `ws.close()` in a browser sends none);
                // RFC 6455 reports that as 1005 No Status Received, a code that is never sent.
                val statusCode = frame.statusCode().takeIf { it >= 0 } ?: CloseReason.Codes.NO_STATUS_RECEIVED
                val reasonText = frame.reasonText().orEmpty()
                // Echo the close (RFC 6455; nothing is sent if our own close already went out) and
                // record the peer's reason, then hand the frame to the route and end its frame loop.
                // Teardown waits for the route; see WebSocketSession.closeFromServer for why the
                // route is woken last.
                session.writeClose(statusCode, reasonText)
                session.closeReason.complete(CloseReason(statusCode, reasonText))
                session._incoming.trySend(WebSocketFrame.Close(statusCode, reasonText))
                session._incoming.close()
            }
        }
    }

    /**
     * Queues a text, binary or pong frame for the route, pausing reads once the route falls [HIGH_WATERMARK] frames
     * behind; [WebSocketSession.frameConsumed] resumes them. The queue itself is unbounded: frames
     * already decoded from the current read still arrive after the pause (a single read can hold
     * hundreds of small messages), and closing on them would punish a burst the pause absorbs.
     */
    private fun deliverData(ctx: ChannelHandlerContext, session: WebSocketSession, frame: WebSocketFrame) {
        // Once a close frame has gone out (ours or the echo of the peer's), data frames are discarded
        // (RFC 6455 §1.4): the route is closing, and reads resume then so the peer's reply is read.
        if (session.closeWriteFuture.get() != null) return
        // Counted before it is queued: the route may consume it (and count it off) at once.
        val pending = session.pendingFrameCount.incrementAndGet()
        // Fails only once the route stopped reading (the queue is closed); the frame is dropped then.
        if (session._incoming.trySend(frame).isFailure) {
            session.pendingFrameCount.decrementAndGet()
            return
        }
        if (pending > HIGH_WATERMARK) {
            ctx.channel().pauseReads(ChannelReadPauseReason.WEBSOCKET)
            if (pending == HIGH_WATERMARK + 1) {
                log.debug("WebSocket backpressure: pausing reads (pending={})", pending)
            }
        }
    }

    /**
     * Answers a ping with a pong, unless a close frame already went out (nothing follows it). While
     * the peer is not reading (the channel is unwritable), only the latest ping is kept, and it is
     * answered once the channel drains ([answerDeferredPing]): RFC 6455 §5.5.3 lets an endpoint
     * answer just the most recent ping, and queueing a pong per ping would let a client that sends
     * pings without reading grow the server's memory without bound.
     */
    private fun answerPing(ctx: ChannelHandlerContext, session: WebSocketSession, payload: ByteArray) {
        if (session.closeWriteFuture.get() != null) return
        if (ctx.channel().isWritable) {
            session.deferredPingPayload = null
            ctx.writeAndFlush(PongWebSocketFrame(Unpooled.wrappedBuffer(payload)))
        } else {
            session.deferredPingPayload = payload
        }
    }

    /** Answers the latest ping held back while the channel was unwritable; see [answerPing]. */
    fun answerDeferredPing(ctx: ChannelHandlerContext) {
        val session = ctx.channel().attr(ChannelAttributes.WS_SESSION).get() ?: return
        val payload = session.deferredPingPayload ?: return
        if (ctx.channel().isWritable) answerPing(ctx, session, payload)
    }

    private fun ByteBuf.copyBytes(): ByteArray = ByteArray(readableBytes()).also(::readBytes)
}
