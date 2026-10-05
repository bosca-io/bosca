package bosca.server.netty

import io.netty.util.concurrent.DefaultThreadFactory
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Detects request executors that stay in one task longer than [thresholdMillis].
 *
 * Every half threshold it sends each [WatchedExecutor] a heartbeat task, unless its last one has
 * not run yet. An executor whose heartbeat has waited the threshold is stuck in one task (a
 * blocking call or long CPU work on an event loop, or every request thread busy in pool mode): the
 * watchdog reports it once with the stack traces of the threads involved, and again when it
 * recovers. A heartbeat is only a volatile write, so a healthy executor pays one tiny task per
 * period, and a stuck one holds at most one.
 *
 * It backs two features: the opt-in `bosca.server.blocked-thread-watchdog` diagnostic, and the
 * management listener's liveness probe, which asks [stalledExecutors] and so adds no work of its
 * own however often it is probed.
 */
internal class BlockedThreadWatchdog(
    private val thresholdMillis: Long,
    private val targets: List<WatchedExecutor>,
    private val report: (String) -> Unit = { message -> log.warn(message) },
    private val recovered: (String) -> Unit = { message -> log.info(message) },
    private val nanoTime: () -> Long = System::nanoTime,
) {
    // Per-target state. Heartbeat tasks only write `heartbeatThread` and clear `pendingSince`, both
    // volatile; `reported` belongs to the thread running the passes.
    private class State(val target: WatchedExecutor) {
        @Volatile var pendingSince: Long = NOT_PENDING
        @Volatile var heartbeatThread: Thread? = null
        var reported = false
    }

    private val states = targets.map(::State)
    private val thresholdNanos = TimeUnit.MILLISECONDS.toNanos(thresholdMillis)
    private val started = AtomicBoolean(false)

    // Written by start() and read by stop(), which may run on different threads.
    @Volatile
    private var passes: ScheduledFuture<*>? = null

    @Volatile
    private var ownScheduler: ScheduledExecutorService? = null

    /** Starts the periodic passes on [scheduler], or on a daemon thread of the watchdog's own when null. */
    fun start(scheduler: ScheduledExecutorService? = null) {
        if (!started.compareAndSet(false, true)) return
        val periodMillis = (thresholdMillis / 2).coerceAtLeast(1)
        val runner = scheduler
            ?: Executors.newSingleThreadScheduledExecutor(DefaultThreadFactory("bosca-blocked-thread-watchdog", true))
                .also { ownScheduler = it }
        passes = runner.scheduleWithFixedDelay(::checkSafely, periodMillis, periodMillis, TimeUnit.MILLISECONDS)
    }

    fun stop() {
        passes?.cancel(false)
        passes = null
        ownScheduler?.shutdownNow()
        ownScheduler = null
    }

    /** Names of the executors whose heartbeat has waited at least the threshold. Safe from any thread. */
    fun stalledExecutors(): List<String> {
        val now = nanoTime()
        return states.filter { state ->
            val pendingSince = state.pendingSince
            pendingSince != NOT_PENDING && now - pendingSince >= thresholdNanos
        }.map { it.target.name }
    }

    // A scheduled task that throws is never run again, so one failed pass must not end the watchdog.
    private fun checkSafely() {
        try {
            check()
        } catch (e: Exception) {
            log.error("Blocked-thread watchdog pass failed", e)
        }
    }

    /** Runs one pass; called on the scheduler's thread, and directly by tests. */
    internal fun check() {
        val now = nanoTime()
        for (state in states) {
            val pendingSince = state.pendingSince
            if (pendingSince == NOT_PENDING) {
                if (state.reported) {
                    state.reported = false
                    recovered("Request executor ${state.target.name} recovered")
                }
                state.pendingSince = now
                try {
                    state.target.submit(Runnable {
                        state.heartbeatThread = Thread.currentThread()
                        state.pendingSince = NOT_PENDING
                    })
                } catch (_: RejectedExecutionException) {
                    // Executor shut down: nothing to watch until the engine stops the watchdog.
                    state.pendingSince = NOT_PENDING
                }
                continue
            }
            val stalledMillis = TimeUnit.NANOSECONDS.toMillis(now - pendingSince)
            if (stalledMillis >= thresholdMillis && !state.reported) {
                state.reported = true
                report(state.target.describeStall(stalledMillis, thresholdMillis, state.heartbeatThread))
            }
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(BlockedThreadWatchdog::class.java)
        private const val NOT_PENDING = Long.MIN_VALUE
    }
}
