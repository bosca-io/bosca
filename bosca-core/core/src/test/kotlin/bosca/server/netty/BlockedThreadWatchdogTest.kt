package bosca.server.netty

import bosca.server.config.ApplicationConfig
import io.netty.channel.DefaultEventLoop
import io.netty.util.concurrent.DefaultThreadFactory
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BlockedThreadWatchdogTest {

    private val loop = DefaultEventLoop(DefaultThreadFactory("watched-loop", true))
    private val reports = CopyOnWriteArrayList<String>()
    private val recoveries = CopyOnWriteArrayList<String>()
    private var clockNanos = 0L

    private val heartbeats = AtomicInteger()

    private val watchdog = BlockedThreadWatchdog(
        thresholdMillis = 100,
        targets = listOf(
            WatchedExecutor("event loop 1", { heartbeat -> heartbeats.incrementAndGet(); loop.execute(heartbeat) }) { listOfNotNull(it) },
        ),
        report = { reports += it },
        recovered = { recoveries += it },
        nanoTime = { clockNanos },
    )

    @AfterTest
    fun tearDown() {
        loop.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS)
    }

    private fun advanceMillis(millis: Long) {
        clockNanos += TimeUnit.MILLISECONDS.toNanos(millis)
    }

    /** Waits until the blocking task's thread is parked inside `await`, so its stack trace shows it. */
    private fun awaitParked(thread: AtomicReference<Thread>) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (thread.get()?.state != Thread.State.TIMED_WAITING) {
            check(System.nanoTime() < deadline) { "The loop thread never blocked" }
            Thread.onSpinWait()
        }
    }

    /** Waits until every task queued on the loop so far has run. */
    private fun drainLoop() {
        loop.submit {}.syncUninterruptibly()
    }

    /** Blocks the loop until the returned latch is released, once its thread is parked. */
    private fun blockLoop(): CountDownLatch {
        val release = CountDownLatch(1)
        val blockedThread = AtomicReference<Thread>()
        loop.execute {
            blockedThread.set(Thread.currentThread())
            release.await(10, TimeUnit.SECONDS)
        }
        awaitParked(blockedThread)
        return release
    }

    @Test
    fun `a responsive loop is never reported`() {
        repeat(5) {
            watchdog.check()
            drainLoop()
            advanceMillis(500)
        }
        assertTrue(reports.isEmpty(), "Unexpected reports: $reports")
    }

    @Test
    fun `a blocked loop is reported once with its stack trace, then recovers`() {
        watchdog.check() // first heartbeat records which thread runs the loop
        drainLoop()

        val release = blockLoop()
        advanceMillis(10)
        watchdog.check() // heartbeat queues behind the blocking task
        advanceMillis(150)
        watchdog.check()
        watchdog.check() // still stalled: must not report twice

        assertEquals(1, reports.size, "Expected exactly one report, got $reports")
        val report = reports.single()
        assertTrue(report.contains("event loop 1"))
        assertTrue(report.contains("has not run a task for 150 ms"), report)
        assertTrue(report.contains("\"watched-loop"), "The report must name the blocked thread: $report")
        assertTrue(report.contains("CountDownLatch.await"), "The report must show the blocking frame: $report")

        release.countDown()
        drainLoop()
        watchdog.check()
        assertEquals(1, recoveries.size)
        assertTrue(recoveries.single().contains("event loop 1 recovered"))
    }

    @Test
    fun `liveness sees a stall only once a heartbeat has waited the threshold`() {
        watchdog.check()
        drainLoop()
        assertEquals(emptyList(), watchdog.stalledExecutors())

        val release = blockLoop()
        watchdog.check() // heartbeat queues behind the blocking task
        advanceMillis(99)
        assertEquals(emptyList(), watchdog.stalledExecutors(), "Not stalled before the threshold")
        advanceMillis(1)
        assertEquals(listOf("event loop 1"), watchdog.stalledExecutors())

        release.countDown()
        drainLoop()
        assertEquals(emptyList(), watchdog.stalledExecutors(), "Live again once the heartbeat runs")
    }

    @Test
    fun `a stuck executor holds at most one heartbeat however many passes run`() {
        val release = blockLoop()
        repeat(5) {
            watchdog.check()
            advanceMillis(100)
        }
        assertEquals(1, heartbeats.get(), "Passes must not queue heartbeats behind one that has not run")
        release.countDown()
        drainLoop()
        watchdog.check()
        assertEquals(2, heartbeats.get())
    }

    @Test
    fun `an executor that rejects heartbeats while shutting down is not stalled`() {
        val shuttingDown = BlockedThreadWatchdog(
            thresholdMillis = 100,
            targets = listOf(WatchedExecutor("event loop 2", { throw RejectedExecutionException("shutting down") }) { emptyList() }),
            nanoTime = { clockNanos },
        )
        shuttingDown.check()
        advanceMillis(1_000)
        assertEquals(emptyList(), shuttingDown.stalledExecutors())
    }

    @Test
    fun `passes run on the given scheduler until stopped`() {
        val passes = AtomicInteger()
        val onScheduler = BlockedThreadWatchdog(
            thresholdMillis = 2,
            targets = listOf(
                WatchedExecutor("event loop 1", { heartbeat ->
                    check(loop.inEventLoop()) { "Passes must run on the given scheduler" }
                    passes.incrementAndGet()
                    heartbeat.run()
                }) { emptyList() },
            ),
        )
        onScheduler.start(loop)
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (passes.get() < 2) {
                check(System.nanoTime() < deadline) { "Passes never ran" }
                Thread.sleep(1)
            }
        } finally {
            onScheduler.stop()
        }
        drainLoop()
        val afterStop = passes.get()
        Thread.sleep(20)
        assertEquals(afterStop, passes.get(), "No passes after stop")
    }

    @Test
    fun `liveness stall timeout defaults to 2 s and falls back on values outside 1 ms to 1 h`() {
        fun stallTimeout(yaml: String) =
            NettyServerSettings.from(ApplicationConfig.load(yaml.byteInputStream()), availableProcessors = 2)
                .managementLivenessStallTimeoutMillis

        assertEquals(2_000, stallTimeout(""))
        assertEquals(500, stallTimeout("bosca: { server: { management-liveness: { stall-timeout-ms: 500 } } }"))
        assertEquals(2_000, stallTimeout("bosca: { server: { management-liveness: { stall-timeout-ms: 0 } } }"))
        // Values past an hour could overflow the nanosecond arithmetic, so they fall back too.
        assertEquals(2_000, stallTimeout("bosca: { server: { management-liveness: { stall-timeout-ms: 9223372036854775807 } } }"))
    }

    @Test
    fun `the watchdog is disabled by default and its threshold defaults to 100 ms`() {
        val defaults = NettyServerSettings.from(ApplicationConfig.load("".byteInputStream()), availableProcessors = 2)
        assertFalse(defaults.blockedThreadWatchdogEnabled)
        assertEquals(100, defaults.blockedThreadWatchdogThresholdMillis)

        val enabled = NettyServerSettings.from(
            ApplicationConfig.load(
                "bosca: { server: { blocked-thread-watchdog: { enabled: true, threshold-ms: 250 } } }".byteInputStream(),
            ),
            availableProcessors = 2,
        )
        assertTrue(enabled.blockedThreadWatchdogEnabled)
        assertEquals(250, enabled.blockedThreadWatchdogThresholdMillis)

        assertEquals(
            100,
            NettyServerSettings.configuredWatchdogThresholdMillis(
                ApplicationConfig.load("bosca: { server: { blocked-thread-watchdog: { threshold-ms: 0 } } }".byteInputStream()),
            ),
        )
    }
}
