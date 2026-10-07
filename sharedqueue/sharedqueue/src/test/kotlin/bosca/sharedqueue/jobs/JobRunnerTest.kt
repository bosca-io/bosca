@file:OptIn(bosca.di.annotation.InternalDI::class, bosca.core.annotations.Internal::class)

package bosca.sharedqueue.jobs

import bosca.cache.CacheManager
import bosca.cache.RequestCacheSerializer
import bosca.db.ConnectionPool
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.lock.DistributedLock
import bosca.lock.DistributedLockFactory
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Drives [JobRunner.run] against a mocked [JobQueue] to cover the worker loop's
 * decision arms without a real backend: the terminal outcomes (complete / fail /
 * delayed-retry), the executor-level distributed-lock outcomes (PROCEED / DELAY /
 * SKIP), and the dequeue/checkin error handling.
 *
 * The runner uses its own cached-thread-pool dispatcher, so each test hands the
 * queue exactly one job (then `null` forever), waits on a [CompletableDeferred]
 * that the expected terminal queue call completes, then shuts the runner down.
 */
class JobRunnerTest {

    private val queue = mockk<JobQueue>(relaxed = true)
    private val lockFactory = mockk<DistributedLockFactory>(relaxed = true)

    @BeforeTest
    fun setUp() {
        ProviderRegistry.clear()
        provides<CacheManager> { mockk(relaxed = true) }
        provides<RequestCacheSerializer> { mockk(relaxed = true) }
        provides<ConnectionPool> { mockk(relaxed = true) }
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
        ProviderRegistry.clear()
    }

    private fun heldLock() = mockk<DistributedLock>(relaxed = true) {
        every { isHeld } returns true
        coEvery { renew(any()) } returns true
        coEvery { release() } returns true
        coEvery { tryAcquire(any()) } returns true
    }

    /** Hand [job] to the runner exactly once, then an empty queue forever. */
    private fun handOut(job: Job) {
        val handed = AtomicBoolean(false)
        coEvery { queue.dequeue() } coAnswers { if (handed.compareAndSet(false, true)) job else null }
        coEvery { queue.checkin(any(), any()) } returns true
    }

    private fun newJob(executor: kotlin.reflect.KClass<out JobExecutor>, lock: DistributedLock? = heldLock()): Job {
        val job = Job(definition = Json.parseToJsonElement("{}"), executor = executor)
        job.lock = lock
        job.setId(UUID.random())
        return job
    }

    /** Run the runner until [signal] fires (or fail after a generous timeout), then shut it down. */
    private fun runUntil(signal: CompletableDeferred<Unit>) {
        val runner = JobRunner(queue, 1, lockFactory)
        runner.run()
        try {
            runBlocking { withTimeout(20.seconds) { signal.await() } }
        } finally {
            runner.shutdown()
        }
    }

    @Test
    fun `executes a job and marks it complete`() {
        val ran = AtomicInteger(0)
        provides<ProceedExecutor> { ProceedExecutor(ran) }
        val job = newJob(ProceedExecutor::class)
        val done = CompletableDeferred<Unit>()
        coEvery { queue.markComplete(job) } coAnswers { done.complete(Unit) }
        handOut(job)

        runUntil(done)

        assertEquals(1, ran.get())
        coVerify { queue.markComplete(job) }
    }

    @Test
    fun `a FailException is a permanent, non-retryable failure`() {
        provides<FailExecutor> { FailExecutor() }
        val job = newJob(FailExecutor::class)
        val failed = CompletableDeferred<Unit>()
        coEvery { queue.markFailed(job, any(), false) } coAnswers { failed.complete(Unit) }
        handOut(job)

        runUntil(failed)

        coVerify { queue.markFailed(job, any(), retry = false) }
    }

    @Test
    fun `an unexpected exception is a retryable failure`() {
        provides<BoomExecutor> { BoomExecutor() }
        val job = newJob(BoomExecutor::class)
        val failed = CompletableDeferred<Unit>()
        coEvery { queue.markFailed(job, any(), true) } coAnswers { failed.complete(Unit) }
        handOut(job)

        runUntil(failed)

        coVerify { queue.markFailed(job, any(), retry = true) }
    }

    @Test
    fun `a DelayException reschedules the job for later`() {
        provides<DelayExecutor> { DelayExecutor() }
        val job = newJob(DelayExecutor::class)
        val delayed = CompletableDeferred<Unit>()
        coEvery { queue.enqueueLater(job, any()) } coAnswers { delayed.complete(Unit); UUID.random() }
        handOut(job)

        runUntil(delayed)

        coVerify { queue.enqueueLater(job, any()) }
    }

