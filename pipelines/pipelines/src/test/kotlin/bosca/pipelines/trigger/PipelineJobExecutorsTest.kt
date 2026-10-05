@file:OptIn(ExperimentalUuidApi::class, bosca.core.annotations.Internal::class, bosca.di.annotation.InternalDI::class)

package bosca.pipelines.trigger

import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.events.catalog.EventCatalogRegistrar
import bosca.events.catalog.EventDescriptor
import bosca.pipelines.model.Pipeline
import bosca.pipelines.model.PipelineRun
import bosca.pipelines.service.PipelineRunService
import bosca.pipelines.service.PipelineService
import bosca.serialization.OffsetDateTimeSerializer
import bosca.serialization.UUID
import bosca.serialization.UUIDSerializer
import bosca.sharedqueue.jobs.InternalJobConstructor
import bosca.sharedqueue.jobs.DelayException
import bosca.sharedqueue.jobs.JobExecutor
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.asCoroutineContext
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
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
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi

/**
 * Drives the three pipeline job executors through the real job harness (a job in the coroutine
 * context, `getJobDefinition` decoding it). Covers the branching that isn't pure delegation — above
 * all `PipelineRunJobExecutor`'s pre-run-failure path, which must record a FAILED run when the
 * pipeline or event serializer is missing rather than start a run.
 */
class PipelineJobExecutorsTest {

    @Serializable
    private data class SampleEvent(val id: String)

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

    private fun catalogEvent(name: String, serializer: KSerializer<*>) {
        provides<EventCatalogRegistrar> {
            object : EventCatalogRegistrar {
                override val events: List<EventDescriptor> = emptyList()
                override val serializers: Map<String, KSerializer<*>> = mapOf(name to serializer)
            }
        }
    }

    private suspend fun <T> run(executor: JobExecutor, type: KClass<out JobExecutor>, serializer: SerializationStrategy<T>, jobDef: T) {
        val job = InternalJobConstructor(definition = json.encodeToJsonElement(serializer, jobDef), executor = type)
        withContext(mockk<JobQueue>(relaxed = true).asCoroutineContext(job)) { executor.execute() }
    }

    // --- PipelineRunJobExecutor -------------------------------------------------------------------

    @Test
    fun `run executor starts and drives the run for a catalogued event`() = runTest {
        val pipelineService = mockk<PipelineService>()
        val runService = mockk<PipelineRunService>(relaxed = true)
        val pipeline = Pipeline(id = UUID.random(), name = "p", acceptedInputType = "sample")
        val started = PipelineRun(id = UUID.random(), pipelineId = pipeline.id, graphSnapshot = JsonObject(emptyMap()))
        coEvery { pipelineService.get(pipeline.id) } returns pipeline
        coEvery { runService.start(pipeline, any(), "sample.event", any()) } returns started
        catalogEvent("sample.event", SampleEvent.serializer())

        val payload = json.encodeToJsonElement(SampleEvent.serializer(), SampleEvent("e1"))
        run(
            PipelineRunJobExecutor(pipelineService, runService, json),
            PipelineRunJobExecutor::class,
            PipelineRunJob.serializer(),
            PipelineRunJob(pipelineId = pipeline.id, eventName = "sample.event", eventPayload = payload),
        )

        coVerify(exactly = 1) { runService.start(pipeline, any(), "sample.event", any()) }
    }

    @Test
    fun `run executor records a FAILED run when the pipeline is missing`() = runTest {
        val pipelineService = mockk<PipelineService>()
        val runService = mockk<PipelineRunService>(relaxed = true)
        val pipelineId = UUID.random()
        coEvery { pipelineService.get(pipelineId) } returns null // pre-run failure

        run(
            PipelineRunJobExecutor(pipelineService, runService, json),
            PipelineRunJobExecutor::class,
            PipelineRunJob.serializer(),
            PipelineRunJob(pipelineId = pipelineId, eventName = "sample.event", eventPayload = JsonObject(emptyMap())),
        )

        coVerify(exactly = 1) { runService.recordLog(match { it.outcome == bosca.pipelines.model.PipelineRunStatus.FAILED && it.pipelineId == pipelineId }) }
        coVerify(exactly = 0) { runService.start(any(), any(), any(), any()) }
    }

