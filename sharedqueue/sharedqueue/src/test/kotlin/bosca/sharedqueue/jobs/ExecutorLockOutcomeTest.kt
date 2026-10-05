package bosca.sharedqueue.jobs

import bosca.lock.DistributedLock
import io.mockk.Called
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Unit tests for [acquireExecutorLockOrDecide].
 *
 * This helper is the pure decision core that the runner calls before it
 * commits to running an executor. It deliberately has no dependencies on
 * [JobRunner] itself, so the full lock-contention branching can be
 * exercised here without any coroutine dispatcher, DI stack, or job queue
 * — just a mocked [DistributedLock]. The runner's only responsibility
 * when wiring this in is to translate the three outcomes into the right
 * control flow (`throw DelayException`, fall through to `markComplete`,
 * or call `executor.execute`); those are straight-line branches that do
 * not merit additional tests of their own.
 */
class ExecutorLockOutcomeTest {

    private val ttl = 120_000L

    @Test
    fun `proceeds when no lock is requested regardless of skipIfLocked flag`() = runTest {
        val outcome = acquireExecutorLockOrDecide(lock = null, ttlMillis = ttl, skipIfLocked = false)
        assertEquals(ExecutorLockOutcome.PROCEED, outcome)

        val outcomeSkip = acquireExecutorLockOrDecide(lock = null, ttlMillis = ttl, skipIfLocked = true)
        assertEquals(ExecutorLockOutcome.PROCEED, outcomeSkip)
    }

    @Test
    fun `proceeds when tryAcquire succeeds`() = runTest {
        val lock = mockk<DistributedLock>()
        coEvery { lock.tryAcquire(ttl) } returns true

        val outcome = acquireExecutorLockOrDecide(lock, ttl, skipIfLocked = false)

        assertEquals(ExecutorLockOutcome.PROCEED, outcome)
        coVerify(exactly = 1) { lock.tryAcquire(ttl) }
    }

    @Test
    fun `returns SKIP when acquisition fails and executor opts in`() = runTest {
        val lock = mockk<DistributedLock>()
        coEvery { lock.tryAcquire(ttl) } returns false

        val outcome = acquireExecutorLockOrDecide(lock, ttl, skipIfLocked = true)

        assertEquals(ExecutorLockOutcome.SKIP, outcome)
        coVerify(exactly = 1) { lock.tryAcquire(ttl) }
    }

    @Test
    fun `returns DELAY when acquisition fails and executor does not opt in`() = runTest {
        val lock = mockk<DistributedLock>()
        coEvery { lock.tryAcquire(ttl) } returns false

        val outcome = acquireExecutorLockOrDecide(lock, ttl, skipIfLocked = false)

        assertEquals(ExecutorLockOutcome.DELAY, outcome)
        coVerify(exactly = 1) { lock.tryAcquire(ttl) }
    }

    @Test
    fun `lock is not touched when the lock argument is null`() = runTest {
        val lock = mockk<DistributedLock>()

        acquireExecutorLockOrDecide(lock = null, ttlMillis = ttl, skipIfLocked = true)

        coVerify { lock wasNot Called }
    }

    @Test
    fun `exceptions from tryAcquire propagate to the caller for normal retry handling`() = runTest {
        val lock = mockk<DistributedLock>()
        coEvery { lock.tryAcquire(ttl) } throws RuntimeException("redis down")

        val thrown = assertFailsWith<RuntimeException> {
            acquireExecutorLockOrDecide(lock, ttl, skipIfLocked = true)
        }
        assertEquals("redis down", thrown.message)
    }
}
