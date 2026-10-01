@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.sharedqueue.jobs.jobs

import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.sharedqueue.jobs.Job
import bosca.sharedqueue.jobs.JobConfigurationEnqueuer
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.configuration.JobQueueNames
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Covers the hand-rolled [MultiJob] builder extensions and
 * [MultiJobExecutorEnqueuer]. Both resolve the common job queue through the DI
 * [ProviderRegistry] under [JobQueueNames.commonJobQueue] and always tag jobs
 * with the "Multi-Job" display name so admin rows match the KSP-generated path.
 */
class MultiJobExtensionsTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val queue = mockk<JobQueue>()

    @BeforeTest
    fun setUp() {
        ProviderRegistry.clear()
        provides<Json>(singleton = true) { json }
        provides<JobQueue>(name = JobQueueNames.commonJobQueue, singleton = true) { queue }
        coEvery { queue.enqueue(any<Job>()) } returns UUID.random()
        coEvery { queue.enqueueLater(any(), any()) } returns UUID.random()
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
        ProviderRegistry.clear()
    }

    private fun sampleDefinition() = MultiJob(
        jobs = listOf(MultiJobJob(name = "alpha", type = "metadata")),
        type = "metadata",
    )

    // ----- MultiJob.prepare / enqueue / enqueueLater -----

    @Test
    fun `prepare tags the job with the multi-job executor and display name`() = runTest {
        val job = sampleDefinition().prepare()
        assertEquals(MultiJobExecutor::class, job.executor)
        assertEquals("Multi-Job", job.displayName)
    }

    @Test
    fun `prepare with initializer runs it against the fresh job`() = runTest {
        val job = sampleDefinition().prepare { setRunOnFailure(true) }
        assertTrue(job.getRunOnFailure())
    }

    @Test
    fun `enqueue resolves the common queue and enqueues the job`() = runTest {
        val captured = slot<Job>()
        coEvery { queue.enqueue(capture(captured)) } returns UUID.random()

        val job = sampleDefinition().enqueue()

        coVerify(exactly = 1) { queue.enqueue(any<Job>()) }
        assertSame(job, captured.captured)
        assertEquals(MultiJobExecutor::class, captured.captured.executor)
    }

    @Test
    fun `enqueue with initializer applies it before enqueueing`() = runTest {
        val captured = slot<Job>()
        coEvery { queue.enqueue(capture(captured)) } returns UUID.random()

        sampleDefinition().enqueue { setRunOnFailure(true) }

        assertTrue(captured.captured.getRunOnFailure())
    }

    @Test
    fun `enqueueLater forwards the timeout`() = runTest {
        val timeout = slot<kotlin.time.Duration>()
        coEvery { queue.enqueueLater(any(), capture(timeout)) } returns UUID.random()

        sampleDefinition().enqueueLater(timeout = 45.seconds)

        assertEquals(45.seconds, timeout.captured)
    }

    @Test
    fun `enqueueLater with initializer applies it and forwards the timeout`() = runTest {
        val captured = slot<Job>()
        val timeout = slot<kotlin.time.Duration>()
        coEvery { queue.enqueueLater(capture(captured), capture(timeout)) } returns UUID.random()

        sampleDefinition().enqueueLater(initializer = { setRunOnFailure(true) }, timeout = 15.seconds)

        assertTrue(captured.captured.getRunOnFailure())
        assertEquals(15.seconds, timeout.captured)
    }

    // ----- MultiJobExecutorEnqueuer -----

    private fun configuration(): JsonElement =
        json.encodeToJsonElement(MultiJob.serializer(), sampleDefinition())

    @Test
    fun `enqueuer exposes the common queue name and the default empty display name`() = runTest {
        val enqueuer = MultiJobExecutorEnqueuer()
        assertEquals(JobQueueNames.commonJobQueue, enqueuer.queueName)
        // displayName is not overridden, so it falls back to the interface default "".
        assertEquals("", enqueuer.displayName)
    }

    @Test
    fun `enqueuer prepare decodes the configuration into a multi-job`() = runTest {
        // Typed as the interface so the call without an initializer exercises the
        // interface's default initializer parameter.
        val enqueuer: JobConfigurationEnqueuer = MultiJobExecutorEnqueuer()
        val job = enqueuer.prepare(configuration())
        assertEquals(MultiJobExecutor::class, job.executor)
        assertEquals("Multi-Job", job.displayName)
    }

    @Test
    fun `enqueuer enqueue decodes and enqueues via the common queue`() = runTest {
        coEvery { queue.enqueue(any<Job>()) } returns UUID.random()
        val enqueuer: JobConfigurationEnqueuer = MultiJobExecutorEnqueuer()

        enqueuer.enqueue(configuration())

        coVerify(exactly = 1) { queue.enqueue(any<Job>()) }
    }

    @Test
    fun `enqueuer enqueueLater decodes and delays via the common queue`() = runTest {
        val timeout = slot<kotlin.time.Duration>()
        coEvery { queue.enqueueLater(any(), capture(timeout)) } returns UUID.random()
        val enqueuer: JobConfigurationEnqueuer = MultiJobExecutorEnqueuer()

        enqueuer.enqueueLater(configuration(), 20.seconds)

        assertEquals(20.seconds, timeout.captured)
    }

    @Test
    fun `enqueuer queue resolves the common queue`() = runTest {
        val enqueuer = MultiJobExecutorEnqueuer()
        assertSame(queue, enqueuer.queue())
    }
}