    @Test
    fun `run executor records a FAILED run when no serializer is catalogued`() = runTest {
        val pipelineService = mockk<PipelineService>()
        val runService = mockk<PipelineRunService>(relaxed = true)
        val pipeline = Pipeline(id = UUID.random(), name = "p", acceptedInputType = "sample")
        coEvery { pipelineService.get(pipeline.id) } returns pipeline // pipeline exists, but no event catalogued

        run(
            PipelineRunJobExecutor(pipelineService, runService, json),
            PipelineRunJobExecutor::class,
            PipelineRunJob.serializer(),
            PipelineRunJob(pipelineId = pipeline.id, eventName = "uncatalogued.event", eventPayload = JsonObject(emptyMap())),
        )

        coVerify(exactly = 1) { runService.recordLog(match { it.outcome == bosca.pipelines.model.PipelineRunStatus.FAILED }) }
        coVerify(exactly = 0) { runService.start(any(), any(), any(), any()) }
    }

    // --- PipelineScheduledRunExecutor -------------------------------------------------------------

    @Test
    fun `scheduled-run executor starts and drives the run`() = runTest {
        val pipelineService = mockk<PipelineService>()
        val runService = mockk<PipelineRunService>(relaxed = true)
        val pipeline = Pipeline(id = UUID.random(), name = "p", acceptedInputType = "JSON")
        val started = PipelineRun(id = UUID.random(), pipelineId = pipeline.id, graphSnapshot = JsonObject(emptyMap()))
        coEvery { pipelineService.get(pipeline.id) } returns pipeline
        coEvery { runService.start(pipeline, any(), "schedule", any()) } returns started

        run(
            PipelineScheduledRunExecutor(pipelineService, runService),
            PipelineScheduledRunExecutor::class,
            PipelineScheduledRunJob.serializer(),
            PipelineScheduledRunJob(pipelineId = pipeline.id),
        )

        coVerify(exactly = 1) { runService.start(pipeline, any(), "schedule", any()) }
    }

    @Test
    fun `scheduled-run executor records a FAILED run when the pipeline is missing`() = runTest {
        val pipelineService = mockk<PipelineService>()
        val runService = mockk<PipelineRunService>(relaxed = true)
        val pipelineId = UUID.random()
        coEvery { pipelineService.get(pipelineId) } returns null

        run(
            PipelineScheduledRunExecutor(pipelineService, runService),
            PipelineScheduledRunExecutor::class,
            PipelineScheduledRunJob.serializer(),
            PipelineScheduledRunJob(pipelineId = pipelineId),
        )

        coVerify(exactly = 1) { runService.recordLog(match { it.outcome == bosca.pipelines.model.PipelineRunStatus.FAILED && it.pipelineId == pipelineId }) }
        coVerify(exactly = 0) { runService.start(any(), any(), any(), any()) }
    }

    // --- PipelineSuspendedSweepExecutor -----------------------------------------------------------

    @Test
    fun `sweep executor delegates to sweepStuckSuspended`() = runTest {
        val runService = mockk<PipelineRunService>(relaxed = true)
        coEvery { runService.sweepStuckSuspended() } returns 2

        run(
            PipelineSuspendedSweepExecutor(runService),
            PipelineSuspendedSweepExecutor::class,
            PipelineSuspendedSweepJob.serializer(),
            PipelineSuspendedSweepJob(),
        )

        coVerify(exactly = 1) { runService.sweepStuckSuspended() }
    }

    @Test
    fun `child-run executor installs drive callback and processes the child`() = runTest {
        val runService = mockk<PipelineRunService>(relaxed = true)
        val childRunId = UUID.random()

        run(
            PipelineChildRunJobExecutor(runService),
            PipelineChildRunJobExecutor::class,
            PipelineChildRunJob.serializer(),
            PipelineChildRunJob(childRunId),
        )

        coVerify(exactly = 1) { runService.process(childRunId) }
    }

    @Test
    fun `delay executor completes when due and requests redelivery while early`() = runTest {
        run(
            PipelineDelayJobExecutor(),
            PipelineDelayJobExecutor::class,
            PipelineDelayJob.serializer(),
            PipelineDelayJob(wakeAtEpochMillis = System.currentTimeMillis() - 1),
        )

        assertFailsWith<DelayException> {
            run(
                PipelineDelayJobExecutor(),
                PipelineDelayJobExecutor::class,
                PipelineDelayJob.serializer(),
                PipelineDelayJob(wakeAtEpochMillis = System.currentTimeMillis() + 60_000),
            )
        }
    }

    // --- Nested-catch + null-message error arms ---------------------------------------------------

