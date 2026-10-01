package bosca.server.netty

import io.netty.util.concurrent.EventExecutor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.RejectedExecutionException
import kotlin.coroutines.CoroutineContext

/** Where request handlers (routes, SSE, WebSocket sessions) run. Set with `bosca.server.request-dispatcher`. */
enum class RequestDispatcherMode {
    /**
     * Each connection's handlers run on that connection's Netty event loop: no thread handoff to
     * start a handler or to write its response. A handler that blocks or burns CPU also delays
     * network I/O for the other connections on that loop, so blocking calls must move to
     * `Dispatchers.IO` and database work to its own dispatcher (both suspend, freeing the loop).
     */
    EVENT_LOOP,

    /** Handlers run on the engine's CPU-sized `bosca-request` pool, one handoff in and one out per request. */
    POOL,
}

/**
 * Runs a channel's coroutines on the channel's event loop.
 *
 * Coroutines started or resumed on the loop run in place without a queue hop; continuations
 * arriving from other threads (a database or IO dispatcher finishing) are handed to the loop as
 * tasks. A continuation is never dropped: if the loop rejects the task (it has shut down), it
 * runs on [Dispatchers.IO] instead so cancellation and cleanup can still complete.
 */
internal class ChannelExecutorDispatcher(
    private val executor: EventExecutor,
) : CoroutineDispatcher() {

    override fun isDispatchNeeded(context: CoroutineContext): Boolean = !executor.inEventLoop()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        // A loop that is shutting down still runs tasks during its quiet period; only a rejection
        // moves the continuation off the loop it expects.
        try {
            executor.execute(block)
        } catch (_: RejectedExecutionException) {
            Dispatchers.IO.dispatch(context, block)
        }
    }

    override fun toString(): String = "ChannelExecutorDispatcher($executor)"
}