    @Test
    fun `a missing job lock fails the job`() {
        provides<ProceedExecutor> { ProceedExecutor(AtomicInteger(0)) }
        // No lock attached → execute() hits its "missing lock" guard and fails retryably.
        val job = newJob(ProceedExecutor::class, lock = null)
        val failed = CompletableDeferred<Unit>()
        coEvery { queue.markFailed(job, any(), true) } coAnswers { failed.complete(Unit) }
        handOut(job)

        runUntil(failed)

        coVerify { queue.markFailed(job, any(), retry = true) }
    }

    // ----- executor-level distributed lock outcomes -----

    @Test
    fun `an executor lock that is free proceeds and completes`() {
        val ran = AtomicInteger(0)
        provides<LockingExecutor> { LockingExecutor(ran) }
        // The executor lock (runner-<id>) is free: tryAcquire succeeds → PROCEED.
        coEvery { lockFactory.create(any()) } returns heldLock()
        val job = newJob(LockingExecutor::class)
        val done = CompletableDeferred<Unit>()
        coEvery { queue.markComplete(job) } coAnswers { done.complete(Unit) }
        handOut(job)

        runUntil(done)

        assertEquals(1, ran.get(), "with the lock free the executor must run")
    }

    @Test
    fun `a held executor lock delays a non-skipping job`() {
        val ran = AtomicInteger(0)
        provides<LockingExecutor> { LockingExecutor(ran) }
        // The executor lock is held by a sibling: tryAcquire fails, skip=false → DELAY.
        coEvery { lockFactory.create(any()) } returns mockk<DistributedLock>(relaxed = true) {
            coEvery { tryAcquire(any()) } returns false
        }
        val job = newJob(LockingExecutor::class)
        val delayed = CompletableDeferred<Unit>()
        coEvery { queue.enqueueLater(job, any()) } coAnswers { delayed.complete(Unit); UUID.random() }
        handOut(job)

        runUntil(delayed)

        assertEquals(0, ran.get(), "the executor must not run while the lock is held")
        coVerify { queue.enqueueLater(job, any()) }
    }

    @Test
    fun `a held executor lock skips a skip-if-locked job`() {
        val ran = AtomicInteger(0)
        provides<SkippingExecutor> { SkippingExecutor(ran) }
        coEvery { lockFactory.create(any()) } returns mockk<DistributedLock>(relaxed = true) {
            coEvery { tryAcquire(any()) } returns false
        }
        val job = newJob(SkippingExecutor::class)
        val done = CompletableDeferred<Unit>()
        // SKIP falls through to markComplete without ever running the executor.
        coEvery { queue.markComplete(job) } coAnswers { done.complete(Unit) }
        handOut(job)

        runUntil(done)

        assertEquals(0, ran.get(), "a skip-if-locked job must be completed without running")
        coVerify { queue.markComplete(job) }
    }

    @Test
    fun `notifies status listeners when a job starts running`() {
        provides<ProceedExecutor> { ProceedExecutor(AtomicInteger(0)) }
        val runningSeen = CompletableDeferred<JobStatus>()
        provides<RunListener> { RunListener(runningSeen) }
        val job = newJob(ProceedExecutor::class)
        job.addCallback(JobCallback(listener = RunListener::class))
        val done = CompletableDeferred<Unit>()
        coEvery { queue.markComplete(job) } coAnswers { done.complete(Unit) }
        handOut(job)

        runUntil(done)

        // The runner's RUNNING notification fired the job's callback listener.
        runBlocking { withTimeout(5.seconds) { assertEquals(JobStatus.RUNNING, runningSeen.await()) } }
    }

    @Test
    fun `fails the job when its own lock cannot be renewed`() {
        provides<ProceedExecutor> { ProceedExecutor(AtomicInteger(0)) }
        // A lock that is not held and cannot be renewed → execute() aborts before running.
        val staleLock = mockk<DistributedLock>(relaxed = true) {
            every { isHeld } returns false
            coEvery { renew(any()) } returns false
        }
        val job = newJob(ProceedExecutor::class, lock = staleLock)
        val failed = CompletableDeferred<Unit>()
        coEvery { queue.markFailed(job, any(), true) } coAnswers { failed.complete(Unit) }
        handOut(job)

        runUntil(failed)

        coVerify { queue.markFailed(job, any(), retry = true) }
    }