    @Test
    fun `run executor falls back to toString when the failure carries no message`() = runTest {
        // Pipeline-missing path with a NULL-message exception drives `e.message ?: e.toString()` (line 80)
        // into its right arm: the recorded errorMessage must be the exception's toString().
        val pipelineService = mockk<PipelineService>()
        val runService = mockk<PipelineRunService>(relaxed = true)
        val pipelineId = UUID.random()
        val boom = RuntimeException() // message == null
        coEvery { pipelineService.get(pipelineId) } throws boom

        run(
            PipelineRunJobExecutor(pipelineService, runService, json),
            PipelineRunJobExecutor::class,
            PipelineRunJob.serializer(),
            PipelineRunJob(pipelineId = pipelineId, eventName = "sample.event", eventPayload = JsonObject(emptyMap())),
        )

        coVerify(exactly = 1) {
            runService.recordLog(match { it.errorMessage == boom.toString() && it.pipelineId == pipelineId })
        }
    }

    @Test
    fun `run executor swallows a failure to record the run log`() = runTest {
        // The nested catch (line 84): recordLog itself throwing must not propagate out of execute().
        val pipelineService = mockk<PipelineService>()
        val runService = mockk<PipelineRunService>(relaxed = true)
        val pipelineId = UUID.random()
        coEvery { pipelineService.get(pipelineId) } returns null // pre-run failure
        coEvery { runService.recordLog(any()) } throws IllegalStateException("history down")

        // Must complete without throwing.
        run(
            PipelineRunJobExecutor(pipelineService, runService, json),
            PipelineRunJobExecutor::class,
            PipelineRunJob.serializer(),
            PipelineRunJob(pipelineId = pipelineId, eventName = "sample.event", eventPayload = JsonObject(emptyMap())),
        )

        coVerify(exactly = 1) { runService.recordLog(any()) }
    }

    @Test
    fun `run executor returns null serializer when a registrar exists but lacks the event`() = runTest {
        // A catalogued registrar that does NOT contain the event drives the
        // `firstNotNullOfOrNull { it.get().serializers[eventName] }` lambda (line 93) through its
        // null arm, so the executor falls into the no-serializer pre-run failure.
        val pipelineService = mockk<PipelineService>()
        val runService = mockk<PipelineRunService>(relaxed = true)
        val pipeline = Pipeline(id = UUID.random(), name = "p", acceptedInputType = "sample")
        coEvery { pipelineService.get(pipeline.id) } returns pipeline
        catalogEvent("some.other.event", SampleEvent.serializer()) // present, but not the requested name

        run(
            PipelineRunJobExecutor(pipelineService, runService, json),
            PipelineRunJobExecutor::class,
            PipelineRunJob.serializer(),
            PipelineRunJob(pipelineId = pipeline.id, eventName = "missing.event", eventPayload = JsonObject(emptyMap())),
        )

        coVerify(exactly = 1) { runService.recordLog(match { it.outcome == bosca.pipelines.model.PipelineRunStatus.FAILED }) }
        coVerify(exactly = 0) { runService.start(any(), any(), any(), any()) }
    }

    @Test
    fun `scheduled-run executor falls back to toString when the failure carries no message`() = runTest {
        // PipelineScheduledRunExecutor line 63: `e.message ?: e.toString()` right arm.
        val pipelineService = mockk<PipelineService>()
        val runService = mockk<PipelineRunService>(relaxed = true)
        val pipelineId = UUID.random()
        val boom = RuntimeException() // message == null
        coEvery { pipelineService.get(pipelineId) } throws boom

        run(
            PipelineScheduledRunExecutor(pipelineService, runService),
            PipelineScheduledRunExecutor::class,
            PipelineScheduledRunJob.serializer(),
            PipelineScheduledRunJob(pipelineId = pipelineId),
        )

        coVerify(exactly = 1) {
            runService.recordLog(match { it.errorMessage == boom.toString() && it.pipelineId == pipelineId })
        }
    }

    @Test
    fun `scheduled-run executor swallows a failure to record the run log`() = runTest {
        // PipelineScheduledRunExecutor line 67: the nested catch when recordLog throws.
        val pipelineService = mockk<PipelineService>()
        val runService = mockk<PipelineRunService>(relaxed = true)
        val pipelineId = UUID.random()
        coEvery { pipelineService.get(pipelineId) } returns null
        coEvery { runService.recordLog(any()) } throws IllegalStateException("history down")

        run(
            PipelineScheduledRunExecutor(pipelineService, runService),
            PipelineScheduledRunExecutor::class,
            PipelineScheduledRunJob.serializer(),
            PipelineScheduledRunJob(pipelineId = pipelineId),
        )

        coVerify(exactly = 1) { runService.recordLog(any()) }
    }

