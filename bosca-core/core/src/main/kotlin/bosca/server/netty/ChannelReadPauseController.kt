package bosca.server.netty

import io.netty.channel.Channel
import io.netty.util.AttributeKey

/** Independent consumers that may need to pause socket reads on an HTTP channel. */
internal enum class ChannelReadPauseReason {
    REQUEST_BODY,
    HTTP1_PIPELINE,
    HTTP2_WEBSOCKET_HANDSHAKE,
    WEBSOCKET,
}

/**
 * Coordinates Netty auto-read changes so one backpressure source cannot resume another.
 *
 * State changes are serialized on the channel event loop. A channel that started in manual-read
 * mode remains in manual-read mode after every Bosca-owned pause reason has been released.
 */
internal class ChannelReadPauseController private constructor(
    private val channel: Channel,
    private val restoreAutoRead: Boolean,
) {
    // Event loop only: pause and resume hop onto the event loop before touching it.
    private val reasons = mutableSetOf<ChannelReadPauseReason>()

    fun pause(reason: ChannelReadPauseReason) {
        onEventLoop {
            reasons += reason
            if (channel.config().isAutoRead) {
                channel.config().isAutoRead = false
            }
        }
    }

    fun resume(reason: ChannelReadPauseReason) {
        // A closed channel reads nothing more, and its event loop may already refuse tasks.
        if (!channel.isActive) return
        onEventLoop {
            val removed = reasons.remove(reason)
            if (removed && reasons.isEmpty() && restoreAutoRead && channel.isActive && !channel.config().isAutoRead) {
                // Enabling auto-read issues the next read itself.
                channel.config().isAutoRead = true
            }
        }
    }

    private fun onEventLoop(block: () -> Unit) {
        val eventLoop = channel.eventLoop()
        if (eventLoop.inEventLoop()) {
            block()
        } else {
            eventLoop.execute(block)
        }
    }

    companion object {
        private val KEY: AttributeKey<ChannelReadPauseController> =
            AttributeKey.valueOf("bosca.channelReadPauseController")

        fun get(channel: Channel): ChannelReadPauseController {
            val attribute = channel.attr(KEY)
            attribute.get()?.let { return it }
            val created = ChannelReadPauseController(channel, channel.config().isAutoRead)
            return attribute.setIfAbsent(created) ?: created
        }
    }
}

internal fun Channel.pauseReads(reason: ChannelReadPauseReason) =
    ChannelReadPauseController.get(this).pause(reason)

internal fun Channel.resumeReads(reason: ChannelReadPauseReason) =
    ChannelReadPauseController.get(this).resume(reason)
