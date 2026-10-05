@file:OptIn(
    ExperimentalUuidApi::class,
    bosca.core.annotations.Internal::class,
    bosca.di.annotation.InternalDI::class,
)

package bosca.pipelines

import bosca.db.ConnectionManager
import bosca.db.asCoroutineContext
import bosca.di.ProviderRegistry
import bosca.di.asProvider
import bosca.di.provides
import bosca.events.Event
import bosca.lock.DistributedLockFactory
import bosca.observability.ErrorCapture
import bosca.pipelines.configuration.PipelinesConfiguration
import bosca.pipelines.configuration.PipelinesJobQueueNames
import bosca.pipelines.configuration.PipelinesRuntimeConfiguration
import bosca.pipelines.git.PipelineProjectContentValidator
import bosca.pipelines.installer.PipelineScheduledJobsInstaller
import bosca.pipelines.model.Pipeline
import bosca.pipelines.repository.PipelinesMigration
import bosca.pipelines.security.PipelinePermissionEvaluator
import bosca.pipelines.service.PipelineRunService
import bosca.pipelines.service.PipelineService
import bosca.pipelines.trigger.PipelineDispatchJob
import bosca.pipelines.trigger.PipelineDispatchJobExecutor
import bosca.pipelines.trigger.PipelineEventDispatcherImpl
import bosca.pipelines.trigger.PipelineRetentionSweepExecutor
import bosca.pipelines.trigger.PipelineRetentionSweepJob
import bosca.pipelines.trigger.PipelineRunJob
import bosca.pipelines.trigger.PipelineRunJobExecutor
import bosca.pipelines.trigger.PipelineScheduledRunExecutor
import bosca.pipelines.trigger.PipelineScheduledRunJob
import bosca.pipelines.trigger.PipelineSuspendedSweepExecutor
import bosca.pipelines.trigger.PipelineSuspendedSweepJob
import bosca.scheduler.service.SchedulerService
import bosca.security.service.GroupEvaluator
import bosca.security.service.SecurityService
import bosca.serialization.OffsetDateTime
import bosca.serialization.OffsetDateTimeSerializer
import bosca.serialization.UUID
import bosca.serialization.UUIDSerializer
import bosca.server.BoscaApplication
import bosca.server.config.ApplicationConfig
import bosca.sharedqueue.jobs.InternalJobConstructor
import bosca.sharedqueue.jobs.JobExecutor
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.JobQueueFactory
import bosca.sharedqueue.jobs.JobRunner
import bosca.sharedqueue.jobs.asCoroutineContext
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlin.reflect.KClass
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi

/**
 * Covers the pipelines DI wiring object ([PipelinesConfiguration]) — above all the
 * config-present-vs-default arm of `pipelinesRuntimeConfiguration` — the
 * [PipelinesRuntimeConfiguration] model (defaults / round-trip / per-field equality),
 * [PipelineEventDispatcherImpl] (both connection arms × match/no-match × the catch path), and the
 * trigger executors' guard arms that the sibling [bosca.pipelines.trigger.PipelineJobExecutorsTest]
 * does not exercise (retention sweep, the suspended-sweep zero arm, the dispatch fan-out and its
 * per-pipeline failure isolation, and the admission-shed paths).
 */