    // --- Job-definition synthetic default-arg constructors ----------------------------------------
    // These construct each @Serializable job model with a PARTIAL subset of optional args (and also
    // the all-args form) so the synthetic default-args constructor mask branches on the data-class
    // headers are all executed, and read each getter.

    @Test
    fun `dispatch job constructs with a defaulted and an explicit eventCreated`() {
        val defaulted = PipelineDispatchJob(eventName = "e", eventPayload = JsonObject(emptyMap()))
        assertEquals("e", defaulted.eventName)
        assertEquals(JsonObject(emptyMap()), defaulted.eventPayload)
        // eventCreated defaulted to now() — just read the getter.
        assertTrue(defaulted.eventCreated.toString().isNotEmpty())

        val stamped = java.time.OffsetDateTime.now()
        val explicit = PipelineDispatchJob(eventName = "e", eventPayload = JsonObject(emptyMap()), eventCreated = stamped)
        assertEquals(stamped, explicit.eventCreated)
    }

    @Test
    fun `run job constructs with a defaulted and an explicit eventCreated`() {
        val id = UUID.random()
        val defaulted = PipelineRunJob(pipelineId = id, eventName = "e", eventPayload = JsonObject(emptyMap()))
        assertEquals(id, defaulted.pipelineId)
        assertEquals("e", defaulted.eventName)
        assertEquals(JsonObject(emptyMap()), defaulted.eventPayload)
        assertTrue(defaulted.eventCreated.toString().isNotEmpty())

        val stamped = java.time.OffsetDateTime.now()
        val explicit = PipelineRunJob(pipelineId = id, eventName = "e", eventPayload = JsonObject(emptyMap()), eventCreated = stamped)
        assertEquals(stamped, explicit.eventCreated)
    }

    @Test
    fun `suspended and retention sweep jobs construct via their no-arg constructors`() {
        val suspended = PipelineSuspendedSweepJob()
        val retention = PipelineRetentionSweepJob()
        // Round-trip both through the serializer so the @Serializable headers are exercised.
        val s = json.encodeToString(PipelineSuspendedSweepJob.serializer(), suspended)
        val r = json.encodeToString(PipelineRetentionSweepJob.serializer(), retention)
        assertTrue(json.decodeFromString(PipelineSuspendedSweepJob.serializer(), s) is PipelineSuspendedSweepJob)
        assertTrue(json.decodeFromString(PipelineRetentionSweepJob.serializer(), r) is PipelineRetentionSweepJob)
    }

    @Test
    fun `scheduled-run job constructs from a pipeline id`() {
        val id = UUID.random()
        val job = PipelineScheduledRunJob(pipelineId = id)
        assertEquals(id, job.pipelineId)
    }

    // --- Job-definition DESERIALIZATION constructor mask branches ----------------------------------
    // The synthetic `<init>(seen, …, marker)` generated for each @Serializable job decides, per field,
    // whether the value came over the wire or the default applies, and throws when a REQUIRED field is
    // absent. Decoding crafted JSON drives the arms that pure construction can't:
    //   * complete JSON (optional field present)        → the "field came over the wire" arm
    //   * JSON missing the optional field               → the "apply default" arm
    //   * JSON missing a required field                 → the throwMissingFieldException arm
    // A second Json with encodeDefaults=true is used to PRODUCE JSON that contains the defaulted
    // optional field (the test `json` omits defaults on encode, so it can't).

