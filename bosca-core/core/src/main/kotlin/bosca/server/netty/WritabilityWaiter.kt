package bosca.server.netty

import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.util.AttributeKey
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.ArrayDeque
import kotlin.coroutines.resume

/**
 * Resumes coroutines waiting for a channel's outbound buffer to drain.
 *
 * Installed at the tail of a channel's pipeline the first time a writer finds the channel
 * unwritable, and kept for the channel's lifetime so later backpressure episodes add no pipeline
 * mutations. Waiters resume when the channel becomes writable again or goes inactive.
 *
 * All state is confined to the channel's event loop.
 */
internal class WritabilityWaiter : ChannelInboundHandlerAdapter() {

    // Event loop only.
    private val waiters = ArrayDeque<CancellableContinuation<Unit>>()

    /** Coroutines waiting on this channel; event loop only. */
    internal val waitingCount: Int get() = waiters.size

    override fun channelWritabilityChanged(ctx: ChannelHandlerContext) {
        if (ctx.channel().isWritable) resumeAll()
        ctx.fireChannelWritabilityChanged()
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        resumeAll()
        ctx.fireChannelInactive()
    }

    override fun handlerRemoved(ctx: ChannelHandlerContext) {
        resumeAll()
    }

    private fun resumeAll() {
        while (waiters.isNotEmpty()) {
            val waiter = waiters.removeFirst()
            if (waiter.isActive) waiter.resume(Unit)
        }
    }

    companion object {
        private val KEY: AttributeKey<WritabilityWaiter> = AttributeKey.valueOf("bosca.writabilityWaiter")

        /**
         * Suspends until [channel] is writable or inactive. Returns immediately when it already is.
         * Cancelling the caller withdraws its wait.
         */
        suspend fun awaitWritable(channel: Channel) {
            if (channel.isWritable || !channel.isActive) return
            suspendCancellableCoroutine { continuation ->
                val eventLoop = channel.eventLoop()
                continuation.invokeOnCancellation {
                    eventLoop.execute { channel.attr(KEY).get()?.waiters?.remove(continuation) }
                }
                // Checking writability and registering on the event loop is atomic with respect to
                // writability events, so no change can slip between them.
                eventLoop.execute {
                    if (!channel.isActive || channel.isWritable) {
                        if (continuation.isActive) continuation.resume(Unit)
                    } else {
                        installedOn(channel).waiters.addLast(continuation)
                    }
                }
            }
        }

        /** Returns the channel's waiter, installing it at the pipeline tail on first use. Event loop only. */
        private fun installedOn(channel: Channel): WritabilityWaiter =
            channel.attr(KEY).get() ?: WritabilityWaiter().also { waiter ->
                channel.pipeline().addLast(PipelineHandlerNames.WRITABILITY_WAITER, waiter)
                channel.attr(KEY).set(waiter)
            }
    }
}
