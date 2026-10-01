package bosca.sharedqueue.jobs.listeners

import bosca.core.annotations.Internal
import bosca.lock.DistributedLock
import bosca.lock.DistributedLockFactory
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.InternalJobConstructor
import bosca.sharedqueue.jobs.Job
import bosca.sharedqueue.jobs.JobExecutor
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.JobStatus
import bosca.sharedqueue.jobs.asCoroutineContext
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

class RunChildOnCompleteListenerTest {

    private val lockFactory = mockk<DistributedLockFactory>(relaxed = true)
    private val queue = mockk<JobQueue>(relaxed = true)
    private val listener = RunChildOnCompleteListener(lockFactory)

    @AfterTest
    fun tearDown() = unmockkAll()

    /** A locked child (id + a held lock) so the listener's enqueue path runs without minting a new lock. */
    @OptIn(Internal::class)
    private fun child(runOnFailure: Boolean = false): Job {
        val job = InternalJobConstructor(Json.parseToJsonElement("{}"), RunChildJob::class)
        if (runOnFailure) job.setRunOnFailure(true)
        job.setId(UUID.random())
        job.lock = mockk<DistributedLock>(relaxed = true) { every { isHeld } returns true }
        return job
    }

    @OptIn(Internal::class)
    private fun parentWith(vararg children: Job): Job {
        // id stays NIL so the parent is "locked" and addChild is legal; the listener fetches it via getJob.
        val parent = InternalJobConstructor(Json.parseToJsonElement("{}"), RunChildJob::class)
        children.forEach { parent.addChild(it) }
        return parent
    }

    private suspend fun fire(parent: Job, status: JobStatus) {
        coEvery { queue.getJob<Unit>(any(), any()) } coAnswers {
            secondArg<suspend (Job?) -> Unit>().invoke(parent)
        }
        withContext(queue.asCoroutineContext(parent)) {
            listener.onStatusChanged(parent, status, null)
        }
    }

    @Test
    fun complete_enqueues_every_child() = runTest {
        val plain = child()
        val onFailure = child(runOnFailure = true)
        fire(parentWith(plain, onFailure), JobStatus.COMPLETE)
        coVerify { queue.enqueue(plain) }
        coVerify { queue.enqueue(onFailure) }
    }

    @Test
    fun terminal_failure_enqueues_only_runOnFailure_children() = runTest {
        val plain = child()
        val onFailure = child(runOnFailure = true)
        fire(parentWith(plain, onFailure), JobStatus.FAILED_AND_COMPLETE)
        coVerify(exactly = 0) { queue.enqueue(plain) }
        coVerify { queue.enqueue(onFailure) }
    }

    @Test
    fun retryable_failure_enqueues_nothing() = runTest {
        fire(parentWith(child(runOnFailure = true)), JobStatus.FAILED)
        coVerify(exactly = 0) { queue.enqueue(any()) }
    }

    @Test
    fun running_enqueues_nothing() = runTest {
        fire(parentWith(child(runOnFailure = true)), JobStatus.RUNNING)
        coVerify(exactly = 0) { queue.enqueue(any()) }
    }

    /** A child with no attached lock, forcing the listener to mint one via the lock factory. */
    @OptIn(Internal::class)
    private fun locklessChild(): Job {
        val job = InternalJobConstructor(Json.parseToJsonElement("{}"), RunChildJob::class)
        job.setId(UUID.random())
        return job
    }

    @Test
    fun mints_a_lock_when_the_child_has_none() = runTest {
        val child = locklessChild()
        // newJobLock returns a lock that renews successfully so the enqueue proceeds.
        val minted = mockk<DistributedLock>(relaxed = true) {
            every { isHeld } returns false
            coEvery { renew(any()) } returns true
            coEvery { release() } returns true
        }
        coEvery { lockFactory.create(any()) } returns minted
        coEvery { minted.acquire(any(), any(), any()) } returns true

        fire(parentWith(child), JobStatus.COMPLETE)

        coVerify { queue.enqueue(child) }
        coVerify { minted.release() }
    }

    @Test
    fun errors_when_the_minted_lock_cannot_be_renewed() = runTest {
        val child = locklessChild()
        val minted = mockk<DistributedLock>(relaxed = true) {
            every { isHeld } returns false
            coEvery { renew(any()) } returns false
            coEvery { acquire(any(), any(), any()) } returns true
            coEvery { release() } returns true
        }
        coEvery { lockFactory.create(any()) } returns minted

        assertFailsWith<IllegalStateException> {
            fire(parentWith(child), JobStatus.COMPLETE)
        }
        // The lock is still released even on the failure path.
        coVerify { minted.release() }
        coVerify(exactly = 0) { queue.enqueue(child) }
    }
}

private class RunChildJob : JobExecutor {
    override suspend fun execute() {}
}