    private val jsonWithDefaults = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        serializersModule = SerializersModule {
            contextual(UUID::class, UUIDSerializer())
            contextual(java.time.OffsetDateTime::class, OffsetDateTimeSerializer())
        }
    }

    @Test
    fun `dispatch job decodes with the optional eventCreated present, absent, and a missing required field`() {
        val stamped = java.time.OffsetDateTime.now()
        // (1) optional present: encodeDefaults=true emits eventCreated → decode takes the over-the-wire arm.
        val full = jsonWithDefaults.encodeToString(
            PipelineDispatchJob.serializer(),
            PipelineDispatchJob(eventName = "e", eventPayload = JsonObject(emptyMap()), eventCreated = stamped),
        )
        assertTrue(full.contains("eventCreated"))
        assertEquals(stamped, json.decodeFromString(PipelineDispatchJob.serializer(), full).eventCreated)

        // (2) optional absent: required fields only → decode applies the now() default.
        val noOptional = json.decodeFromString(
            PipelineDispatchJob.serializer(),
            """{"eventName":"e","eventPayload":{}}""",
        )
        assertEquals("e", noOptional.eventName)
        assertTrue(noOptional.eventCreated.toString().isNotEmpty())

        // (3) missing required eventPayload → throwMissingFieldException arm.
        assertFailsWith<SerializationException> {
            json.decodeFromString(PipelineDispatchJob.serializer(), """{"eventName":"e"}""")
        }
    }

    @Test
    fun `run job decodes with the optional eventCreated present, absent, and a missing required field`() {
        val id = UUID.random()
        val stamped = java.time.OffsetDateTime.now()
        val full = jsonWithDefaults.encodeToString(
            PipelineRunJob.serializer(),
            PipelineRunJob(pipelineId = id, eventName = "e", eventPayload = JsonObject(emptyMap()), eventCreated = stamped),
        )
        assertTrue(full.contains("eventCreated"))
        val decodedFull = json.decodeFromString(PipelineRunJob.serializer(), full)
        assertEquals(stamped, decodedFull.eventCreated)
        assertEquals(id, decodedFull.pipelineId)

        val idJson = jsonWithDefaults.encodeToString(UUIDSerializer(), id)
        val noOptional = json.decodeFromString(
            PipelineRunJob.serializer(),
            """{"pipelineId":$idJson,"eventName":"e","eventPayload":{}}""",
        )
        assertEquals(id, noOptional.pipelineId)
        assertTrue(noOptional.eventCreated.toString().isNotEmpty())

        assertFailsWith<SerializationException> {
            json.decodeFromString(PipelineRunJob.serializer(), """{"eventName":"e","eventPayload":{}}""")
        }
    }

    @Test
    fun `scheduled-run job throws when the required pipeline id is missing`() {
        // Drives the deserialization ctor's `(seen and 1) == 1` check into its throw arm.
        assertFailsWith<SerializationException> {
            json.decodeFromString(PipelineScheduledRunJob.serializer(), """{}""")
        }
    }

    // --- Job-definition write$Self default-encoding branches --------------------------------------
    // The synthetic `write$Self` decides, per optional field, whether to emit it. The plain `json`
    // (encodeDefaults=false) drives the `shouldEncodeElementDefault == false` arm: the writer then
    // compares the field to its default and emits only when it differs. Encoding each job with the
    // plain `json` exercises that arm (the encodeDefaults=true arm is covered by `jsonWithDefaults`).

    @Test
    fun `dispatch job round-trips through the plain encoder (encodeDefaults=false write arm)`() {
        val stamped = java.time.OffsetDateTime.now().minusDays(1)
        val encoded = json.encodeToString(
            PipelineDispatchJob.serializer(),
            PipelineDispatchJob(eventName = "e", eventPayload = JsonObject(emptyMap()), eventCreated = stamped),
        )
        val decoded = json.decodeFromString(PipelineDispatchJob.serializer(), encoded)
        assertEquals("e", decoded.eventName)
        // eventCreated differs from a fresh now() → the writer emits it, so it survives the round-trip.
        assertEquals(stamped, decoded.eventCreated)
    }

    @Test
    fun `run job round-trips through the plain encoder (encodeDefaults=false write arm)`() {
        val id = UUID.random()
        val stamped = java.time.OffsetDateTime.now().minusDays(1)
        val encoded = json.encodeToString(
            PipelineRunJob.serializer(),
            PipelineRunJob(pipelineId = id, eventName = "e", eventPayload = JsonObject(emptyMap()), eventCreated = stamped),
        )
        val decoded = json.decodeFromString(PipelineRunJob.serializer(), encoded)
        assertEquals(id, decoded.pipelineId)
        assertEquals(stamped, decoded.eventCreated)
    }

    // NOTE: the eventCreated default is `OffsetDateTime.now()`, which the compiler inlines into
    // `write$Self` and re-evaluates at encode time, so the field is SKIPPED only when the stored stamp
    // equals that fresh now(). Reaching that arm requires winning a real-clock race (the stamp must
    // land in the same clock tick as the encode-time read), so it's coverable only by luck and the
    // outcome flips with clock resolution (ms/µs/ns) across platforms and JDKs. It's also dead in
    // practice: eventCreated is always stamped at fire time, strictly before the later encode. We do
    // NOT test that arm — a timing-race assertion is inherently flaky. It's accounted for as a tolerated
    // uncoverable serializer arm in the Kover residual (see pipelines/build.gradle.kts). The encode arm
    // (stamp != now()) is covered deterministically by the round-trip tests above.
}
