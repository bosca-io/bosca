package bosca.kubernetes.service

import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.kubernetes.jobs.KubernetesJobRequest
import bosca.kubernetes.model.KubernetesJobExecution
import bosca.kubernetes.model.KubernetesJobExecutionStatus
import bosca.kubernetes.model.KubernetesJobResultStatus
import bosca.serialization.OffsetDateTime
import bosca.db.ConnectionManager
import bosca.kubernetes.repository.KubernetesJobExecutionRepository
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.Job
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.serialize
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement

@OptIn(InternalDI::class)
class KubernetesJobDispatchServiceImplTest {

    @AfterTest
    fun tearDown() {
        unmockkAll()
        ProviderRegistry.clear()
    }

    @Test
    fun `dispatch enqueues a controller-owned record without in-process history callbacks`() = runTest {
        provides<Json>(singleton = true) { Json }
        val queue = mockk<JobQueue>()
        val executions = mockk<KubernetesJobExecutionRepository>()
        val queued = slot<Job>()
        coEvery { queue.enqueue(capture(queued)) } answers {
            queued.captured.serialize().id
        }
        coEvery { executions.markPublished(any()) } returns Unit
        coEvery { executions.create(any(), "android", "ci-job-attempt-1", any()) } answers {
            KubernetesJobExecution(
                dispatchId = firstArg(),
                profile = "android",
                idempotencyKey = "ci-job-attempt-1",
                request = arg(3),
            )
        }

        val result = KubernetesJobDispatchServiceImpl(queue, executions, Json).dispatch(
            KubernetesJobRequest(
                profile = "android",
                idempotencyKey = "ci-job-attempt-1",
            )
        )

        assertEquals(queued.captured.getId(), result)
        assertTrue(queued.captured.disableEmitEvent)
        assertTrue(queued.captured.disableEnqueueCallbacks)
        coVerify(exactly = 1) {
            executions.create(result, "android", "ci-job-attempt-1", any())
        }
        coVerify(exactly = 1) { executions.markPublished(result) }
    }

    @Test
    fun `duplicate idempotency key returns the original dispatch without republishing`() = runTest {
        val queue = mockk<JobQueue>()
        val executions = mockk<KubernetesJobExecutionRepository>()
        val request = KubernetesJobRequest("android", "ci-job-attempt-1")
        val originalId = UUID.random()
        coEvery {
            executions.create(any(), "android", "ci-job-attempt-1", any())
        } returns KubernetesJobExecution(
            dispatchId = originalId,
            profile = "android",
            idempotencyKey = "ci-job-attempt-1",
            request = Json.encodeToJsonElement(KubernetesJobRequest.serializer(), request),
        )

        val result = KubernetesJobDispatchServiceImpl(queue, executions, Json).dispatch(request)

        assertEquals(originalId, result)
        coVerify(exactly = 0) { queue.enqueue(any()) }
        coVerify(exactly = 0) { executions.markPublished(any()) }
    }

    @Test
    fun `transactional dispatch publishes and marks through a fresh connection after commit`() = runTest {
        provides<Json>(singleton = true) { Json }
        mockkStatic("bosca.db.ConnectionManagerKt")
        mockkStatic("bosca.db.ConnectionPoolKt")
        val manager = mockk<ConnectionManager>()
        val queue = mockk<JobQueue>()
        val executions = mockk<KubernetesJobExecutionRepository>()
        val request = KubernetesJobRequest("gpu", "training-transaction")
        coEvery { bosca.db.connectionOrNull() } returns manager
        io.mockk.every { manager.inTransaction } returns true
        coEvery { bosca.db.afterCommit(any()) } coAnswers {
            firstArg<suspend () -> Unit>().invoke()
        }
        coEvery { bosca.db.withConnectionManager<Any?>(any()) } coAnswers {
            firstArg<suspend () -> Any?>().invoke()
        }
        coEvery { queue.enqueue(any()) } answers { firstArg<Job>().getId() }
        coEvery { executions.markPublished(any()) } returns Unit
        coEvery { executions.create(any(), request.profile, request.idempotencyKey, any()) } answers {
            KubernetesJobExecution(
                dispatchId = firstArg(),
                profile = request.profile,
                idempotencyKey = request.idempotencyKey,
                request = arg(3),
            )
        }

        val dispatchId = KubernetesJobDispatchServiceImpl(queue, executions, Json)
            .dispatch(request)

        coVerify(exactly = 1) { queue.enqueue(match { it.getId() == dispatchId }) }
        coVerify(exactly = 1) { executions.markPublished(dispatchId) }
        coVerify(exactly = 1) { bosca.db.withConnectionManager<Any?>(any()) }
    }

