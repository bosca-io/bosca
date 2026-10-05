package bosca.sharedqueue.jobs.enqueue

import bosca.core.annotations.Internal
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.InternalJobConstructor
import bosca.sharedqueue.jobs.JobCallback
import bosca.sharedqueue.jobs.JobExecutor
import bosca.sharedqueue.jobs.JobListener
import bosca.sharedqueue.jobs.JobQueue
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class EventEmittingJobQueueTest {

    private val delegate = mockk<JobQueue>(relaxed = true)
    private val channel = DefaultJobEnqueueEventChannel()
    private val lock = mockk<bosca.lock.DistributedLock>(relaxed = true).apply {
        coEvery { acquire(any(), any(), any()) } returns true
        every { isHeld } returns true
    }
    private val queue = EventEmittingJobQueue(delegate, "test-queue", channel, emptyList())

    @OptIn(Internal::class)
    @Test
    fun enqueue_delegates_and_emits_event() = runTest(UnconfinedTestDispatcher()) {
        val jobId = UUID.random()
        coEvery { delegate.enqueue(any()) } returns jobId

        val received = mutableListOf<JobEnqueueEvent>()
        val collector = launch { channel.events().collect { received.add(it) } }

        val job = InternalJobConstructor(
            definition = Json.parseToJsonElement("{}"),
            executor = StubExecutor::class
        )

        val result = queue.enqueue(job)

        assertEquals(jobId, result)
        coVerify { delegate.enqueue(job) }
        assertEquals(1, received.size)
        assertEquals(jobId, received[0].jobId)
        assertEquals("test-queue", received[0].queue)
        assertFalse(received[0].delayed)

        collector.cancel()
    }

    @OptIn(Internal::class)
    @Test
    fun enqueueLater_delegates_and_emits_delayed_event() = runTest(UnconfinedTestDispatcher()) {
        val jobId = UUID.random()
        coEvery { delegate.enqueueLater(any(), any()) } returns jobId

        val received = mutableListOf<JobEnqueueEvent>()
        val collector = launch { channel.events().collect { received.add(it) } }

        val job = InternalJobConstructor(
            definition = Json.parseToJsonElement("{}"),
            executor = StubExecutor::class
        )

        val result = queue.enqueueLater(job, 5.minutes)

        assertEquals(jobId, result)
        coVerify { delegate.enqueueLater(job, 5.minutes) }
        assertEquals(1, received.size)
        assertEquals(jobId, received[0].jobId)
        assertTrue(received[0].delayed)

        collector.cancel()
    }

    @OptIn(Internal::class)
    @Test
    fun dequeue_delegates_without_emitting() = runTest(UnconfinedTestDispatcher()) {
        coEvery { delegate.dequeue() } returns null

        val received = mutableListOf<JobEnqueueEvent>()
        val collector = launch { channel.events().collect { received.add(it) } }

        queue.dequeue()

        coVerify { delegate.dequeue() }
        assertEquals(0, received.size)

        collector.cancel()
    }

    @OptIn(Internal::class)
    @Test
    fun enqueue_attaches_callbacks_to_job() = runTest(UnconfinedTestDispatcher()) {
        val callback = JobCallback(listener = StubListener::class)
        val queueWithCallbacks = EventEmittingJobQueue(delegate, "test-queue", channel, listOf(callback))

        val jobId = UUID.random()
        coEvery { delegate.enqueue(any()) } returns jobId

        val job = InternalJobConstructor(
            definition = Json.parseToJsonElement("{}"),
            executor = StubExecutor::class
        )

        assertEquals(0, job.callbacks.size)

        queueWithCallbacks.enqueue(job)

        assertEquals(1, job.callbacks.size)
        assertEquals(StubListener::class, job.callbacks[0].listener)
    }

    @OptIn(Internal::class)
    @Test
    fun enqueueLater_attaches_callbacks_to_job() = runTest(UnconfinedTestDispatcher()) {
        val callback = JobCallback(listener = StubListener::class)
        val queueWithCallbacks = EventEmittingJobQueue(delegate, "test-queue", channel, listOf(callback))

        val jobId = UUID.random()
        coEvery { delegate.enqueueLater(any(), any()) } returns jobId

        val job = InternalJobConstructor(
            definition = Json.parseToJsonElement("{}"),
            executor = StubExecutor::class
        )

        assertEquals(0, job.callbacks.size)

        queueWithCallbacks.enqueueLater(job, 10.seconds)

        assertEquals(1, job.callbacks.size)
        assertEquals(StubListener::class, job.callbacks[0].listener)
    }

    @OptIn(Internal::class)
    @Test
    fun enqueueLater_under_one_minute_emits_non_delayed_event() = runTest(UnconfinedTestDispatcher()) {
        val jobId = UUID.random()
        coEvery { delegate.enqueueLater(any(), any()) } returns jobId

        val received = mutableListOf<JobEnqueueEvent>()
        val collector = launch { channel.events().collect { received.add(it) } }

        val job = InternalJobConstructor(
            definition = Json.parseToJsonElement("{}"),
            executor = StubExecutor::class
        )

        val result = queue.enqueueLater(job, 30.seconds)

        assertEquals(jobId, result)
        coVerify { delegate.enqueueLater(job, 30.seconds) }
        assertEquals(1, received.size)
        assertEquals(jobId, received[0].jobId)
        assertFalse(received[0].delayed)
        assertEquals(null, received[0].delayedUntil)

        collector.cancel()
    }

    @OptIn(Internal::class)
    @Test
    fun enqueue_without_callbacks_does_not_modify_job() = runTest(UnconfinedTestDispatcher()) {
        val jobId = UUID.random()
        coEvery { delegate.enqueue(any()) } returns jobId

        val job = InternalJobConstructor(
            definition = Json.parseToJsonElement("{}"),
            executor = StubExecutor::class
        )

        queue.enqueue(job)

        assertEquals(0, job.callbacks.size)
    }

    @OptIn(Internal::class)
    @Test
    fun enqueue_with_disableEmitEvent_skips_the_event() = runTest(UnconfinedTestDispatcher()) {
        val jobId = UUID.random()
        coEvery { delegate.enqueue(any()) } returns jobId
        val callback = JobCallback(listener = StubListener::class)
        val queueWithCallbacks = EventEmittingJobQueue(delegate, "test-queue", channel, listOf(callback))

        val received = mutableListOf<JobEnqueueEvent>()
        val collector = launch { channel.events().collect { received.add(it) } }

        val job = InternalJobConstructor(
            definition = Json.parseToJsonElement("{}"),
            executor = StubExecutor::class
        ).apply { disableEmitEvent = true }

        val result = queueWithCallbacks.enqueue(job)

        assertEquals(jobId, result)
        assertEquals(0, received.size, "disableEmitEvent must suppress the enqueue event")
        assertEquals(
            1,
            job.callbacks.size,
            "disableEmitEvent must not change the existing default-callback behavior",
        )

        collector.cancel()
    }

    @OptIn(Internal::class)
    @Test
    fun enqueue_with_disableEnqueueCallbacks_skips_only_automatic_callbacks() =
        runTest(UnconfinedTestDispatcher()) {
            val jobId = UUID.random()
            coEvery { delegate.enqueue(any()) } returns jobId
            val automatic = JobCallback(listener = StubListener::class)
            val explicit = JobCallback(listener = ExplicitStubListener::class)
            val queueWithCallbacks =
                EventEmittingJobQueue(delegate, "test-queue", channel, listOf(automatic))

            val received = mutableListOf<JobEnqueueEvent>()
            val collector = launch { channel.events().collect { received.add(it) } }

            val job = InternalJobConstructor(
                definition = Json.parseToJsonElement("{}"),
                executor = StubExecutor::class,
            ).apply {
                addCallback(explicit)
                disableEnqueueCallbacks = true
            }

            val result = queueWithCallbacks.enqueue(job)

            assertEquals(jobId, result)
            assertEquals(1, received.size, "default callback suppression must not suppress the event")
            assertEquals(
                1,
                job.callbacks.size,
                "default callback suppression must preserve explicitly attached callbacks",
            )

            collector.cancel()
        }

    @OptIn(Internal::class)
    @Test
    fun enqueueLater_with_disableEmitEvent_skips_the_event() = runTest(UnconfinedTestDispatcher()) {
        val jobId = UUID.random()
        coEvery { delegate.enqueueLater(any(), any()) } returns jobId

        val received = mutableListOf<JobEnqueueEvent>()
        val collector = launch { channel.events().collect { received.add(it) } }

        val job = InternalJobConstructor(
            definition = Json.parseToJsonElement("{}"),
            executor = StubExecutor::class
        ).apply { disableEmitEvent = true }

        queue.enqueueLater(job, 5.minutes)

        assertEquals(0, received.size)
        collector.cancel()
    }

    @OptIn(Internal::class)
    @Test
    fun enqueueLater_with_disableEnqueueCallbacks_preserves_explicit_callbacks() =
        runTest(UnconfinedTestDispatcher()) {
            val jobId = UUID.random()
            coEvery { delegate.enqueueLater(any(), any()) } returns jobId
            val automatic = JobCallback(listener = StubListener::class)
            val explicit = JobCallback(listener = ExplicitStubListener::class)
            val queueWithCallbacks =
                EventEmittingJobQueue(delegate, "test-queue", channel, listOf(automatic))
            val job = InternalJobConstructor(
                definition = Json.parseToJsonElement("{}"),
                executor = StubExecutor::class,
            ).apply {
                addCallback(explicit)
                disableEnqueueCallbacks = true
            }

            queueWithCallbacks.enqueueLater(job, 10.seconds)

            assertEquals(listOf(explicit), job.callbacks)
        }

    @OptIn(Internal::class)
    @Test
    fun enqueue_swallows_channel_emit_failures() = runTest(UnconfinedTestDispatcher()) {
        // A channel that throws on emit must not fail the enqueue — the event is best-effort.
        val throwingQueue = EventEmittingJobQueue(delegate, "test-queue", ThrowingChannel, emptyList())
        val jobId = UUID.random()
        coEvery { delegate.enqueue(any()) } returns jobId

        val job = InternalJobConstructor(
            definition = Json.parseToJsonElement("{}"),
            executor = StubExecutor::class
        )

        assertEquals(jobId, throwingQueue.enqueue(job))
    }

    private object ThrowingChannel : JobEnqueueEventChannel {
        override suspend fun emit(event: JobEnqueueEvent): Unit = throw RuntimeException("channel down")
        override fun events(): Flow<JobEnqueueEvent> = emptyFlow()
    }

    @OptIn(Internal::class)
    @Test
    fun emitted_event_uses_executor_simple_name_when_no_qualified_name() = runTest(UnconfinedTestDispatcher()) {
        // A local class has a null qualifiedName but a non-null simpleName.
        class LocalExec : JobExecutor { override suspend fun execute() {} }
        coEvery { delegate.enqueue(any()) } returns UUID.random()
        val received = mutableListOf<JobEnqueueEvent>()
        val collector = launch { channel.events().collect { received.add(it) } }

        queue.enqueue(InternalJobConstructor(Json.parseToJsonElement("{}"), LocalExec::class))

        assertEquals("LocalExec", received.single().executor)
        collector.cancel()
    }

    @OptIn(Internal::class)
    @Test
    fun emitted_event_uses_unknown_when_executor_has_no_names() = runTest(UnconfinedTestDispatcher()) {
        // An anonymous object's class has null qualifiedName AND null simpleName.
        val anon = object : JobExecutor { override suspend fun execute() {} }
        coEvery { delegate.enqueue(any()) } returns UUID.random()
        val received = mutableListOf<JobEnqueueEvent>()
        val collector = launch { channel.events().collect { received.add(it) } }

        queue.enqueue(InternalJobConstructor(Json.parseToJsonElement("{}"), anon::class))

        assertEquals("unknown", received.single().executor)
        collector.cancel()
    }
}

class StubExecutor : JobExecutor {
    override suspend fun execute() {}
}

class StubListener : JobListener

class ExplicitStubListener : JobListener
