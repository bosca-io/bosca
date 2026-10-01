@file:OptIn(bosca.di.annotation.InternalDI::class, bosca.core.annotations.Internal::class)

package bosca.sharedqueue.jobs.jobs

import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.sharedqueue.jobs.InternalJobConstructor
import bosca.sharedqueue.jobs.Job
import bosca.sharedqueue.jobs.JobConfigurationEnqueuer
import bosca.sharedqueue.jobs.JobExecutor
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.asCoroutineContext
import bosca.sharedqueue.jobs.enqueue.JobEnqueueEvent
import bosca.sharedqueue.jobs.enqueue.JobEnqueueEventChannel
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Covers [MultiJobExecutor]'s synthetic fan-out enqueue-event emission. When a
 * [JobEnqueueEventChannel] is registered, each attached child emits an event so
 * the admin history can show the child under its parent before the real enqueue.
 * When no channel is registered, or the channel throws, execute() must proceed
 * anyway — the event is best-effort observability, never load-bearing.
 */
class MultiJobExecutorEventTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val mockQueue = mockk<JobQueue>()

    @BeforeTest
    fun setUp() {
        ProviderRegistry.clear()
        provides<Json>(singleton = true) { json }
        provides<JobConfigurationEnqueuer>(name = "alpha", singleton = true) { DummyEnqueuer(AlphaExecutor::class) }
        coEvery { mockQueue.setJob(any()) } just Runs
    }

    @AfterTest
    fun tearDown() {
        ProviderRegistry.clear()
    }

    private fun parent(): Job {
        val definition = MultiJob(
            jobs = listOf(MultiJobJob(name = "alpha", type = "metadata")),
            type = "metadata",
        )
        return InternalJobConstructor(
            definition = json.encodeToJsonElement(definition),
            executor = MultiJobExecutor::class,
        )
    }

    private suspend fun runExecute(parent: Job) {
        withContext(mockQueue.asCoroutineContext(parent)) {
            MultiJobExecutor().execute()
        }
    }

    @Test
    fun `emits a fan-out enqueue event for each attached child`() = runTest {
        val emitted = mutableListOf<JobEnqueueEvent>()
        provides<JobEnqueueEventChannel>(singleton = true) { CapturingChannel(emitted) }

        runExecute(parent())

        assertEquals(1, emitted.size)
        val event = emitted.single()
        // executor falls back to the fully-qualified name of the child's executor class.
        assertEquals(AlphaExecutor::class.qualifiedName, event.executor)
        // The child carries no displayName, so the event falls back to the fan-out config name.
        assertEquals("alpha", event.displayName)
    }

    @Test
    fun `event uses the child's own display name when present`() = runTest {
        val emitted = mutableListOf<JobEnqueueEvent>()
        ProviderRegistry.clear()
        provides<Json>(singleton = true) { json }
        coEvery { mockQueue.setJob(any()) } just Runs
        provides<JobEnqueueEventChannel>(singleton = true) { CapturingChannel(emitted) }
        // This enqueuer stamps the child with its own displayName, so the event should carry it
        // instead of falling back to the fan-out config name.
        provides<JobConfigurationEnqueuer>(name = "alpha", singleton = true) {
            DummyEnqueuer(AlphaExecutor::class, childDisplayName = "Custom Label")
        }

        runExecute(parent())

        assertEquals("Custom Label", emitted.single().displayName)
    }

    @Test
    fun `event falls back to the executor simple name when there is no qualified name`() = runTest {
        // A local class has a null qualifiedName but a non-null simpleName.
        class LocalChildExecutor : JobExecutor { override suspend fun execute() {} }
        val emitted = mutableListOf<JobEnqueueEvent>()
        freshRegistry(emitted, DummyEnqueuer(LocalChildExecutor::class))
        runExecute(parent())
        assertEquals("LocalChildExecutor", emitted.single().executor)
    }

    @Test
    fun `event falls back to unknown when the executor has neither qualified nor simple name`() = runTest {
        // An anonymous object's class has null qualifiedName AND null simpleName.
        val anon = object : JobExecutor { override suspend fun execute() {} }
        val emitted = mutableListOf<JobEnqueueEvent>()
        freshRegistry(emitted, DummyEnqueuer(anon::class))
        runExecute(parent())
        assertEquals("unknown", emitted.single().executor)
    }

    private fun freshRegistry(emitted: MutableList<JobEnqueueEvent>, enqueuer: JobConfigurationEnqueuer) {
        ProviderRegistry.clear()
        provides<Json>(singleton = true) { json }
        coEvery { mockQueue.setJob(any()) } just Runs
        provides<JobEnqueueEventChannel>(singleton = true) { CapturingChannel(emitted) }
        provides<JobConfigurationEnqueuer>(name = "alpha", singleton = true) { enqueuer }
    }

    @Test
    fun `execute proceeds when the enqueue-event channel throws`() = runTest {
        provides<JobEnqueueEventChannel>(singleton = true) { ThrowingChannel() }

        // Must not propagate: the child is still attached even though the event failed.
        val parent = parent()
        runExecute(parent)
        assertEquals(1, parent.getChildren().size)
    }

    // Each dummy enqueuer produces a Job whose executor identifies the fan-out target.
    private class DummyEnqueuer(
        private val executor: kotlin.reflect.KClass<out JobExecutor>,
        private val childDisplayName: String? = null,
    ) : JobConfigurationEnqueuer {
        override val queueName: String = "test"
        override suspend fun prepare(configuration: kotlinx.serialization.json.JsonElement, initializer: suspend Job.() -> Unit): Job =
            InternalJobConstructor(definition = configuration, executor = executor, displayName = childDisplayName)
        override suspend fun enqueue(configuration: kotlinx.serialization.json.JsonElement, initializer: suspend Job.() -> Unit): Job = error("unused")
        override suspend fun enqueueLater(configuration: kotlinx.serialization.json.JsonElement, timeout: kotlin.time.Duration, initializer: suspend Job.() -> Unit): Job = error("unused")
        override suspend fun queue(): JobQueue = error("unused")
    }

    private class CapturingChannel(private val sink: MutableList<JobEnqueueEvent>) : JobEnqueueEventChannel {
        override suspend fun emit(event: JobEnqueueEvent) { sink += event }
        override fun events(): Flow<JobEnqueueEvent> = emptyFlow()
    }

    private class ThrowingChannel : JobEnqueueEventChannel {
        override suspend fun emit(event: JobEnqueueEvent): Unit = throw RuntimeException("channel down")
        override fun events(): Flow<JobEnqueueEvent> = emptyFlow()
    }

    private class AlphaExecutor : JobExecutor {
        override suspend fun execute() {}
    }
}
