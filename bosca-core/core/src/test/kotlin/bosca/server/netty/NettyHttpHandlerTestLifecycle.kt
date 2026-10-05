package bosca.server.netty

import io.netty.channel.ChannelHandlerContext

/** Delivers an inbound message after reproducing Netty's channel activation lifecycle. */
internal fun NettyHttpHandler.readInbound(ctx: ChannelHandlerContext, message: Any) {
    if (ctx.channel().attr(ChannelAttributes.SCOPE).get() == null) {
        channelActive(ctx)
    }
    channelRead(ctx, message)
}
