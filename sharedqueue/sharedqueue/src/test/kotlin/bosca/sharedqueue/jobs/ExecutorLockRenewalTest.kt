package bosca.sharedqueue.jobs

import bosca.lock.DistributedLock
import bosca.serialization.UUID
import io.mockk.Called
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse

/**
 * Virtual-time unit tests for [renewExecutorLockLoop].
 *
 * Testing the real [JobRunner] end-to-end would require a Redis container,
 * the full DI stack, and ~45 seconds of wall-clock per test. Extracting the
 * renewal logic into a standalone suspend function lets us drive it under
 * [runTest]'s virtual clock and assert on the exact number of `renew` calls
 * without waiting.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExecutorLockRenewalTest {

    private val ttl = 120_000L
    private val interval = 45_000L
    private val jobId = UUID.random()

    @Test
    fun `renews the lock repeatedly while the enclosing coroutine stays active`() = runTest(StandardTestDispatcher()) {
        val lock = mockk<DistributedLock>()
        coEvery { lock.renew(ttl) } returns true

        val loop = launch {
            renewExecutorLockLoop(lock, ttl, interval, jobId)
        }

        // Before the first interval elapses, no renew has been issued.
        runCurrent()
        coVerify(exactly = 0) { lock.renew(any()) }

        // Advance four intervals — expect four successful renewals.
        advanceTimeBy(interval * 4)
        runCurrent()
        coVerify(exactly = 4) { lock.renew(ttl) }

        loop.cancelAndJoin()
    }

    @Test
    fun `stops renewing and exits when renew returns false`() = runTest(StandardTestDispatcher()) {
        val lock = mockk<DistributedLock>()
        // First two renewals succeed, third reports the lock has been lost.
        coEvery { lock.renew(ttl) } returnsMany listOf(true, true, false)

        val loop = launch {
            renewExecutorLockLoop(lock, ttl, interval, jobId)
        }

        advanceTimeBy(interval * 10)
        runCurrent()

        // Loop exited after the first `false`, so renew was called exactly 3
        // times regardless of how much virtual time has elapsed beyond.
        coVerify(exactly = 3) { lock.renew(ttl) }
        assertFalse(loop.isActive, "loop should terminate after a failed renewal")
    }

    @Test
    fun `exits cleanly without calling release when cancelled`() = runTest(StandardTestDispatcher()) {
        val lock = mockk<DistributedLock>(relaxed = true)
        coEvery { lock.renew(ttl) } returns true

        val loop = launch {
            renewExecutorLockLoop(lock, ttl, interval, jobId)
        }

        // Let one renewal fire so the loop is definitely "in" the body.
        advanceTimeBy(interval + 1)
        runCurrent()
        coVerify(exactly = 1) { lock.renew(ttl) }

        loop.cancelAndJoin()
        assertFalse(loop.isActive)

        // The renewal loop must not release the lock on cancel — that is
        // the caller's responsibility via the surrounding try/finally.
        coVerify(exactly = 0) { lock.release() }
    }

    @Test
    fun `a transient renewal exception is swallowed and the loop keeps going`() = runTest(StandardTestDispatcher()) {
        val lock = mockk<DistributedLock>()
        coEvery { lock.renew(ttl) } throws RuntimeException("redis blip") andThenThrows RuntimeException("still blipping") andThen true

        val loop = launch {
            renewExecutorLockLoop(lock, ttl, interval, jobId)
        }

        advanceTimeBy(interval * 3)
        runCurrent()

        // Three attempts: two threw, one succeeded. Loop is still alive.
        coVerify(exactly = 3) { lock.renew(ttl) }

        loop.cancelAndJoin()
    }

    @Test
    fun `never touches the lock when cancelled before the first interval`() = runTest(StandardTestDispatcher()) {
        val lock = mockk<DistributedLock>()

        val loop = launch {
            renewExecutorLockLoop(lock, ttl, interval, jobId)
        }

        // Cancel before the first renewal could possibly fire.
        advanceTimeBy(interval / 2)
        loop.cancelAndJoin()

        coVerify { lock wasNot Called }
    }
}
