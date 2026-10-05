package bosca.server.netty

import io.netty.buffer.Unpooled
import io.netty.channel.WriteBufferWaterMark
import io.netty.channel.embedded.EmbeddedChannel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WritabilityWaiterTest {

    private val channel = EmbeddedChannel().apply {
        config().writeBufferWaterMark = WriteBufferWaterMark(1, 2)
    }

    @AfterTest
    fun tearDown() {
        channel.finishAndReleaseAll()
    }

    /** Queues more than the high water mark without flushing, making the channel unwritable. */
    private fun fillOutboundBuffer() {
        channel.write(Unpooled.wrappedBuffer(ByteArray(16)))
        channel.runPendingTasks()
        assertFalse(channel.isWritable)
    }

    /** Flushes the queued bytes, so the channel becomes writable and fires the writability event. */
    private fun drainOutboundBuffer() {
        channel.flush()
        channel.runPendingTasks()
        assertTrue(channel.isWritable)
    }

    private fun installedWaiter() = channel.pipeline().get(PipelineHandlerNames.WRITABILITY_WAITER)

    @Test
    fun `writable channel returns immediately without installing a waiter`() = runTest {
        WritabilityWaiter.awaitWritable(channel)

        assertNull(installedWaiter())
    }

    @Test
    fun `inactive channel returns immediately without installing a waiter`() = runTest {
        fillOutboundBuffer()
        channel.close()

        WritabilityWaiter.awaitWritable(channel)

        assertNull(installedWaiter())
    }

    @Test
    fun `waiter resumes when the channel becomes writable and stays installed for reuse`() = runTest {
        fillOutboundBuffer()
        val first = launch { WritabilityWaiter.awaitWritable(channel) }
        runCurrent()
        channel.runPendingTasks()

        val waiter = assertNotNull(installedWaiter())
        assertFalse(first.isCompleted)

        drainOutboundBuffer()
        runCurrent()
        assertTrue(first.isCompleted)

        fillOutboundBuffer()
        val second = launch { WritabilityWaiter.awaitWritable(channel) }
        runCurrent()
        channel.runPendingTasks()
        assertSame(waiter, installedWaiter(), "Later backpressure must reuse the installed waiter")

        drainOutboundBuffer()
        runCurrent()
        assertTrue(second.isCompleted)
    }

    @Test
    fun `every concurrent waiter resumes on one writability change`() = runTest {
        fillOutboundBuffer()
        val waiters = List(3) { launch { WritabilityWaiter.awaitWritable(channel) } }
        runCurrent()
        channel.runPendingTasks()

        drainOutboundBuffer()
        runCurrent()

        assertTrue(waiters.all { it.isCompleted })
    }

    @Test
    fun `closing the channel resumes waiters`() = runTest {
        fillOutboundBuffer()
        val job = launch { WritabilityWaiter.awaitWritable(channel) }
        runCurrent()
        channel.runPendingTasks()

        channel.close()
        channel.runPendingTasks()
        runCurrent()

        assertTrue(job.isCompleted)
    }

    @Test
    fun `cancelling a wait withdraws it`() = runTest {
        fillOutboundBuffer()
        val job = launch { WritabilityWaiter.awaitWritable(channel) }
        runCurrent()
        channel.runPendingTasks()
        val waiter = channel.pipeline().get(WritabilityWaiter::class.java)
        assertEquals(1, waiter.waitingCount)

        job.cancelAndJoin()
        channel.runPendingTasks()

        assertEquals(0, waiter.waitingCount, "A cancelled wait must leave the queue")
        drainOutboundBuffer()
        runCurrent()
        assertTrue(job.isCancelled)
    }
}