    @Test
    fun `nontransactional connection marks publication on the current connection`() = runTest {
        provides<Json>(singleton = true) { Json }
        mockkStatic("bosca.db.ConnectionManagerKt")
        mockkStatic("bosca.db.ConnectionPoolKt")
        val manager = mockk<ConnectionManager>()
        val queue = mockk<JobQueue>()
        val executions = mockk<KubernetesJobExecutionRepository>()
        val request = KubernetesJobRequest("gpu", "training-current-connection")
        coEvery { bosca.db.connectionOrNull() } returns manager
        io.mockk.every { manager.inTransaction } returns false
        coEvery { bosca.db.afterCommit(any()) } coAnswers {
            firstArg<suspend () -> Unit>().invoke()
        }
        coEvery { queue.enqueue(any()) } answers { firstArg<Job>().getId() }
        coEvery { executions.markPublished(any()) } returns Unit
        coEvery { executions.create(any(), request.profile, request.idempotencyKey, any()) } answers {
            KubernetesJobExecution(
                dispatchId = firstArg(),
                profile = request.profile,
                idempotencyKey = request.idempotencyKey,
                request = arg(3),
            )
        }

        val dispatchId = KubernetesJobDispatchServiceImpl(queue, executions, Json)
            .dispatch(request)

        coVerify { executions.markPublished(dispatchId) }
        coVerify(exactly = 0) { bosca.db.withConnectionManager<Any?>(any()) }
    }

    @Test
    fun `cancel persists intent before removing the durable queue record`() = runTest {
        val queue = mockk<JobQueue>()
        val executions = mockk<KubernetesJobExecutionRepository>()
        val dispatchId = UUID.random()
        coEvery { executions.requestCancellation(dispatchId) } returns KubernetesJobExecution(
            dispatchId = dispatchId,
            profile = "android",
            idempotencyKey = "ci-job-attempt-1",
            status = KubernetesJobExecutionStatus.CANCEL_REQUESTED,
        )
        coEvery { queue.markCancelled(dispatchId) } returns Unit

        KubernetesJobDispatchServiceImpl(queue, executions, Json).cancel(dispatchId)

        coVerify(exactly = 1) { executions.requestCancellation(dispatchId) }
        coVerify(exactly = 1) { queue.markCancelled(dispatchId) }
    }

    @Test
    fun `get execution returns durable lifecycle state`() = runTest {
        val queue = mockk<JobQueue>()
        val executions = mockk<KubernetesJobExecutionRepository>()
        val dispatchId = UUID.random()
        val execution = KubernetesJobExecution(
            dispatchId = dispatchId,
            profile = "gpu",
            idempotencyKey = "training-1",
            status = KubernetesJobExecutionStatus.FAILED,
            message = "ImagePullBackOff",
        )
        coEvery { executions.getById(dispatchId) } returns execution

        assertEquals(
            execution,
            KubernetesJobDispatchServiceImpl(queue, executions, Json).getExecution(dispatchId),
        )
    }

    @Test
    fun `get result exposes only terminal execution outcomes`() = runTest {
        val queue = mockk<JobQueue>()
        val executions = mockk<KubernetesJobExecutionRepository>()
        val dispatchId = UUID.random()
        val finishedAt = OffsetDateTime.now()
        coEvery { executions.getById(dispatchId) } returnsMany listOf(
            KubernetesJobExecution(
                dispatchId = dispatchId,
                profile = "gpu",
                idempotencyKey = "training-1",
                status = KubernetesJobExecutionStatus.RUNNING,
            ),
            KubernetesJobExecution(
                dispatchId = dispatchId,
                profile = "gpu",
                idempotencyKey = "training-1",
                status = KubernetesJobExecutionStatus.SUCCEEDED,
                finishedAt = finishedAt,
            ),
        )
        val service = KubernetesJobDispatchServiceImpl(queue, executions, Json)

        assertEquals(null, service.getResult(dispatchId))
        val result = service.getResult(dispatchId)
        assertEquals(KubernetesJobResultStatus.SUCCEEDED, result?.status)
        assertEquals("gpu", result?.profile)
        assertEquals("training-1", result?.idempotencyKey)
        assertEquals(finishedAt, result?.finishedAt)
    }

    @Test
    fun `get result returns null when the dispatch does not exist`() = runTest {
        val queue = mockk<JobQueue>()
        val executions = mockk<KubernetesJobExecutionRepository>()
        val dispatchId = UUID.random()
        coEvery { executions.getById(dispatchId) } returns null

        assertEquals(
            null,
            KubernetesJobDispatchServiceImpl(queue, executions, Json).getResult(dispatchId),
        )
    }
}
