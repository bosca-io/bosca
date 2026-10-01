@file:OptIn(bosca.di.annotation.InternalDI::class, bosca.core.annotations.Internal::class)

package bosca.sharedqueue.jobs

import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.queue.annotations.IJobDefinition
import bosca.serialization.UUID
import bosca.lock.DistributedLock
import io.mockk.mockk
import io.mockk.coEvery
import io.mockk.coVerify
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Exercises the interface default members ([JobExecutor.getLockId] /
 * [JobExecutor.skipExecutionIfLocked], the [JobListener] no-op hooks) and the
 * inline [Job] factory's default-argument path — the bits every concrete
 * implementation inherits without overriding.
 */
class InterfaceDefaultsTest {

    @Serializable
    private data class Def(val v: String = "x") : IJobDefinition

    @BeforeTest
    fun setUp() {
        ProviderRegistry.clear()
        provides<Json>(singleton = true) { Json }
    }

    @AfterTest
    fun tearDown() = ProviderRegistry.clear()

    @Test
    fun `JobExecutor defaults return no lock and do not skip`() = runTest {
        val executor = object : JobExecutor {
            override suspend fun execute() {}
        }
        assertNull(executor.getLockId())
        assertFalse(executor.skipExecutionIfLocked)
    }

    @Test
    fun `JobListener default hooks are no-ops`() = runTest {
        val listener = object : JobListener {}
        val job = InternalJobConstructor(Json.parseToJsonElement("{}"), DefaultsExecutor::class)
        val child = InternalJobConstructor(Json.parseToJsonElement("{}"), DefaultsExecutor::class)
        // Both defaults simply return without doing anything.
        listener.onStatusChanged(job, JobStatus.RUNNING, null)
        listener.onChildStatusChanged(job, child, JobStatus.COMPLETE)
    }

    @Test
    fun `Job factory applies default executorName and displayName`() = runTest {
        // Calling the inline factory without the optional args exercises its default-argument path.
        val job = Job(definition = Def(), executor = DefaultsExecutor::class)
        assertNull(job.executorName)
        assertNull(job.displayName)
        assertEquals(DefaultsExecutor::class, job.executor)
    }

    @Test
    fun `JobCallback listenerName defaults to null`() = runTest {
        val callback = JobCallback(listener = DefaultsListener::class)
        assertNull(callback.listenerName)
    }

    @Test
    fun `JobQueue timed dequeue delegates to the nonblocking operation`() = runTest {
        val job = InternalJobConstructor(Json.parseToJsonElement("{}"), DefaultsExecutor::class)
        val queue = DefaultsJobQueue(job)

        assertEquals(job, queue.dequeue(1.seconds))
        assertEquals(1, queue.dequeueCalls)
    }

    @Test
    fun `JobQueue releases only a lock still owned by the job instance`() = runTest {
        val queue = DefaultsJobQueue()
        val job = InternalJobConstructor(Json.parseToJsonElement("{}"), DefaultsExecutor::class)
        val held = mockk<DistributedLock>()
        coEvery { held.release() } returns true
        io.mockk.every { held.isHeld } returns true
        job.lock = held

        queue.releaseJobLock(job)

        coVerify(exactly = 1) { held.release() }
        assertNull(job.lock)

        val expired = mockk<DistributedLock>()
        io.mockk.every { expired.isHeld } returns false
        job.lock = expired
        queue.releaseJobLock(job)
        coVerify(exactly = 0) { expired.release() }
        assertNull(job.lock)

        queue.releaseJobLock(job)
        assertNull(job.lock)
    }
}

private class DefaultsExecutor : JobExecutor {
    override suspend fun execute() {}
}

private class DefaultsListener : JobListener

private class DefaultsJobQueue(private val next: Job? = null) : JobQueue {
    var dequeueCalls = 0
    override val name: String = "defaults"
    override suspend fun enqueue(job: Job): UUID = error("not used")
    override suspend fun enqueueLater(job: Job, timeout: Duration): UUID = error("not used")
    override suspend fun dequeue(): Job? {
        dequeueCalls++
        return next
    }
    override suspend fun <T> getJob(id: UUID, block: suspend (Job?) -> T): T = block(null)
    override suspend fun enqueueIfAbsent(job: Job): UUID = error("not used")
    override suspend fun setJob(job: Job) = Unit
    override suspend fun checkin(job: Job, lockRenew: Long): Boolean = false
    override suspend fun setDefinition(job: Job, definition: kotlinx.serialization.json.JsonElement) = Unit
    override suspend fun markFailed(job: Job, exception: Exception, retry: Boolean) = Unit
    override suspend fun markComplete(job: Job) = Unit
    override suspend fun markCancelled(id: UUID) = Unit
    override suspend fun checkForExpiredJobs(time: Long) = Unit
    override suspend fun expireAllJobs() = Unit
    override suspend fun clearJobLock(id: UUID) = Unit
    override suspend fun clearAllJobLocks() = Unit
}