class PipelineConfigAndTriggerTest {

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
        // Every executor path runs through AbstractJobExecutor.getJobDefinition(), which resolves
        // provide<Json>() to decode the job definition. Register the configured Json so the executor
        // harness can decode jobs (the enqueue-DI helper re-registers the same instance harmlessly).
        provides<Json> { json }
    }

    @AfterTest
    fun tearDown() = ProviderRegistry.clear()

    // --- PipelinesRuntimeConfiguration ------------------------------------------------------------

    @Test
    fun `runtime configuration carries the documented defaults`() = runTest {
        val config = PipelinesRuntimeConfiguration()
        assertEquals("sa", config.serviceAccount)
        assertEquals(1440, config.suspendedRunMaxLifetimeMinutes)
        assertEquals(5000, config.onDemandRunMaxBlockMillis)
        assertEquals(30, config.runStateRetentionDays)
        assertEquals(90, config.runHistoryRetentionDays)
    }

    @Test
    fun `runtime configuration round-trips through JSON`() = runTest {
        val original = PipelinesRuntimeConfiguration(
            serviceAccount = "svc",
            suspendedRunMaxLifetimeMinutes = 10,
            onDemandRunMaxBlockMillis = 20,
            runStateRetentionDays = 30,
            runHistoryRetentionDays = 40,
        )
        val encoded = Json.encodeToString(PipelinesRuntimeConfiguration.serializer(), original)
        val decoded = Json.decodeFromString(PipelinesRuntimeConfiguration.serializer(), encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `runtime configuration decodes an empty object using all defaults`() = runTest {
        // Exercises every default-value deserializer arm: an empty object must reconstruct the defaults.
        val decoded = Json.decodeFromString(PipelinesRuntimeConfiguration.serializer(), "{}")
        assertEquals(PipelinesRuntimeConfiguration(), decoded)
    }

    @Test
    fun `runtime configuration decodes a partial object filling the rest with defaults`() = runTest {
        val decoded = Json.decodeFromString(
            PipelinesRuntimeConfiguration.serializer(),
            """{"serviceAccount":"only-this"}""",
        )
        assertEquals("only-this", decoded.serviceAccount)
        assertEquals(1440, decoded.suspendedRunMaxLifetimeMinutes)
    }

    @Test
    fun `runtime configuration differs when any single field changes`() = runTest {
        val base = PipelinesRuntimeConfiguration()
        assertNotEquals(base, base.copy(serviceAccount = "other"))
        assertNotEquals(base, base.copy(suspendedRunMaxLifetimeMinutes = 1))
        assertNotEquals(base, base.copy(onDemandRunMaxBlockMillis = 1))
        assertNotEquals(base, base.copy(runStateRetentionDays = 1))
        assertNotEquals(base, base.copy(runHistoryRetentionDays = 1))
        // And an identical copy stays equal (the all-arms-true side of equals).
        assertEquals(base, base.copy())

        assertEquals(7, PipelinesRuntimeConfiguration(runStateRetentionDays = 7).runStateRetentionDays)
        assertEquals(8, PipelinesRuntimeConfiguration(runHistoryRetentionDays = 8).runHistoryRetentionDays)
    }

    // --- PipelinesConfiguration.pipelinesRuntimeConfiguration -------------------------------------

    private fun appWithYaml(yaml: String): BoscaApplication {
        ProviderRegistry.clear()
        return BoscaApplication(ApplicationConfig.load(yaml.byteInputStream()))
    }

    @Test
    fun `provider reads the pipelines config block when present`() = runTest {
        val app = appWithYaml(
            """
            pipelines:
              serviceAccount: configured
              suspendedRunMaxLifetimeMinutes: 7
              onDemandRunMaxBlockMillis: 8
              runStateRetentionDays: 9
              runHistoryRetentionDays: 11
            """.trimIndent(),
        )
        val config = PipelinesConfiguration().pipelinesRuntimeConfiguration(app)
        assertEquals("configured", config.serviceAccount)
        assertEquals(7, config.suspendedRunMaxLifetimeMinutes)
        assertEquals(8, config.onDemandRunMaxBlockMillis)
        assertEquals(9, config.runStateRetentionDays)
        assertEquals(11, config.runHistoryRetentionDays)
    }

    @Test
    fun `provider falls back to defaults when the pipelines block is absent`() = runTest {
        // The `?.getAs() ?: PipelinesRuntimeConfiguration()` elvis null arm.
        val app = appWithYaml("server:\n  port: 8080")
        val config = PipelinesConfiguration().pipelinesRuntimeConfiguration(app)
        assertEquals(PipelinesRuntimeConfiguration(), config)
    }

    // --- PipelinesConfiguration pure-construction providers ---------------------------------------

    @Test
    fun `migration provider builds a pipelines migration`() = runTest {
        assertTrue(PipelinesConfiguration().migration() is PipelinesMigration)
    }

    @Test
    fun `scheduled jobs installer provider builds an installer`() = runTest {
        val installer = PipelinesConfiguration().pipelineScheduledJobsInstaller(
            mockk<SchedulerService>(relaxed = true),
            json,
        )
        assertTrue(installer is PipelineScheduledJobsInstaller)
    }

    @Test
    fun `job queue provider delegates to the factory with the physical queue name`() = runTest {
        val factory = mockk<JobQueueFactory>()
        val queue = mockk<JobQueue>(relaxed = true)
        every { factory.create(PipelinesJobQueueNames.queue) } returns queue
        val result = PipelinesConfiguration().pipelinesJobQueue(factory)
        assertSame(queue, result)
        verify(exactly = 1) { factory.create("pipelines") }
    }

    @Test
    fun `job queue runner provider builds a runner`() = runTest {
        val runner = PipelinesConfiguration().pipelinesJobQueueRunner(
            mockk<JobQueue>(relaxed = true),
            mockk<DistributedLockFactory>(relaxed = true),
            ErrorCapture.Noop.asProvider(),
        )
        assertTrue(runner is JobRunner)
    }

    @Test
    fun `event dispatcher provider builds the impl`() = runTest {
        val dispatcher = PipelinesConfiguration().pipelineEventDispatcher(
            mockk<PipelineService>(relaxed = true),
            json,
        )
        assertTrue(dispatcher is PipelineEventDispatcherImpl)
    }

    @Test
    fun `content validator provider builds the validator`() = runTest {
        val validator = PipelinesConfiguration().pipelineProjectContentValidator(
            mockk<PipelineService>(relaxed = true),
        )
        assertTrue(validator is PipelineProjectContentValidator)
    }

    @Test
    fun `permission evaluator provider builds the evaluator`() = runTest {
        val evaluator = PipelinesConfiguration().pipelinePermissionEvaluator(
            mockk<PipelineService>(relaxed = true),
            mockk<SecurityService>(relaxed = true),
            mockk<GroupEvaluator>(relaxed = true),
        )
        assertTrue(evaluator is PipelinePermissionEvaluator)
    }

    // --- PipelineEventDispatcherImpl --------------------------------------------------------------

    /** Registers the named queue used by generated enqueue helpers. */
    private fun registerEnqueueDI(queue: JobQueue) {
        provides<JobQueue>(name = PipelinesJobQueueNames.jobQueue) { queue }
    }

    /** Runs [block] with a (mock) ConnectionManager in the coroutine context so connectionOrNull() != null. */
    private suspend fun withLiveConnection(block: suspend () -> Unit) {
        val manager = mockk<ConnectionManager>(relaxed = true)
        withContext(manager.asCoroutineContext()) { block() }
    }

    @Test
    fun `dispatch enqueues a single job on the live-connection match path`() = runTest {
        val pipelineService = mockk<PipelineService>()
        coEvery { pipelineService.triggeredEventTypes() } returns setOf("sample.event")
        val queue = mockk<JobQueue>(relaxed = true)
        coEvery { queue.enqueue(any()) } returns UUID.random()
        registerEnqueueDI(queue)

        val dispatcher = PipelineEventDispatcherImpl(pipelineService, json)
        withLiveConnection {
            dispatcher.dispatch("sample.event", SampleEvent("e1"), SampleEvent.serializer())
        }

        coVerify(exactly = 1) { queue.enqueue(any()) }
    }

    @Test
    fun `dispatch does nothing on the live-connection no-match path`() = runTest {
        val pipelineService = mockk<PipelineService>()
        coEvery { pipelineService.triggeredEventTypes() } returns setOf("other.event")
        val queue = mockk<JobQueue>(relaxed = true)
        registerEnqueueDI(queue)

        val dispatcher = PipelineEventDispatcherImpl(pipelineService, json)
        withLiveConnection {
            dispatcher.dispatch("sample.event", SampleEvent("e1"), SampleEvent.serializer())
        }

        coVerify(exactly = 0) { queue.enqueue(any()) }
    }

    @Test
    fun `dispatch propagates an exception from the gate lookup`() = runTest {
        val pipelineService = mockk<PipelineService>()
        coEvery { pipelineService.triggeredEventTypes() } throws IllegalStateException("boom")
        val queue = mockk<JobQueue>(relaxed = true)
        registerEnqueueDI(queue)

        val dispatcher = PipelineEventDispatcherImpl(pipelineService, json)
        withLiveConnection {
            assertFailsWith<IllegalStateException> {
                dispatcher.dispatch("sample.event", SampleEvent("e1"), SampleEvent.serializer())
            }
        }
        coVerify(exactly = 0) { queue.enqueue(any()) }
    }

    @Test
    fun `dispatch with no connection propagates connection setup failure`() = runTest {
        val pipelineService = mockk<PipelineService>(relaxed = true)
        coEvery { pipelineService.triggeredEventTypes() } returns emptySet()
        val queue = mockk<JobQueue>(relaxed = true)
        registerEnqueueDI(queue)

        val dispatcher = PipelineEventDispatcherImpl(pipelineService, json)
        // Intentionally NOT inside withLiveConnection: connectionOrNull() == null.
        assertFails {
            dispatcher.dispatch("sample.event", SampleEvent("e1"), SampleEvent.serializer())
        }

        coVerify(exactly = 0) { queue.enqueue(any()) }
    }

    // --- Trigger executor harness -----------------------------------------------------------------

    private suspend fun <T> runExecutor(
        executor: JobExecutor,
        type: KClass<out JobExecutor>,
        serializer: SerializationStrategy<T>,
        jobDef: T,
    ) {
        val job = InternalJobConstructor(
            definition = json.encodeToJsonElement(serializer, jobDef),
            executor = type,
        )
        withContext(mockk<JobQueue>(relaxed = true).asCoroutineContext(job)) { executor.execute() }
    }

    // --- PipelineRetentionSweepExecutor -----------------------------------------------------------

    @Test
    fun `retention sweep logs when it reaps rows`() = runTest {
        val runService = mockk<PipelineRunService>(relaxed = true)
        coEvery { runService.purgeExpiredRuns() } returns 5

        runExecutor(
            PipelineRetentionSweepExecutor(runService),
            PipelineRetentionSweepExecutor::class,
            PipelineRetentionSweepJob.serializer(),
            PipelineRetentionSweepJob(),
        )

        coVerify(exactly = 1) { runService.purgeExpiredRuns() }
    }

    @Test
    fun `retention sweep stays quiet when nothing is reaped`() = runTest {
        // The `reaped > 0` false arm.
        val runService = mockk<PipelineRunService>(relaxed = true)
        coEvery { runService.purgeExpiredRuns() } returns 0

        runExecutor(
            PipelineRetentionSweepExecutor(runService),
            PipelineRetentionSweepExecutor::class,
            PipelineRetentionSweepJob.serializer(),
            PipelineRetentionSweepJob(),
        )

        coVerify(exactly = 1) { runService.purgeExpiredRuns() }
    }

    // --- PipelineSuspendedSweepExecutor (zero arm) ------------------------------------------------

    @Test
    fun `suspended sweep stays quiet when nothing is swept`() = runTest {
        // The `swept > 0` false arm — the sibling test only covers the positive arm.
        val runService = mockk<PipelineRunService>(relaxed = true)
        coEvery { runService.sweepStuckSuspended() } returns 0

        runExecutor(
            PipelineSuspendedSweepExecutor(runService),
            PipelineSuspendedSweepExecutor::class,
            PipelineSuspendedSweepJob.serializer(),
            PipelineSuspendedSweepJob(),
        )

        coVerify(exactly = 1) { runService.sweepStuckSuspended() }
    }

    // --- PipelineDispatchJobExecutor --------------------------------------------------------------

    @Test
    fun `dispatch executor enqueues a run job for each matched pipeline`() = runTest {
        val pipelineService = mockk<PipelineService>()
        val p1 = Pipeline(id = UUID.random(), name = "p1", acceptedInputType = "sample")
        val p2 = Pipeline(id = UUID.random(), name = "p2", acceptedInputType = "sample")
        coEvery { pipelineService.triggeredFor("sample.event") } returns listOf(p1, p2)
        val queue = mockk<JobQueue>(relaxed = true)
        coEvery { queue.enqueue(any()) } returns UUID.random()
        registerEnqueueDI(queue)

        runExecutor(
            PipelineDispatchJobExecutor(pipelineService),
            PipelineDispatchJobExecutor::class,
            PipelineDispatchJob.serializer(),
            PipelineDispatchJob(eventName = "sample.event", eventPayload = JsonObject(emptyMap())),
        )

        // Two pipelines -> two PipelineRunJob enqueues.
        coVerify(exactly = 2) { queue.enqueue(any()) }
    }

    @Test
    fun `dispatch executor does nothing when no pipeline is triggered`() = runTest {
        // forEach over zero elements — the loop body never runs.
        val pipelineService = mockk<PipelineService>()
        coEvery { pipelineService.triggeredFor("sample.event") } returns emptyList()
        val queue = mockk<JobQueue>(relaxed = true)
        registerEnqueueDI(queue)

        runExecutor(
            PipelineDispatchJobExecutor(pipelineService),
            PipelineDispatchJobExecutor::class,
            PipelineDispatchJob.serializer(),
            PipelineDispatchJob(eventName = "sample.event", eventPayload = JsonObject(emptyMap())),
        )

        coVerify(exactly = 0) { queue.enqueue(any()) }
    }

    @Test
    fun `dispatch executor attempts every pipeline and then propagates an enqueue failure`() = runTest {
        val pipelineService = mockk<PipelineService>()
        val bad = Pipeline(id = UUID.random(), name = "bad", acceptedInputType = "sample")
        val good = Pipeline(id = UUID.random(), name = "good", acceptedInputType = "sample")
        coEvery { pipelineService.triggeredFor("sample.event") } returns listOf(bad, good)
        val queue = mockk<JobQueue>(relaxed = true)
        // First enqueue throws (bad pipeline), the rest succeed.
        coEvery { queue.enqueue(any()) } throws IllegalStateException("queue down") andThen UUID.random()
        registerEnqueueDI(queue)

        assertFailsWith<IllegalStateException> {
            runExecutor(
                PipelineDispatchJobExecutor(pipelineService),
                PipelineDispatchJobExecutor::class,
                PipelineDispatchJob.serializer(),
                PipelineDispatchJob(eventName = "sample.event", eventPayload = JsonObject(emptyMap())),
            )
        }

        // Both pipelines were attempted even though the first threw.
        coVerify(exactly = 2) { queue.enqueue(any()) }
    }

    // --- Admission-shed arms (start returns null) -------------------------------------------------

    @Test
    fun `run executor sheds the run when admission returns null`() = runTest {
        // start() sheds (returns null) — the executor records nothing further.
        val pipelineService = mockk<PipelineService>()
        val runService = mockk<PipelineRunService>(relaxed = true)
        val pipeline = Pipeline(id = UUID.random(), name = "p", acceptedInputType = "sample")
        coEvery { pipelineService.get(pipeline.id) } returns pipeline
        coEvery { runService.start(pipeline, any(), "sample.event", any()) } returns null
        catalogEvent("sample.event", SampleEvent.serializer())

        val payload = json.encodeToJsonElement(SampleEvent.serializer(), SampleEvent("e1"))
        runExecutor(
            PipelineRunJobExecutor(pipelineService, runService, json),
            PipelineRunJobExecutor::class,
            PipelineRunJob.serializer(),
            PipelineRunJob(pipelineId = pipeline.id, eventName = "sample.event", eventPayload = payload),
        )

        coVerify(exactly = 1) { runService.start(pipeline, any(), "sample.event", any()) }
        coVerify(exactly = 0) { runService.recordLog(any()) }
    }

    @Test
    fun `scheduled-run executor sheds the run when admission returns null`() = runTest {
        val pipelineService = mockk<PipelineService>()
        val runService = mockk<PipelineRunService>(relaxed = true)
        val pipeline = Pipeline(id = UUID.random(), name = "p", acceptedInputType = "JSON")
        coEvery { pipelineService.get(pipeline.id) } returns pipeline
        coEvery { runService.start(pipeline, any(), "schedule", any()) } returns null

        runExecutor(
            PipelineScheduledRunExecutor(pipelineService, runService),
            PipelineScheduledRunExecutor::class,
            PipelineScheduledRunJob.serializer(),
            PipelineScheduledRunJob(pipelineId = pipeline.id),
        )

        coVerify(exactly = 1) { runService.start(pipeline, any(), "schedule", any()) }
        coVerify(exactly = 0) { runService.recordLog(any()) }
    }

    private fun catalogEvent(name: String, serializer: kotlinx.serialization.KSerializer<*>) {
        provides<bosca.events.catalog.EventCatalogRegistrar> {
            object : bosca.events.catalog.EventCatalogRegistrar {
                override val events: List<bosca.events.catalog.EventDescriptor> = emptyList()
                override val serializers: Map<String, kotlinx.serialization.KSerializer<*>> =
                    mapOf(name to serializer)
            }
        }
    }

    // --- Job definition models (round-trip + per-field equality) ----------------------------------

    @Test
    fun `dispatch job round-trips and differs per field`() = runTest {
        val base = PipelineDispatchJob(
            eventName = "e",
            eventPayload = JsonObject(emptyMap()),
            eventCreated = OffsetDateTime.now(),
        )
        val encoded = json.encodeToString(PipelineDispatchJob.serializer(), base)
        val decoded = json.decodeFromString(PipelineDispatchJob.serializer(), encoded)
        assertEquals(base, decoded)
        assertNotEquals(base, base.copy(eventName = "other"))
        assertNotEquals(base, base.copy(eventPayload = json.encodeToJsonElement(SampleEvent.serializer(), SampleEvent("x"))))
    }

    @Test
    fun `run job round-trips and differs per field`() = runTest {
        val base = PipelineRunJob(
            pipelineId = UUID.random(),
            eventName = "e",
            eventPayload = JsonObject(emptyMap()),
            eventCreated = OffsetDateTime.now(),
        )
        val encoded = json.encodeToString(PipelineRunJob.serializer(), base)
        val decoded = json.decodeFromString(PipelineRunJob.serializer(), encoded)
        assertEquals(base, decoded)
        assertNotEquals(base, base.copy(pipelineId = UUID.random()))
        assertNotEquals(base, base.copy(eventName = "other"))
    }

    @Test
    fun `scheduled-run job round-trips and differs by pipeline id`() = runTest {
        val base = PipelineScheduledRunJob(pipelineId = UUID.random())
        val encoded = json.encodeToString(PipelineScheduledRunJob.serializer(), base)
        val decoded = json.decodeFromString(PipelineScheduledRunJob.serializer(), encoded)
        assertEquals(base, decoded)
        assertNotEquals(base, base.copy(pipelineId = UUID.random()))
    }
}
