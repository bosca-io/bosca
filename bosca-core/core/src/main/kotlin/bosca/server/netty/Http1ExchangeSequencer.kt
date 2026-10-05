package bosca.server.netty

import io.netty.channel.ChannelDuplexHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelPromise
import io.netty.handler.codec.http.HttpRequest
import io.netty.handler.codec.http.HttpResponse
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpServerCodec
import io.netty.handler.codec.http.HttpStatusClass
import io.netty.handler.codec.http.HttpUtil
import io.netty.handler.codec.http.LastHttpContent
import io.netty.util.ReferenceCountUtil
import java.util.ArrayDeque

/**
 * Allows only one HTTP/1.x request/response exchange at a time on a connection.
 *
 * HTTP/1.1 requires pipelined responses to be written in request order. Bosca request handlers can
 * suspend, so dispatching every decoded request immediately would let a later handler write its
 * response first. This handler keeps later decoded requests on the channel event loop until the
 * preceding final response has been written. HTTP/2 does not use this handler because each request
 * has its own stream channel.
 *
 * A new instance must be installed for each connection.
 */
internal class Http1ExchangeSequencer(
    private val httpCodec: HttpServerCodec,
) : ChannelDuplexHandler() {

    // Event loop only: Netty invokes every handler method on the channel's event loop.
    private val pendingReads = ArrayDeque<Any>()
    private var exchangeActive = false
    private var finalResponseStarted = false
    private var switchingProtocolsResponse = false
    private var closingConnectionResponse = false
    private var replayScheduled = false
    private var replaying = false
    private var upgraded = false
    private var connectionClosing = false
    private var readsPaused = false

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        if (connectionClosing) {
            ReferenceCountUtil.release(msg)
            return
        }
        if (upgraded || replaying) {
            ctx.fireChannelRead(msg)
            return
        }

        if (pendingReads.isNotEmpty() || replayScheduled || msg is HttpRequest && exchangeActive) {
            pendingReads.addLast(msg)
            pauseReadsIfNeeded(ctx)
            return
        }

        if (msg is HttpRequest) {
            exchangeActive = true
        }
        ctx.fireChannelRead(msg)
    }

    override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
        if (!upgraded && msg is HttpResponse) {
            val status = msg.status()
            if (status == HttpResponseStatus.SWITCHING_PROTOCOLS ||
                status.codeClass() != HttpStatusClass.INFORMATIONAL
            ) {
                finalResponseStarted = true
                switchingProtocolsResponse = status == HttpResponseStatus.SWITCHING_PROTOCOLS
                closingConnectionResponse = !HttpUtil.isKeepAlive(msg)
            }
        }

        val completesExchange = !upgraded && exchangeActive && finalResponseStarted && msg is LastHttpContent
        val switchesProtocols = completesExchange && switchingProtocolsResponse
        val closesConnection = completesExchange && closingConnectionResponse

        ctx.write(msg, promise)

        if (completesExchange) {
            exchangeActive = false
            finalResponseStarted = false
            switchingProtocolsResponse = false
            closingConnectionResponse = false
            if (switchesProtocols) {
                upgraded = true
                releasePending(ctx)
            } else if (closesConnection) {
                connectionClosing = true
                releasePending(ctx)
            } else {
                scheduleReplay(ctx)
            }
        }
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        releasePending(ctx)
        ctx.fireChannelInactive()
    }

    override fun handlerRemoved(ctx: ChannelHandlerContext) {
        releasePending(ctx)
    }

    private fun scheduleReplay(ctx: ChannelHandlerContext) {
        if (replayScheduled || pendingReads.isEmpty()) return
        replayScheduled = true
        ctx.executor().execute {
            replayScheduled = false
            replayNextRequest(ctx)
        }
    }

    private fun replayNextRequest(ctx: ChannelHandlerContext) {
        if (upgraded || exchangeActive || pendingReads.isEmpty() || !ctx.channel().isActive) return

        var requestStarted = false
        replaying = true
        try {
            while (pendingReads.isNotEmpty()) {
                val next = pendingReads.peekFirst()
                if (requestStarted && next is HttpRequest) break

                val msg = pendingReads.removeFirst()
                resumeReadsIfNeeded(ctx)
                if (msg is HttpRequest) {
                    exchangeActive = true
                    requestStarted = true
                }

                // Start immediately after the decoder so handlers inserted there during a
                // WebSocket handshake still observe the replayed request content.
                val codecContext = ctx.pipeline().context(httpCodec)
                if (codecContext == null) {
                    ReferenceCountUtil.release(msg)
                    releasePending(ctx)
                    return
                }
                codecContext.fireChannelRead(msg)

                if (requestStarted && msg is LastHttpContent) break
            }
        } finally {
            replaying = false
        }

        if (!exchangeActive) {
            scheduleReplay(ctx)
        }
    }

    private fun pauseReadsIfNeeded(ctx: ChannelHandlerContext) {
        if (!readsPaused && pendingReads.size >= PAUSE_READ_COUNT) {
            readsPaused = true
            ctx.channel().pauseReads(ChannelReadPauseReason.HTTP1_PIPELINE)
        }
    }

    private fun resumeReadsIfNeeded(ctx: ChannelHandlerContext) {
        if (readsPaused && pendingReads.size <= RESUME_READ_COUNT) {
            readsPaused = false
            ctx.channel().resumeReads(ChannelReadPauseReason.HTTP1_PIPELINE)
        }
    }

    private fun releasePending(ctx: ChannelHandlerContext) {
        while (pendingReads.isNotEmpty()) {
            ReferenceCountUtil.release(pendingReads.removeFirst())
        }
        if (readsPaused) {
            readsPaused = false
            ctx.channel().resumeReads(ChannelReadPauseReason.HTTP1_PIPELINE)
        }
    }

    private companion object {
        const val PAUSE_READ_COUNT = 64
        const val RESUME_READ_COUNT = 32
    }
}