    @Test
    fun `the job still completes when a checkin fails`() {
        provides<ProceedExecutor> { ProceedExecutor(AtomicInteger(0)) }
        val job = newJob(ProceedExecutor::class)
        val done = CompletableDeferred<Unit>()
        coEvery { queue.markComplete(job) } coAnswers { done.complete(Unit) }
        // checkin returning false exercises the checkin loop's error/log arm.
        coEvery { queue.checkin(any(), any()) } returns false
        val handed = AtomicBoolean(false)
        coEvery { queue.dequeue() } coAnswers { if (handed.compareAndSet(false, true)) job else null }

        runUntil(done)

        coVerify { queue.markComplete(job) }
    }

    @Test
    fun `the runner shuts down cleanly while idle`() {
        coEvery { queue.dequeue() } returns null
        coEvery { queue.checkin(any(), any()) } returns true
        val runner = JobRunner(queue, 2, lockFactory)
        runner.run()
        // Let the worker loops poll the empty queue, then stop them.
        Thread.sleep(300)
        runner.shutdown()
        Thread.sleep(200)
    }

    // ----- dequeue error handling -----

    @Test
    fun `does not dequeue another locked job while all workers are busy`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val extraDequeue = CompletableDeferred<Unit>()
        provides<WaitingExecutor> { WaitingExecutor(started, finish) }
        val first = newJob(WaitingExecutor::class)
        val next = newJob(WaitingExecutor::class)
        val calls = AtomicInteger(0)
        coEvery { queue.dequeue() } coAnswers {
            when (calls.getAndIncrement()) {
                0 -> first
                1 -> {
                    extraDequeue.complete(Unit)
                    next
                }
                else -> null
            }
        }
        coEvery { queue.checkin(any(), any()) } returns true
        val runner = JobRunner(queue, 1, lockFactory)
        runner.run()
        try {
            withTimeout(5.seconds) { started.await() }
            assertNull(
                withTimeoutOrNull(250.milliseconds) { extraDequeue.await() },
                "A locked delivery must not wait in the runner without check-in"
            )
            finish.complete(Unit)
            withTimeout(5.seconds) { extraDequeue.await() }
        } finally {
            runner.shutdown()
            finish.complete(Unit)
        }
    }

    @Test
    fun `the producer recovers from a dequeue error and keeps consuming`() {
        val ran = AtomicInteger(0)
        provides<ProceedExecutor> { ProceedExecutor(ran) }
        val job = newJob(ProceedExecutor::class)
        val done = CompletableDeferred<Unit>()
        coEvery { queue.markComplete(job) } coAnswers { done.complete(Unit) }
        coEvery { queue.checkin(any(), any()) } returns true

        // First dequeue throws (exercises the fetch error/backoff arm), then the job, then empty.
        val calls = AtomicInteger(0)
        coEvery { queue.dequeue() } coAnswers {
            when (calls.getAndIncrement()) {
                0 -> throw RuntimeException("transient fetch failure")
                1 -> job
                else -> null
            }
        }

        runUntil(done)

        coVerify { queue.markComplete(job) }
    }

    // ----- executors -----

    private class ProceedExecutor(private val ran: AtomicInteger) : JobExecutor {
        override suspend fun execute() { ran.incrementAndGet() }
    }

    private class WaitingExecutor(
        private val started: CompletableDeferred<Unit>,
        private val finish: CompletableDeferred<Unit>,
    ) : JobExecutor {
        override suspend fun execute() {
            started.complete(Unit)
            finish.await()
        }
    }

    private class FailExecutor : JobExecutor {
        override suspend fun execute(): Unit = throw FailException("permanent")
    }

    private class BoomExecutor : JobExecutor {
        override suspend fun execute(): Unit = throw RuntimeException("boom")
    }

    private class DelayExecutor : JobExecutor {
        override suspend fun execute(): Unit = throw DelayException(1.seconds)
    }

    private class LockingExecutor(private val ran: AtomicInteger) : JobExecutor {
        override suspend fun getLockId(): String = "entity-1"
        override suspend fun execute() { ran.incrementAndGet() }
    }

    private class SkippingExecutor(private val ran: AtomicInteger) : JobExecutor {
        override suspend fun getLockId(): String = "entity-1"
        override val skipExecutionIfLocked: Boolean get() = true
        override suspend fun execute() { ran.incrementAndGet() }
    }

    private class RunListener(private val seen: CompletableDeferred<JobStatus>) : JobListener {
        override suspend fun onStatusChanged(job: Job, status: JobStatus, errorMessage: String?) {
            seen.complete(status)
        }
    }
}
