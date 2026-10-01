@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.sharedqueue.jobs.listeners

import bosca.core.annotations.Internal
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.sharedqueue.jobs.*
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class NotifyParentListenerTest {

    private val listener = NotifyParentListener()
    private val mockQueue = mockk<JobQueue>()

    @AfterTest
    fun tearDown() {
        unmockkAll()
        ProviderRegistry.clear()
        JobQueueRegistry.clear()
    }

    @OptIn(Internal::class)
    private fun createJobWithParent(parentId: bosca.serialization.UUID): Job {
        val job = InternalJobConstructor(
            definition = Json.parseToJsonElement("{}"),
            executor = DummyExecutor::class
        )
        // Set parentId directly before setId (while id == UUID.NIL, job is considered locked)
        job.parentId = parentId
        job.setId(bosca.serialization.UUID.random())
        return job
    }

    @OptIn(Internal::class)
    private fun createJobWithoutParent(): Job {
        val job = InternalJobConstructor(
            definition = Json.parseToJsonElement("{}"),
            executor = DummyExecutor::class
        )
        job.setId(bosca.serialization.UUID.random())
        return job
    }

    @OptIn(Internal::class)
    private suspend fun <T> withJobContext(block: suspend () -> T): T {
        val dummyJob = InternalJobConstructor(
            definition = Json.parseToJsonElement("{}"),
            executor = DummyExecutor::class
        )
        return withContext(mockQueue.asCoroutineContext(dummyJob)) {
            block()
        }
    }

    @Test
    fun `does nothing when job has no parent`() = runTest {
        val job = createJobWithoutParent()

        withJobContext {
            listener.onStatusChanged(job, JobStatus.COMPLETE, null)
        }

        coVerify(exactly = 0) { mockQueue.getJob<Any>(any(), any()) }
    }

    @Test
    fun `does not throw when parent job is not found`() = runTest {
        val parentId = bosca.serialization.UUID.random()
        val job = createJobWithParent(parentId)

        coEvery { mockQueue.getJob<Any>(parentId, any()) } throws IllegalStateException("Job $parentId not found")

        withJobContext {
            listener.onStatusChanged(job, JobStatus.COMPLETE, null)
        }
    }

    @Test
    fun `does not throw when parent job lookup fails with any exception`() = runTest {
        val parentId = bosca.serialization.UUID.random()
        val job = createJobWithParent(parentId)

        coEvery { mockQueue.getJob<Any>(parentId, any()) } throws RuntimeException("Connection failed")

        withJobContext {
            listener.onStatusChanged(job, JobStatus.COMPLETE, null)
        }
    }

    @Test
    fun `calls getJob on parent when parent exists`() = runTest {
        val parentId = bosca.serialization.UUID.random()
        val childJob = createJobWithParent(parentId)

        coEvery { mockQueue.getJob<Unit>(parentId, any()) } coAnswers {
            @Suppress("UNCHECKED_CAST")
            val block = it.invocation.args[1] as suspend (Job?) -> Unit
            block(null) // parent returns null (handled gracefully by the block's it?.let)
        }

        withJobContext {
            listener.onStatusChanged(childJob, JobStatus.COMPLETE, null)
        }

        coVerify { mockQueue.getJob<Unit>(parentId, any()) }
    }

    @Test
    fun `does not throw on terminal-failure status when parent not found`() = runTest {
        val parentId = bosca.serialization.UUID.random()
        val job = createJobWithParent(parentId)

        coEvery { mockQueue.getJob<Any>(parentId, any()) } throws IllegalStateException("Job $parentId not found")

        withJobContext {
            listener.onStatusChanged(job, JobStatus.FAILED_AND_COMPLETE, "some error")
        }
    }

    @Test
    fun `notifies the parent on a terminal failure`() = runTest {
        val parentId = bosca.serialization.UUID.random()
        val job = createJobWithParent(parentId)

        coEvery { mockQueue.getJob<Unit>(parentId, any()) } coAnswers {
            @Suppress("UNCHECKED_CAST")
            (it.invocation.args[1] as suspend (Job?) -> Unit)(null)
        }

        withJobContext {
            listener.onStatusChanged(job, JobStatus.FAILED_AND_COMPLETE, "boom")
        }

        coVerify { mockQueue.getJob<Unit>(parentId, any()) }
    }

    @Test
    fun `does not notify the parent on a retryable FAILED`() = runTest {
        val parentId = bosca.serialization.UUID.random()
        val job = createJobWithParent(parentId)

        withJobContext {
            listener.onStatusChanged(job, JobStatus.FAILED, "transient")
        }

        // FAILED is non-terminal now (the job will retry), so the parent is not touched.
        coVerify(exactly = 0) { mockQueue.getJob<Any>(parentId, any()) }
    }

    @Test
    fun `looks the parent up on the PARENT's queue when the child completed on a different queue`() = runTest {
        // The release-relay hang: a workops backing job parked under a pipelines run job completed, but
        // the parent lookup ran against the child's own queue and silently found nothing. The child now
        // carries parentQueue; the listener must resolve that queue from the registry and use it.
        val parentId = bosca.serialization.UUID.random()
        val job = createJobWithParent(parentId)
        job.parentQueue = "pipelines"

        every { mockQueue.name } returns "workops"
        val parentQueue = mockk<JobQueue>()
        every { parentQueue.name } returns "pipelines"
        JobQueueRegistry.register(parentQueue)

        coEvery { parentQueue.getJob<Unit>(parentId, any()) } coAnswers {
            @Suppress("UNCHECKED_CAST")
            (it.invocation.args[1] as suspend (Job?) -> Unit)(null)
        }

        withJobContext {
            listener.onStatusChanged(job, JobStatus.COMPLETE, null)
        }

        coVerify(exactly = 1) { parentQueue.getJob<Unit>(parentId, any()) }
        coVerify(exactly = 0) { mockQueue.getJob<Any>(any(), any()) }
    }

    @Test
    fun `falls back to the child's queue when the parent queue is not live in this process`() = runTest {
        val parentId = bosca.serialization.UUID.random()
        val job = createJobWithParent(parentId)
        job.parentQueue = "pipelines"

        every { mockQueue.name } returns "workops"
        // Nothing registered under "pipelines" — the listener logs and falls back to the current queue.
        coEvery { mockQueue.getJob<Unit>(parentId, any()) } coAnswers {
            @Suppress("UNCHECKED_CAST")
            (it.invocation.args[1] as suspend (Job?) -> Unit)(null)
        }

        withJobContext {
            listener.onStatusChanged(job, JobStatus.COMPLETE, null)
        }

        coVerify(exactly = 1) { mockQueue.getJob<Unit>(parentId, any()) }
    }

    @Test
    fun `uses the current queue directly when the parent lives on the same queue`() = runTest {
        val parentId = bosca.serialization.UUID.random()
        val job = createJobWithParent(parentId)
        job.parentQueue = "workops"

        every { mockQueue.name } returns "workops"
        coEvery { mockQueue.getJob<Unit>(parentId, any()) } coAnswers {
            @Suppress("UNCHECKED_CAST")
            (it.invocation.args[1] as suspend (Job?) -> Unit)(null)
        }

        withJobContext {
            listener.onStatusChanged(job, JobStatus.COMPLETE, null)
        }

        coVerify(exactly = 1) { mockQueue.getJob<Unit>(parentId, any()) }
    }

    @Test
    fun `does not notify the parent while a COMPLETE child still has open children`() = runTest {
        // The release-relay orphaning: a child run job completes its delivery while its
        // own subtree is still open. Recording it COMPLETE in the parent would let the parent's join —
        // which only sees attach-time snapshots — read fully complete and delete the parent's state
        // while live descendants still need it. The notify must defer until the subtree settles.
        val parentId = bosca.serialization.UUID.random()
        val child = createJobWithParent(parentId)
        child.lock = heldLock()
        val grandchild = InternalJobConstructor(Json.parseToJsonElement("{}"), DummyExecutor::class)
        grandchild.lock = heldLock()
        grandchild.setId(bosca.serialization.UUID.random())
        child.addChild(grandchild)
        child.setStatus(JobStatus.COMPLETE)

        withJobContext {
            listener.onStatusChanged(child, JobStatus.COMPLETE, null)
        }

        coVerify(exactly = 0) { mockQueue.getJob<Any>(any(), any()) }
    }

    @Test
    fun `notifies the parent once a COMPLETE child's subtree has settled`() = runTest {
        val parentId = bosca.serialization.UUID.random()
        val child = createJobWithParent(parentId)
        child.lock = heldLock()
        val grandchild = InternalJobConstructor(Json.parseToJsonElement("{}"), DummyExecutor::class)
        grandchild.lock = heldLock()
        grandchild.setId(bosca.serialization.UUID.random())
        child.addChild(grandchild)
        grandchild.setStatus(JobStatus.COMPLETE)
        child.setStatus(JobStatus.COMPLETE)

        coEvery { mockQueue.getJob<Unit>(parentId, any()) } coAnswers {
            @Suppress("UNCHECKED_CAST")
            (it.invocation.args[1] as suspend (Job?) -> Unit)(null)
        }

        withJobContext {
            listener.onStatusChanged(child, JobStatus.COMPLETE, null)
        }

        coVerify(exactly = 1) { mockQueue.getJob<Unit>(parentId, any()) }
    }

    @Test
    fun `notifies the parent immediately on terminal failure even with open children`() = runTest {
        // A terminally-failed child's state is deleted right away — a deferred notify would never
        // come, so the parent must learn of the failure now regardless of open descendants.
        val parentId = bosca.serialization.UUID.random()
        val child = createJobWithParent(parentId)
        child.lock = heldLock()
        val grandchild = InternalJobConstructor(Json.parseToJsonElement("{}"), DummyExecutor::class)
        grandchild.lock = heldLock()
        grandchild.setId(bosca.serialization.UUID.random())
        child.addChild(grandchild)
        child.setStatus(JobStatus.FAILED_AND_COMPLETE)

        coEvery { mockQueue.getJob<Unit>(parentId, any()) } coAnswers {
            @Suppress("UNCHECKED_CAST")
            (it.invocation.args[1] as suspend (Job?) -> Unit)(null)
        }

        withJobContext {
            listener.onStatusChanged(child, JobStatus.FAILED_AND_COMPLETE, "boom")
        }

        coVerify(exactly = 1) { mockQueue.getJob<Unit>(parentId, any()) }
    }

    private fun heldLock(): bosca.lock.DistributedLock =
        mockk<bosca.lock.DistributedLock>(relaxed = true) { every { isHeld } returns true }

    @Test
    fun `fires the parent's onChildStatusChanged when a child reaches terminal`() = runTest {
        // The hook a coordinator drives off: when a child completes, the parent (loaded + locked here)
        // has its listeners' onChildStatusChanged invoked, before its fully-complete is re-evaluated.
        val parentId = bosca.serialization.UUID.random()
        val childId = bosca.serialization.UUID.random()

        CapturingChildListener.calls.clear()
        provides<CapturingChildListener> { CapturingChildListener() }

        // One child object, held-locked so it survives the parent's setId parent-propagation.
        val child = InternalJobConstructor(Json.parseToJsonElement("{}"), DummyExecutor::class)
        child.lock = mockk<bosca.lock.DistributedLock>(relaxed = true) { every { isHeld } returns true }
        child.setId(childId)

        // The parent carries the capturing drive-listener and contains the child (so setChildStatus
        // finds it). Its own status stays non-terminal, so it is not fully complete (setJob branch).
        val parent = InternalJobConstructor(Json.parseToJsonElement("{}"), DummyExecutor::class)
        parent.addChild(child)
        parent.addCallback(JobCallback(listener = CapturingChildListener::class))
        parent.setId(parentId)
        // getJob hands back a locked parent in production; mirror that so setChildStatus is legal.
        parent.lock = mockk<bosca.lock.DistributedLock>(relaxed = true) { every { isHeld } returns true }

        coEvery { mockQueue.getJob<Unit>(parentId, any()) } coAnswers {
            @Suppress("UNCHECKED_CAST")
            (it.invocation.args[1] as suspend (Job?) -> Unit)(parent)
        }
        coEvery { mockQueue.setJob(any()) } just Runs

        withJobContext {
            listener.onStatusChanged(child, JobStatus.COMPLETE, null)
        }

        assertEquals(1, CapturingChildListener.calls.size)
        val (observedParentId, observedChildId, observedStatus) = CapturingChildListener.calls.single()
        assertEquals(parentId, observedParentId)
        assertEquals(childId, observedChildId)
        assertEquals(JobStatus.COMPLETE, observedStatus)
    }
}

private class DummyExecutor : JobExecutor {
    override suspend fun execute() {}
}

/** A drive-style listener that records every onChildStatusChanged it is handed (statically, so the
 *  assertion sees the calls regardless of which instance the DI container hands back). */
private class CapturingChildListener : JobListener {
    override suspend fun onChildStatusChanged(job: Job, child: Job, status: JobStatus) {
        calls += Triple(job.getId(), child.getId(), status)
    }
    companion object {
        val calls = mutableListOf<Triple<bosca.serialization.UUID, bosca.serialization.UUID, JobStatus>>()
    }
}
