@file:OptIn(
    ExperimentalUuidApi::class,
    bosca.core.annotations.Internal::class,
    bosca.di.annotation.InternalDI::class,
)

package bosca.pipelines.trigger

import bosca.db.ConnectionManager
import bosca.db.ConnectionPool
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.events.Event
import bosca.pipelines.configuration.PipelinesJobQueueNames
import bosca.pipelines.service.PipelineService
import bosca.serialization.OffsetDateTimeSerializer
import bosca.serialization.UUID
import bosca.serialization.UUIDSerializer
import bosca.sharedqueue.jobs.JobQueue
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.uuid.ExperimentalUuidApi

/**
 * Covers [PipelineEventDispatcherImpl.dispatch]'s no-active-connection arm — the body that runs
 * INSIDE [bosca.db.withConnectionManager] (the gate lookup, the match/no-match short-circuit, the
 * payload encode + enqueue). The live-connection arm and the catch path are exercised by
 * [bosca.pipelines.PipelineConfigAndTriggerTest]; here we register a [ConnectionPool] so the
 * withConnectionManager block actually executes rather than throwing and being swallowed.
 */
class PipelineEventDispatcherImplTest {

    @Serializable
    private data class SampleEvent(val id: String) : Event

    private val json = Json {
        ignoreUnknownKeys = true
        serializersModule = SerializersModule {
            contextual(UUID::class, UUIDSerializer())
            contextual(java.time.OffsetDateTime::class, OffsetDateTimeSerializer())
        }
    }

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<Json> { json }
    }

    @AfterTest
    fun tearDown() = ProviderRegistry.clear()

    /**
     * Registers a [ConnectionPool] whose `connection()` hands back a relaxed [ConnectionManager], so
     * `withConnectionManager { ... }` runs its block (and releases) instead of throwing. The
     * dispatcher's outer `connectionOrNull()` stays null because runTest adds no manager to the
     * context — so the no-connection arm is taken and its block actually executes.
     */
    private fun registerConnectionPool() {
        val manager = mockk<ConnectionManager>(relaxed = true)
        val pool = mockk<ConnectionPool>(relaxed = true)
        every { pool.connection() } returns manager
        provides<ConnectionPool> { pool }
    }

    private fun registerEnqueueDI(queue: JobQueue) {
        provides<JobQueue>(name = PipelinesJobQueueNames.jobQueue) { queue }
    }

    @Test
    fun `dispatch enqueues a single job on the no-connection match path`() = runTest {
        // connectionOrNull() == null -> withConnectionManager block runs; the event matches the gate,
        // so the payload is encoded and exactly one PipelineDispatchJob is enqueued.
        registerConnectionPool()
        val pipelineService = mockk<PipelineService>()
        coEvery { pipelineService.triggeredEventTypes() } returns setOf("sample.event")
        val queue = mockk<JobQueue>(relaxed = true)
        coEvery { queue.enqueue(any()) } returns UUID.random()
        registerEnqueueDI(queue)

        val dispatcher = PipelineEventDispatcherImpl(pipelineService, json)
        dispatcher.dispatch("sample.event", SampleEvent("e1"), SampleEvent.serializer())

        coVerify(exactly = 1) { queue.enqueue(any()) }
    }

    @Test
    fun `dispatch does nothing on the no-connection no-match path`() = runTest {
        // connectionOrNull() == null -> withConnectionManager block runs but the event is NOT gated,
        // so the return@withConnectionManager short-circuits before any encode/enqueue.
        registerConnectionPool()
        val pipelineService = mockk<PipelineService>()
        coEvery { pipelineService.triggeredEventTypes() } returns setOf("other.event")
        val queue = mockk<JobQueue>(relaxed = true)
        registerEnqueueDI(queue)

        val dispatcher = PipelineEventDispatcherImpl(pipelineService, json)
        dispatcher.dispatch("sample.event", SampleEvent("e1"), SampleEvent.serializer())

        coVerify(exactly = 0) { queue.enqueue(any()) }
    }
}
