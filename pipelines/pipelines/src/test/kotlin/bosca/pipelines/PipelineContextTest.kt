@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package bosca.pipelines

import bosca.pipelines.node.PipelineResumeCorrelation
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.Job
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.asCoroutineContext
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PipelineContextTest {

    private val auth = AuthenticationContext(null, null)

    @Test
    fun `correlate requires a run and writes its return address`() {
        val job = mockk<Job>(relaxed = true)
        val missing = PipelineContext(auth, Json)
        assertFailsWith<IllegalStateException> { missing.correlate(job, "node") }

        val runId = UUID.random()
        val durable = PipelineContext(auth, Json, runId = runId)
        val encoded = slot<JsonElement>()
        every { job.setContext(capture(encoded)) } returns Unit
        durable.correlate(job, "node")

        val correlation = Json.decodeFromJsonElement(PipelineResumeCorrelation.serializer(), encoded.captured)
        assertEquals(runId, correlation.runId)
        assertEquals("node", correlation.nodeId)
    }

    @Test
    fun `enqueue attaches directly to an already locked run job`() = runTest {
        val parent = mockk<Job>(relaxed = true)
        val child = mockk<Job>(relaxed = true)
        val queue = mockk<JobQueue>(relaxed = true)
        val queuedId = UUID.random()
        coEvery { queue.enqueue(child) } returns queuedId
        val context = PipelineContext(auth, Json, runJob = parent)

        assertEquals(queuedId, context.enqueue(queue, child, runOnParentComplete = true))

        verify(exactly = 1) { parent.addChild(child, runOnParentComplete = true) }
        coVerify(exactly = 1) { queue.enqueue(child) }
    }

    @Test
    fun `enqueue loads and persists the parent through the child queue when only its id is known`() = runTest {
        val runJobId = UUID.random()
        val parent = mockk<Job>(relaxed = true)
        val child = mockk<Job>(relaxed = true)
        val queue = mockk<JobQueue>(relaxed = true)
        val queuedId = UUID.random()
        coEvery { queue.enqueue(child) } returns queuedId
        coEvery { queue.getJob<Unit>(runJobId, any()) } coAnswers {
            @Suppress("UNCHECKED_CAST")
            (invocation.args[1] as suspend (Job?) -> Unit).invoke(parent)
        }
        val context = PipelineContext(auth, Json, runJobId = runJobId)

        assertEquals(queuedId, context.enqueue(queue, child))

        verify(exactly = 1) { parent.addChild(child, runOnParentComplete = false) }
        coVerify(exactly = 1) { queue.setJob(parent) }
        coVerify(exactly = 1) { queue.enqueue(child) }
    }

    @Test
    fun `enqueue tolerates a missing persisted parent and still queues the child`() = runTest {
        val runJobId = UUID.random()
        val child = mockk<Job>(relaxed = true)
        val queue = mockk<JobQueue>(relaxed = true)
        coEvery { queue.enqueue(child) } returns UUID.random()
        coEvery { queue.getJob<Unit>(runJobId, any()) } coAnswers {
            @Suppress("UNCHECKED_CAST")
            (invocation.args[1] as suspend (Job?) -> Unit).invoke(null)
        }

        PipelineContext(auth, Json, runJobId = runJobId).enqueue(queue, child)

        coVerify(exactly = 0) { queue.setJob(any()) }
        coVerify(exactly = 1) { queue.enqueue(child) }
    }

    @Test
    fun `enqueue loads the parent from the driving run queue when the child uses another queue`() = runTest {
        val runJobId = UUID.random()
        val drivingJob = mockk<Job>(relaxed = true)
        val parent = mockk<Job>(relaxed = true)
        val child = mockk<Job>(relaxed = true)
        val runQueue = mockk<JobQueue>(relaxed = true)
        val childQueue = mockk<JobQueue>(relaxed = true)
        val queuedId = UUID.random()
        coEvery { childQueue.enqueue(child) } returns queuedId
        coEvery { runQueue.getJob<Unit>(runJobId, any()) } coAnswers {
            @Suppress("UNCHECKED_CAST")
            (invocation.args[1] as suspend (Job?) -> Unit).invoke(parent)
        }
        val context = PipelineContext(auth, Json, runJobId = runJobId)

        val result = withContext(runQueue.asCoroutineContext(drivingJob)) {
            context.enqueue(childQueue, child)
        }

        assertEquals(queuedId, result)
        coVerify(exactly = 1) { runQueue.setJob(parent) }
        coVerify(exactly = 0) { childQueue.getJob<Unit>(any(), any()) }
    }
}
