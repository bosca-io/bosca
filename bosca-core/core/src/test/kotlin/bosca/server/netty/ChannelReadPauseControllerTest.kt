package bosca.server.netty

import io.netty.channel.embedded.EmbeddedChannel
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChannelReadPauseControllerTest {

    @Test
    fun `one backpressure source cannot resume another`() {
        val channel = EmbeddedChannel()
        try {
            channel.pauseReads(ChannelReadPauseReason.REQUEST_BODY)
            channel.pauseReads(ChannelReadPauseReason.HTTP1_PIPELINE)
            channel.runPendingTasks()
            assertFalse(channel.config().isAutoRead)

            channel.resumeReads(ChannelReadPauseReason.REQUEST_BODY)
            channel.runPendingTasks()
            assertFalse(channel.config().isAutoRead)

            channel.resumeReads(ChannelReadPauseReason.HTTP1_PIPELINE)
            channel.runPendingTasks()
            assertTrue(channel.config().isAutoRead)
        } finally {
            channel.finishAndReleaseAll()
        }
    }

    @Test
    fun `WebSocket backpressure remains paused after HTTP2 handshake resumes`() {
        val channel = EmbeddedChannel()
        try {
            channel.pauseReads(ChannelReadPauseReason.HTTP2_WEBSOCKET_HANDSHAKE)
            channel.pauseReads(ChannelReadPauseReason.WEBSOCKET)
            channel.runPendingTasks()
            assertFalse(channel.config().isAutoRead)

            channel.resumeReads(ChannelReadPauseReason.HTTP2_WEBSOCKET_HANDSHAKE)
            channel.runPendingTasks()
            assertFalse(channel.config().isAutoRead)

            channel.resumeReads(ChannelReadPauseReason.WEBSOCKET)
            channel.runPendingTasks()
            assertTrue(channel.config().isAutoRead)
        } finally {
            channel.finishAndReleaseAll()
        }
    }

    @Test
    fun `manual read mode is preserved after Bosca pause reasons clear`() {
        val channel = EmbeddedChannel()
        try {
            channel.config().isAutoRead = false
            channel.pauseReads(ChannelReadPauseReason.HTTP1_PIPELINE)
            channel.resumeReads(ChannelReadPauseReason.HTTP1_PIPELINE)
            channel.runPendingTasks()

            assertFalse(channel.config().isAutoRead)
        } finally {
            channel.finishAndReleaseAll()
        }
    }

    @Test
    fun `unmatched resume cannot enable reads`() {
        val channel = EmbeddedChannel()
        try {
            channel.resumeReads(ChannelReadPauseReason.REQUEST_BODY)
            channel.runPendingTasks()
            channel.config().isAutoRead = false

            channel.resumeReads(ChannelReadPauseReason.REQUEST_BODY)
            channel.runPendingTasks()

            assertFalse(channel.config().isAutoRead)
        } finally {
            channel.finishAndReleaseAll()
        }
    }

    @Test
    fun `resume does not enable reads after channel closes`() {
        val channel = EmbeddedChannel()
        channel.pauseReads(ChannelReadPauseReason.REQUEST_BODY)
        channel.runPendingTasks()
        assertFalse(channel.config().isAutoRead)

        channel.close()
        channel.resumeReads(ChannelReadPauseReason.REQUEST_BODY)
        channel.runPendingTasks()

        assertFalse(channel.config().isAutoRead)
        channel.finishAndReleaseAll()
    }

    @Test
    fun `resume preserves an already enabled channel`() {
        val channel = EmbeddedChannel()
        try {
            channel.pauseReads(ChannelReadPauseReason.REQUEST_BODY)
            channel.runPendingTasks()
            channel.config().isAutoRead = true

            channel.resumeReads(ChannelReadPauseReason.REQUEST_BODY)
            channel.runPendingTasks()

            assertTrue(channel.config().isAutoRead)
        } finally {
            channel.finishAndReleaseAll()
        }
    }
}
