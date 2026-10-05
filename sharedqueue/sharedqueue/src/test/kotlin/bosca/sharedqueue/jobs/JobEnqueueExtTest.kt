@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.sharedqueue.jobs

import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.queue.annotations.IJobDefinition
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import bosca.serialization.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Exercises the inline `IJobDefinition.prepare/enqueue/enqueueLater` extension
 * builders in [JobEnqueueExt]. These are the ergonomic entry points domain code
 * uses to turn a `@Serializable` definition into a [Job] and hand it to a
 * [JobQueue]; each overload (with and without an initializer) must build the job
 * with the supplied executor metadata and, for the enqueue variants, forward it
 * to the queue.
 */
class JobEnqueueExtTest {

    @Serializable
    private data class SampleDefinition(val value: String = "x") : IJobDefinition

    private class SampleExecutor : JobExecutor {
        override suspend fun execute() {}
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val queue = mockk<JobQueue>()

    @BeforeTest
    fun setUp() {
        ProviderRegistry.clear()
        provides<Json>(singleton = true) { json }
        coEvery { queue.enqueue(any<Job>()) } returns UUID.random()
        coEvery { queue.enqueueLater(any(), any()) } returns UUID.random()
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
        ProviderRegistry.clear()
    }

    @Test
    fun `prepare builds a job carrying the executor metadata`() = runTest {
        val job = SampleDefinition("hello").prepare(
            executor = SampleExecutor::class,
            executorName = "sample",
            displayName = "Sample Job",
        )

        assertEquals(SampleExecutor::class, job.executor)
        assertEquals("sample", job.executorName)
        assertEquals("Sample Job", job.displayName)
        // The definition was serialized into the job's payload.
        assertEquals(json.encodeToString(SampleDefinition.serializer(), SampleDefinition("hello")), job.getDefinition().toString())
    }

    @Test
    fun `prepare with initializer runs the initializer against the fresh job`() = runTest {
        val job = SampleDefinition().prepare(executor = SampleExecutor::class) {
            // A brand-new job (id == NIL) is "locked", so mutating it here is legal.
            // yield() is a real suspension point, exercising the suspending-initializer path.
            kotlinx.coroutines.yield()
            setRunOnFailure(true)
        }
        assertTrue(job.getRunOnFailure())
    }

    @Test
    fun `prepare with persistent id preserves the caller owned queue identity`() = runTest {
        val id = UUID.random()

        val job = SampleDefinition().prepare(
            id = id,
            executor = SampleExecutor::class,
        )

        assertEquals(id, job.getId())
    }

    @Test
    fun `prepare with persistent id initializes the fresh job before assigning its identity`() = runTest {
        val id = UUID.random()

        val job = SampleDefinition().prepare(
            id = id,
            executor = SampleExecutor::class,
        ) {
            kotlinx.coroutines.yield()
            setRunOnFailure(true)
        }

        assertEquals(id, job.getId())
        assertTrue(job.getRunOnFailure())
    }

    @Test
    fun `enqueue prepares then hands the job to the queue`() = runTest {
        val captured = slot<Job>()
        coEvery { queue.enqueue(capture(captured)) } returns UUID.random()

        val returned = SampleDefinition().enqueue(
            queue = queue,
            executor = SampleExecutor::class,
            displayName = "Enqueued",
        )

        coVerify(exactly = 1) { queue.enqueue(any<Job>()) }
        // The job passed to the queue is the same instance returned to the caller.
        assertSame(returned, captured.captured)
        assertEquals("Enqueued", returned.displayName)
    }

    @Test
    fun `enqueue with initializer applies the initializer before enqueueing`() = runTest {
        val captured = slot<Job>()
        coEvery { queue.enqueue(capture(captured)) } returns UUID.random()

        SampleDefinition().enqueue(queue = queue, executor = SampleExecutor::class) {
            setRunOnFailure(true)
        }

        assertTrue(captured.captured.getRunOnFailure(), "initializer must run before the job reaches the queue")
    }

    @Test
    fun `enqueueLater forwards the timeout to the queue`() = runTest {
        val captured = slot<Job>()
        val timeoutSlot = slot<kotlin.time.Duration>()
        coEvery { queue.enqueueLater(capture(captured), capture(timeoutSlot)) } returns UUID.random()

        SampleDefinition().enqueueLater(
            queue = queue,
            executor = SampleExecutor::class,
            timeout = 30.seconds,
        )

        assertEquals(30.seconds, timeoutSlot.captured)
        assertNull(captured.captured.executorName)
    }

    @Test
    fun `enqueueLater with initializer applies the initializer before enqueueing`() = runTest {
        val captured = slot<Job>()
        coEvery { queue.enqueueLater(capture(captured), any()) } returns UUID.random()

        SampleDefinition().enqueueLater(
            queue = queue,
            executor = SampleExecutor::class,
            timeout = 5.seconds,
        ) {
            setRunOnFailure(true)
        }

        assertTrue(captured.captured.getRunOnFailure())
        coVerify(exactly = 1) { queue.enqueueLater(any(), 5.seconds) }
    }
}
