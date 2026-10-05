@file:OptIn(bosca.di.annotation.InternalDI::class, bosca.core.annotations.Internal::class)

package bosca.sharedqueue.jobs

import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.lock.DistributedLock
import bosca.lock.DistributedLockFactory
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/**
 * Covers [Job.newExecutor]'s named/unnamed DI resolution and the [newJobLock]
 * helper's wait/no-wait acquisition arms — the two places the queues turn a
 * persisted job back into a runnable, locked unit of work.
 */
class JobLockAndExecutorTest {

    private val lockFactory = mockk<DistributedLockFactory>()

    @AfterTest
    fun tearDown() {
        unmockkAll()
        ProviderRegistry.clear()
    }

    private fun lock(acquires: Boolean, tryAcquires: Boolean) = mockk<DistributedLock>(relaxed = true) {
        coEvery { acquire(any(), any(), any()) } returns acquires
        coEvery { tryAcquire(any()) } returns tryAcquires
    }

    // ----- newExecutor -----

    @Test
    fun `newExecutor resolves an unnamed executor`() = runTest {
        ProviderRegistry.clear()
        val executor = LookupExecutor()
        provides<LookupExecutor> { executor }
        val job = Job(definition = Json.parseToJsonElement("{}"), executor = LookupExecutor::class)
        assertSame(executor, job.newExecutor())
    }

    @Test
    fun `newExecutor resolves a named executor via executorName`() = runTest {
        ProviderRegistry.clear()
        val executor = LookupExecutor()
        provides<LookupExecutor>(name = "primary") { executor }
        val job = Job(
            definition = Json.parseToJsonElement("{}"),
            executor = LookupExecutor::class,
            executorName = "primary",
        )
        assertSame(executor, job.newExecutor())
    }

    // ----- newJobLock -----

    @Test
    fun `newJobLock without waiting acquires via tryAcquire`() = runTest {
        val theLock = lock(acquires = false, tryAcquires = true)
        coEvery { lockFactory.create(any()) } returns theLock
        assertSame(theLock, newJobLock(lockFactory, UUID.random(), 1000, wait = false))
    }

    @Test
    fun `newJobLock without waiting throws when tryAcquire fails`() = runTest {
        coEvery { lockFactory.create(any()) } returns lock(acquires = false, tryAcquires = false)
        assertFailsWith<LockAcquisitionException> {
            newJobLock(lockFactory, UUID.random(), 1000, wait = false)
        }
    }

    @Test
    fun `newJobLock with waiting acquires via acquire`() = runTest {
        val theLock = lock(acquires = true, tryAcquires = false)
        coEvery { lockFactory.create(any()) } returns theLock
        assertSame(theLock, newJobLock(lockFactory, UUID.random(), 1000, wait = true))
    }

    @Test
    fun `newJobLock with waiting throws when acquire fails`() = runTest {
        coEvery { lockFactory.create(any()) } returns lock(acquires = false, tryAcquires = false)
        assertFailsWith<LockAcquisitionException> {
            newJobLock(lockFactory, UUID.random(), 1000, wait = true)
        }
    }
}

private class LookupExecutor : JobExecutor {
    override suspend fun execute() {}
}
