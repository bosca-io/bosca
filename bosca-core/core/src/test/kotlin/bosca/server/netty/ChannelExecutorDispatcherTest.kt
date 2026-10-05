package bosca.server.netty

import io.mockk.every
import io.mockk.mockk
import io.netty.channel.DefaultEventLoop
import io.netty.util.concurrent.DefaultThreadFactory
import io.netty.util.concurrent.EventExecutor
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChannelExecutorDispatcherTest {

    private val loop = DefaultEventLoop(DefaultThreadFactory("test-loop", true))
    private val dispatcher = ChannelExecutorDispatcher(loop)

    @AfterTest
    fun tearDown() {
        loop.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS)
    }

    @Test
    fun `no dispatch is needed on the loop, and one is needed elsewhere`() {
        assertTrue(dispatcher.isDispatchNeeded(EmptyCoroutineContext), "The test thread is not the loop")
        val onLoop = AtomicReference<Boolean>()
        loop.submit { onLoop.set(dispatcher.isDispatchNeeded(EmptyCoroutineContext)) }.syncUninterruptibly()
        assertFalse(onLoop.get(), "Work already on the loop must run in place")
    }

    @Test
    fun `continuations from other threads run on the loop`() = runBlocking {
        val thread = withContext(dispatcher) { Thread.currentThread().name }
        assertTrue(thread.startsWith("test-loop"), "Expected the loop thread, got $thread")
    }

    @Test
    fun `a shutting-down loop hands continuations to another thread instead of dropping them`() {
        loop.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).syncUninterruptibly()
        val ran = CountDownLatch(1)
        val thread = AtomicReference<String>()
        dispatcher.dispatch(EmptyCoroutineContext) {
            thread.set(Thread.currentThread().name)
            ran.countDown()
        }
        assertTrue(ran.await(5, TimeUnit.SECONDS), "The continuation must still run")
        assertFalse(thread.get().startsWith("test-loop"))
    }

    @Test
    fun `a loop that rejects a task still gets the continuation run elsewhere`() {
        val racingLoop = mockk<EventExecutor> {
            every { execute(any()) } throws RejectedExecutionException("event executor terminated")
        }
        val ran = CountDownLatch(1)
        ChannelExecutorDispatcher(racingLoop).dispatch(EmptyCoroutineContext) { ran.countDown() }
        assertTrue(ran.await(5, TimeUnit.SECONDS), "The continuation must still run")
    }

    @Test
    fun `a loop in its shutdown quiet period still runs continuations itself`() {
        val quietLoop = DefaultEventLoop(DefaultThreadFactory("quiet-loop", true))
        try {
            // A long quiet period: the loop is shutting down but still accepts and runs tasks.
            quietLoop.shutdownGracefully(10, 10, TimeUnit.SECONDS)
            val thread = AtomicReference<String>()
            val ran = CountDownLatch(1)
            ChannelExecutorDispatcher(quietLoop).dispatch(EmptyCoroutineContext) {
                thread.set(Thread.currentThread().name)
                ran.countDown()
            }
            assertTrue(ran.await(5, TimeUnit.SECONDS))
            assertTrue(thread.get().startsWith("quiet-loop"), "Got ${thread.get()}")
        } finally {
            quietLoop.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS)
        }
    }

    @Test
    fun `dispatch preserves submission order on the loop`() {
        val order = java.util.Collections.synchronizedList(mutableListOf<Int>())
        val done = CountDownLatch(3)
        repeat(3) { index -> dispatcher.dispatch(EmptyCoroutineContext) { order += index; done.countDown() } }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(listOf(0, 1, 2), order)
    }
}
