@file:OptIn(bosca.di.annotation.InternalDI::class, bosca.core.annotations.Internal::class)

package bosca.pipelines.service

import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.events.catalog.EventCatalogRegistrar
import bosca.events.catalog.EventDescriptor
import bosca.pipelines.PipelineContext
import bosca.pipelines.annotation.NodeCategory
import bosca.pipelines.configuration.PipelinesRuntimeConfiguration
import bosca.pipelines.model.NodeExecutionRecord
import bosca.pipelines.model.NodeExecutionStatus
import bosca.pipelines.model.NodeMetrics
import bosca.pipelines.model.Pipeline
import bosca.pipelines.model.PipelineEdge
import bosca.pipelines.model.PipelineGraph
import bosca.pipelines.model.PipelineRun
import bosca.pipelines.model.PipelineRunAwait
import bosca.pipelines.model.PipelineRunLog
import bosca.pipelines.model.PipelineRunLogWithName
import bosca.pipelines.model.PipelineRunStatus
import bosca.pipelines.node.InputNode
import bosca.pipelines.node.NodeDescriptor
import bosca.pipelines.node.NodeExecutionEvent
import bosca.pipelines.node.NodeOutputSlot
import bosca.pipelines.node.PipelineValue
import bosca.pipelines.node.RollbackEvent
import bosca.pipelines.repository.RollbackRepository
import bosca.pipelines.repository.NodeExecutionRepository
import bosca.pipelines.repository.PipelineRunIteration
import bosca.pipelines.repository.PipelineRunIterationRepository
import bosca.pipelines.repository.PipelineRunLogRepository
import bosca.pipelines.repository.PipelineRunRepository
import bosca.pipelines.model.PipelineRunUpdate
import bosca.pipelines.service.PipelineRunService.Companion.runEventChannel
import bosca.pipelines.trigger.PipelineChildRunJob
import bosca.pipelines.trigger.PipelineChildRunJobExecutor
import bosca.pipelines.trigger.PipelineManualRunJob
import bosca.pipelines.trigger.PipelineManualRunJobExecutor
import bosca.pubsub.PubSubService
import bosca.security.model.Principal
import bosca.security.service.AuthenticationContext
import bosca.security.service.SecurityService
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.InternalJobConstructor
import bosca.sharedqueue.jobs.Job
import bosca.sharedqueue.jobs.JobConfigurationEnqueuer
import bosca.sharedqueue.jobs.JobExecutor
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.asCoroutineContext
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.time.OffsetDateTime
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration

class PipelineRunServiceImplTest {

    @Serializable
    private data class SampleEvent(val id: String)

    private val json = Json
    private val pipeline = Pipeline(id = UUID.random(), name = "p", acceptedInputType = "sample")

    // `start` now snapshots the graph before driving, so the default PipelineService must answer
    // graphAsJsonElement; a test that needs a specific snapshot passes its own PipelineService.
    private fun stubbedPipelineService(): PipelineService = mockk<PipelineService>().also {
        coEvery { it.graphAsJsonElement(any()) } returns JsonObject(emptyMap())
    }

    private fun service(
        runRepo: PipelineRunRepository = mockk(),
        // `start` now drives to a terminal state, which appends run history; the default answers it
        // (a test asserting on history passes its own logRepo with a capture slot).
        logRepo: PipelineRunLogRepository = mockk(relaxed = true),
        resultStore: PipelineRunResultStore = mockk(),
        pipelineService: PipelineService = stubbedPipelineService(),
        executor: PipelineExecutor = mockk(),
        nodeExecutionRepo: NodeExecutionRepository = mockk(relaxed = true),
        iterationRepo: PipelineRunIterationRepository = mockk(relaxed = true),
        rollbackRepo: RollbackRepository = mockk(relaxed = true),
        config: PipelinesRuntimeConfiguration = mockk(relaxed = true),
        pubSub: PubSubService = mockk(relaxed = true),
        securityService: SecurityService = mockk(relaxed = true),
    ) = PipelineRunServiceImpl(
        runRepository = runRepo,
        runLogRepository = logRepo,
        resultStore = resultStore,
        nodeExecutionRepository = nodeExecutionRepo,
        iterationRepository = iterationRepo,
        rollbackRepository = rollbackRepo,
        pipelineService = pipelineService,
        executor = executor,
        securityService = securityService,
        config = config,
        pubSub = pubSub,
    )

    // An on-demand run (start with a non-null authentication) is now driven via an enqueued
    // PipelineManualRunJob rather than inline, so enqueue must resolve in every such test. Registered
    // for all tests (inline-drive tests never enqueue one, so it is harmless there) and exposed for the
    // tests that assert on what was enqueued.
    private lateinit var manualRunEnqueuer: CapturingChildRunEnqueuer

    @BeforeTest
    fun registerOnDemandRunEnqueuer() {
        // PipelineRunServiceImpl no longer takes Json in its constructor; graphJson() resolves it via
        // provide<Json>() (merging any PipelineNodeSerializers modules), so the registry must answer it.
        provides<Json>(singleton = true) { json }
        manualRunEnqueuer = registerManualRunEnqueuer()
    }

    @AfterTest
    fun tearDownRegistry() = ProviderRegistry.clear()

    @Test
    fun `start snapshots the graph and seeds the encoded input as RUNNING`() = runTest {
        val runRepo = mockk<PipelineRunRepository>(relaxed = true)
        val pipelineService = stubbedPipelineService()
        val snapshot = buildJsonObject { put("nodes", "snapshot") }
        coEvery { pipelineService.graphAsJsonElement(pipeline) } returns snapshot
        val captured = slot<PipelineRun>()
        coEvery { runRepo.add(capture(captured)) } answers { captured.captured.copy(id = UUID.random()) }
        coEvery { runRepo.getById(any()) } answers { captured.captured.copy(id = UUID.random()) }
        val executor = mockk<PipelineExecutor>()
        coEvery { executor.execute(any(), any(), any(), any()) } returns ExecutionResult.Completed(null)

        val sut = service(runRepo = runRepo, pipelineService = pipelineService, executor = executor)
        val input = PipelineValue.of(SampleEvent("e1"), SampleEvent.serializer())
        val run = sut.start(pipeline, input, "sample.event", OffsetDateTime.now())

        assertEquals(PipelineRunStatus.RUNNING, captured.captured.status)
        assertEquals(pipeline.id, captured.captured.pipelineId)
        assertEquals("sample.event", captured.captured.eventName)
        assertEquals(snapshot, captured.captured.graphSnapshot)
        assertEquals(
            json.encodeToJsonElement(SampleEvent.serializer(), SampleEvent("e1")),
            captured.captured.input,
        )
        assertEquals(SampleEvent.serializer().descriptor.serialName, captured.captured.inputType)
        assertNotNull(run)
        assertTrue(run.id != UUID.NIL)
    }

    private val sampleInput get() = PipelineValue.of(SampleEvent("e"), SampleEvent.serializer())

    @Test
    fun `completed start remains correct when every external boundary suspends`() = runTest {
        val runId = UUID.random()
        val created = PipelineRun(
            id = runId,
            pipelineId = pipeline.id,
            status = PipelineRunStatus.RUNNING,
            eventName = "sample.event",
            graphSnapshot = JsonObject(emptyMap()),
        )
        val completed = created.copy(status = PipelineRunStatus.OK)
        val runRepo = mockk<PipelineRunRepository>()
        val logRepo = mockk<PipelineRunLogRepository>()
        val pipelineService = mockk<PipelineService>()
        val executor = mockk<PipelineExecutor>()
        val pubSub = mockk<PubSubService>()

        coEvery { pipelineService.graphAsJsonElement(pipeline) } coAnswers {
            yield()
            JsonObject(emptyMap())
        }
        coEvery { runRepo.add(any()) } coAnswers {
            yield()
            firstArg<PipelineRun>().copy(id = runId)
        }
        coEvery { executor.execute(any(), any(), any(), any()) } coAnswers {
            yield()
            ExecutionResult.Completed(null)
        }
        coEvery { runRepo.complete(runId, PipelineRunStatus.OK, any(), null) } coAnswers {
            yield()
            completed
        }
        coEvery { logRepo.add(any()) } coAnswers {
            yield()
            firstArg<PipelineRunLog>()
        }
        coEvery { pubSub.publish(any(), any(), any<PipelineRunUpdate>()) } coAnswers { yield() }
        coEvery { runRepo.getById(runId) } coAnswers {
            yield()
            completed
        }

        val drivingJob = InternalJobConstructor(JsonObject(emptyMap()), DummyExecutor::class)
        val result = withContext(mockk<JobQueue>(relaxed = true).asCoroutineContext(drivingJob)) {
            service(
                runRepo = runRepo,
                logRepo = logRepo,
                pipelineService = pipelineService,
                executor = executor,
                pubSub = pubSub,
            ).start(pipeline, sampleInput, "sample.event", OffsetDateTime.now())
        }

        assertEquals(runId, result?.id)
        assertEquals(PipelineRunStatus.OK, result?.status)
    }

    @Test
    fun `iteration attaches a child directly to the supplied run job`() = runTest {
        val runRepo = mockk<PipelineRunRepository>(relaxed = true)
        val pipelineService = stubbedPipelineService()
        val iterationRepo = mockk<PipelineRunIterationRepository>(relaxed = true)
        val body = pipeline.copy(id = UUID.random())
        val parentJob = mockk<Job>(relaxed = true)
        coEvery { pipelineService.get(body.id) } returns body
        coEvery { pipelineService.graphAsJsonElement(body) } returns JsonObject(emptyMap())
        coEvery { runRepo.add(any()) } answers { firstArg<PipelineRun>().copy(id = UUID.random()) }
        registerChildRunEnqueuer()

        service(runRepo = runRepo, pipelineService = pipelineService, iterationRepo = iterationRepo)
            .startIteration(
                UUID.random(), "each", body.id, listOf(JsonPrimitive("item")), false,
                OffsetDateTime.now(), runJob = parentJob,
            )

        io.mockk.verify(exactly = 1) { parentJob.addChild(any(), runOnParentComplete = false) }
    }

    @Test
    fun `iteration enqueues unparented when a persisted run job lookup yields no job`() = runTest {
        val runRepo = mockk<PipelineRunRepository>(relaxed = true)
        val pipelineService = stubbedPipelineService()
        val iterationRepo = mockk<PipelineRunIterationRepository>(relaxed = true)
        val body = pipeline.copy(id = UUID.random())
        val parentJobId = UUID.random()
        val queue = mockk<JobQueue>(relaxed = true)
        coEvery { pipelineService.get(body.id) } returns body
        coEvery { pipelineService.graphAsJsonElement(body) } returns JsonObject(emptyMap())
        coEvery { runRepo.add(any()) } answers { firstArg<PipelineRun>().copy(id = UUID.random()) }
        coEvery { queue.getJob<Unit>(parentJobId, any()) } coAnswers {
            @Suppress("UNCHECKED_CAST")
            (invocation.args[1] as suspend (Job?) -> Unit).invoke(null)
        }
        provides<JobConfigurationEnqueuer>(name = PipelineChildRunJobExecutor.NAME, overrideExisting = true) {
            object : JobConfigurationEnqueuer {
                override val queueName = "pipeline-child-run"
                override suspend fun prepare(configuration: JsonElement, initializer: suspend Job.() -> Unit): Job =
                    InternalJobConstructor(configuration, DummyExecutor::class).also { it.initializer() }
                override suspend fun enqueue(configuration: JsonElement, initializer: suspend Job.() -> Unit): Job =
                    prepare(configuration, initializer)
                override suspend fun enqueueLater(configuration: JsonElement, timeout: Duration, initializer: suspend Job.() -> Unit): Job =
                    prepare(configuration, initializer)
                override suspend fun queue(): JobQueue = queue
            }
        }

        service(runRepo = runRepo, pipelineService = pipelineService, iterationRepo = iterationRepo)
            .startIteration(
                UUID.random(), "each", body.id, listOf(JsonPrimitive("item")), false,
                OffsetDateTime.now(), runJobId = parentJobId,
            )

        coVerify(exactly = 1) { queue.enqueue(any()) }
        coVerify(exactly = 0) { queue.setJob(any()) }
    }

    @Test
    fun `resume remains correct when persistence execution and publication all suspend`() = runTest {
        val runId = UUID.random()
        val suspended = suspendedRun(runId, awaiting = listOf(PipelineRunAwait("n1")))
        val running = suspended.copy(status = PipelineRunStatus.RUNNING, version = suspended.version + 1)
        val runRepo = mockk<PipelineRunRepository>()
        val logRepo = mockk<PipelineRunLogRepository>()
        val store = mockk<PipelineRunResultStore>()
        val pipelineService = mockk<PipelineService>()
        val executor = mockk<PipelineExecutor>()
        val nodeRepo = mockk<NodeExecutionRepository>()
        val pubSub = mockk<PubSubService>()

        coEvery { runRepo.getById(runId) } coAnswers { yield(); suspended }
        coEvery { pipelineService.decodeGraph(any()) } coAnswers {
            yield()
            PipelineGraph(nodes = listOf(InputNode(id = "n1", acceptedType = "JSON")))
        }
        coEvery { pipelineService.descriptorFor(any()) } coAnswers { yield(); null }
        coEvery { store.get(runId, "n1") } coAnswers {
            yield()
            PipelineRunNodeResult(JsonPrimitive("stored"))
        }
        coEvery { runRepo.updateState(runId, PipelineRunStatus.RUNNING, any(), any(), null, suspended.version) } coAnswers {
            yield()
            running
        }
        coEvery { store.remove(runId, "n1") } coAnswers { yield() }
        coEvery { nodeRepo.add(any()) } coAnswers { yield(); firstArg<NodeExecutionRecord>() }
        coEvery { executor.execute(any(), any(), any(), any()) } coAnswers {
            yield()
            ExecutionResult.Completed(null)
        }
        coEvery { runRepo.complete(runId, PipelineRunStatus.OK, any(), null) } coAnswers { yield(); running }
        coEvery { logRepo.add(any()) } coAnswers { yield(); firstArg<PipelineRunLog>() }
        coEvery { pubSub.publish(any(), any(), any<PipelineRunUpdate>()) } coAnswers { yield() }

        service(
            runRepo = runRepo,
            logRepo = logRepo,
            resultStore = store,
            pipelineService = pipelineService,
            executor = executor,
            nodeExecutionRepo = nodeRepo,
            pubSub = pubSub,
        ).resume(runId, "n1", succeeded = true, error = null)

        coVerify(exactly = 1) { runRepo.updateState(runId, PipelineRunStatus.RUNNING, any(), any(), null, suspended.version) }
        coVerify(exactly = 1) { nodeRepo.add(match { it.nodeId == "n1" && it.status == NodeExecutionStatus.OK }) }
    }

    @Test
    fun `startIfAdmitted sheds a run at the concurrency cap`() = runTest {
        val runRepo = mockk<PipelineRunRepository>()
        val capped = pipeline.copy(maxConcurrentRuns = 2)
        coEvery { runRepo.countActive(capped.id) } returns 2
        val sut = service(runRepo = runRepo)

        assertNull(sut.start(capped, sampleInput, "e", OffsetDateTime.now()), "at cap → shed")
        coVerify(exactly = 0) { runRepo.add(any()) }
    }

    @Test
    fun `startIfAdmitted starts a run under the concurrency cap`() = runTest {
        val runRepo = mockk<PipelineRunRepository>(relaxed = true)
        val pipelineService = stubbedPipelineService()
        val executor = mockk<PipelineExecutor>()
        val capped = pipeline.copy(maxConcurrentRuns = 2)
        coEvery { runRepo.countActive(capped.id) } returns 1
        coEvery { pipelineService.graphAsJsonElement(capped) } returns JsonObject(emptyMap())
        coEvery { runRepo.add(any()) } answers { firstArg<PipelineRun>().copy(id = UUID.random()) }
        coEvery { executor.execute(any(), any(), any(), any()) } returns ExecutionResult.Completed(null)
        val sut = service(runRepo = runRepo, pipelineService = pipelineService, executor = executor)

        assertNotNull(sut.start(capped, sampleInput, "e", OffsetDateTime.now()), "under cap → starts")
        coVerify(exactly = 1) { runRepo.add(any()) }
    }

    @Test
    fun `startIfAdmitted sheds a run at the per-minute rate limit`() = runTest {
        val runRepo = mockk<PipelineRunRepository>()
        // Only a rate cap is set, so the concurrency count is never consulted.
        val rated = pipeline.copy(maxRunsPerMinute = 5)
        coEvery { runRepo.countStartedSince(rated.id, any()) } returns 5
        val sut = service(runRepo = runRepo)

        assertNull(sut.start(rated, sampleInput, "e", OffsetDateTime.now()), "at rate limit → shed")
        coVerify(exactly = 0) { runRepo.add(any()) }
    }

    @Test
    fun `startIfAdmitted with no caps always starts`() = runTest {
        val runRepo = mockk<PipelineRunRepository>(relaxed = true)
        val pipelineService = stubbedPipelineService()
        val executor = mockk<PipelineExecutor>()
        coEvery { pipelineService.graphAsJsonElement(pipeline) } returns JsonObject(emptyMap())
        coEvery { runRepo.add(any()) } answers { firstArg<PipelineRun>().copy(id = UUID.random()) }
        coEvery { executor.execute(any(), any(), any(), any()) } returns ExecutionResult.Completed(null)
        val sut = service(runRepo = runRepo, pipelineService = pipelineService, executor = executor)

        assertNotNull(sut.start(pipeline, sampleInput, "e", OffsetDateTime.now()))
        coVerify(exactly = 1) { runRepo.add(any()) }
    }

    @Test
    fun `complete records the terminal status and error`() = runTest {
        val runRepo = mockk<PipelineRunRepository>()
        val id = UUID.random()
        coEvery { runRepo.complete(id, PipelineRunStatus.FAILED, any(), "boom") } returns null

        service(runRepo = runRepo).complete(id, PipelineRunStatus.FAILED, "boom")

        coVerify(exactly = 1) { runRepo.complete(id, PipelineRunStatus.FAILED, any(), "boom") }
    }

    @Test
    fun `recordLog appends to the run history`() = runTest {
        val logRepo = mockk<PipelineRunLogRepository>()
        val log = PipelineRunLog(
            pipelineId = pipeline.id,
            eventName = "sample.event",
            outcome = bosca.pipelines.model.PipelineRunStatus.OK,
            startedAt = OffsetDateTime.now(),
        )
        coEvery { logRepo.add(log) } returns log

        service(logRepo = logRepo).recordLog(log)

        coVerify(exactly = 1) { logRepo.add(log) }
    }

    @Test
    fun `resume success records the stored result as the node output and advances`() = runTest {
        val runRepo = mockk<PipelineRunRepository>()
        val store = mockk<PipelineRunResultStore>()
        val pipelineService = stubbedPipelineService()
        val executor = mockk<PipelineExecutor>()
        val logRepo = mockk<PipelineRunLogRepository>(relaxed = true)
        val runId = UUID.random()
        val suspended = suspendedRun(runId, awaiting = listOf(PipelineRunAwait("n1")))
        coEvery { runRepo.getById(runId) } returns suspended
        coEvery { pipelineService.decodeGraph(any()) } returns PipelineGraph()
        coEvery { store.get(runId, "n1") } returns PipelineRunNodeResult(JsonPrimitive("stored"))
        coEvery { store.remove(runId, "n1") } returns Unit
        val mergedOutputs = slot<JsonElement>()
        val running = suspended.copy(status = PipelineRunStatus.RUNNING, version = suspended.version + 1)
        coEvery {
            runRepo.updateState(runId, PipelineRunStatus.RUNNING, capture(mergedOutputs), any(), null, suspended.version)
        } returns running
        coEvery { executor.execute(any(), any(), any(), any()) } returns ExecutionResult.Completed(null)
        coEvery { runRepo.complete(runId, PipelineRunStatus.OK, any(), null) } returns running

        service(runRepo = runRepo, logRepo = logRepo, resultStore = store, pipelineService = pipelineService, executor = executor)
            .resume(runId, "n1", succeeded = true, error = null)

        // The node's stored output is promoted into the checkpoint, and the store entry is cleared.
        assertEquals(JsonPrimitive("stored"), mergedOutputs.captured.jsonObject["n1"])
        coVerify(exactly = 1) { store.remove(runId, "n1") }
    }

    @Test
    fun `resume failure fails the run and never transitions back to running`() = runTest {
        val runRepo = mockk<PipelineRunRepository>(relaxed = true)
        val store = mockk<PipelineRunResultStore>(relaxed = true)
        val logRepo = mockk<PipelineRunLogRepository>(relaxed = true)
        val pipelineService = stubbedPipelineService()
        // No node declares a wired error port (empty graph) -> a failure fails the whole run.
        coEvery { pipelineService.decodeGraph(any()) } returns PipelineGraph()
        val runId = UUID.random()
        coEvery { runRepo.getById(runId) } returns suspendedRun(runId, awaiting = listOf(PipelineRunAwait("n1")))

        service(runRepo = runRepo, logRepo = logRepo, resultStore = store, pipelineService = pipelineService)
            .resume(runId, "n1", succeeded = false, error = "job blew up")

        coVerify(exactly = 1) { runRepo.complete(runId, PipelineRunStatus.FAILED, any(), "job blew up") }
        coVerify(exactly = 0) { runRepo.updateState(any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 1) { store.remove(runId, "n1") }
    }

    @Test
    fun `resume is a no-op when the node output is already recorded`() = runTest {
        val runRepo = mockk<PipelineRunRepository>()
        val runId = UUID.random()
        coEvery { runRepo.getById(runId) } returns suspendedRun(
            runId,
            nodeOutputs = buildJsonObject { put("n1", JsonPrimitive("already")) },
        )

        service(runRepo = runRepo).resume(runId, "n1", succeeded = true, error = null)

        coVerify(exactly = 0) { runRepo.updateState(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `resume is a no-op when the run is already terminal`() = runTest {
        val runRepo = mockk<PipelineRunRepository>()
        val runId = UUID.random()
        coEvery { runRepo.getById(runId) } returns suspendedRun(runId).copy(status = PipelineRunStatus.OK)

        service(runRepo = runRepo).resume(runId, "n1", succeeded = true, error = null)

        coVerify(exactly = 0) { runRepo.updateState(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `resume failure routes to a wired error port instead of failing the run`() = runTest {
        val runRepo = mockk<PipelineRunRepository>()
        val store = mockk<PipelineRunResultStore>(relaxed = true)
        val pipelineService = stubbedPipelineService()
        val executor = mockk<PipelineExecutor>()
        val logRepo = mockk<PipelineRunLogRepository>(relaxed = true)
        val runId = UUID.random()
        val suspended = suspendedRun(runId, awaiting = listOf(PipelineRunAwait("n1")))
        coEvery { runRepo.getById(runId) } returns suspended
        // n1 declares an error output port that is wired to a downstream node.
        val graph = PipelineGraph(
            nodes = listOf(InputNode(id = "n1", acceptedType = "X")),
            edges = listOf(PipelineEdge(id = "e", source = "n1", target = "g", sourcePort = "error")),
        )
        coEvery { pipelineService.decodeGraph(any()) } returns graph
        coEvery { pipelineService.descriptorFor(any()) } returns NodeDescriptor(
            key = "x", label = "X", category = NodeCategory.ACTION,
            outputs = listOf(NodeOutputSlot(name = "error", error = true)),
        )
        coEvery {
            runRepo.updateState(runId, PipelineRunStatus.RUNNING, any(), any(), null, suspended.version)
        } returns suspended.copy(status = PipelineRunStatus.RUNNING, version = suspended.version + 1)
        coEvery { executor.execute(any(), any(), any(), any()) } returns ExecutionResult.Completed(null)
        coEvery { runRepo.complete(runId, PipelineRunStatus.OK, any(), null) } returns suspended

        service(runRepo = runRepo, logRepo = logRepo, resultStore = store, pipelineService = pipelineService, executor = executor)
            .resume(runId, "n1", succeeded = false, error = "409 conflict")

        // The failure routed (RUNNING transition + re-drive), the run was NOT failed outright.
        coVerify(exactly = 1) { runRepo.updateState(runId, PipelineRunStatus.RUNNING, any(), any(), null, suspended.version) }
        coVerify(exactly = 0) { runRepo.complete(runId, PipelineRunStatus.FAILED, any(), any()) }
    }

    @Test
    fun `resume routes a stored alreadyExists outcome to its wired error port`() = runTest {
        val runRepo = mockk<PipelineRunRepository>()
        val store = mockk<PipelineRunResultStore>(relaxed = true)
        val pipelineService = stubbedPipelineService()
        val executor = mockk<PipelineExecutor>()
        val logRepo = mockk<PipelineRunLogRepository>(relaxed = true)
        val runId = UUID.random()
        val suspended = suspendedRun(runId, awaiting = listOf(PipelineRunAwait("n1")))
        coEvery { runRepo.getById(runId) } returns suspended
        // n1 declares an alreadyExists error port, wired to a downstream node.
        val graph = PipelineGraph(
            nodes = listOf(InputNode(id = "n1", acceptedType = "X")),
            edges = listOf(PipelineEdge(id = "e", source = "n1", target = "branch", sourcePort = "alreadyExists")),
        )
        coEvery { pipelineService.decodeGraph(any()) } returns graph
        coEvery { pipelineService.descriptorFor(any()) } returns NodeDescriptor(
            key = "x", label = "X", category = NodeCategory.ACTION,
            outputs = listOf(NodeOutputSlot(name = "out"), NodeOutputSlot(name = "alreadyExists", error = true)),
        )
        // The job completed and staged its outcome on the alreadyExists port (a recovered 409).
        coEvery { store.get(runId, "n1") } returns
            PipelineRunNodeResult(buildJsonObject { put("id", "c1") }, port = "alreadyExists")
        coEvery {
            runRepo.updateState(runId, PipelineRunStatus.RUNNING, any(), any(), null, suspended.version)
        } returns suspended.copy(status = PipelineRunStatus.RUNNING, version = suspended.version + 1)
        coEvery { executor.execute(any(), any(), any(), any()) } returns ExecutionResult.Completed(null)
        coEvery { runRepo.complete(runId, PipelineRunStatus.OK, any(), null) } returns suspended

        service(runRepo = runRepo, logRepo = logRepo, resultStore = store, pipelineService = pipelineService, executor = executor)
            .resume(runId, "n1", succeeded = true, error = null)

        // The already-exists outcome routes down its wired branch — the run advances, it is not failed.
        coVerify(exactly = 1) { runRepo.updateState(runId, PipelineRunStatus.RUNNING, any(), any(), null, suspended.version) }
        coVerify(exactly = 0) { runRepo.complete(runId, PipelineRunStatus.FAILED, any(), any()) }
    }

    @Test
    fun `resume on an unwired error port fails the run instead of silently succeeding`() = runTest {
        val runRepo = mockk<PipelineRunRepository>(relaxed = true)
        val store = mockk<PipelineRunResultStore>(relaxed = true)
        val pipelineService = stubbedPipelineService()
        val logRepo = mockk<PipelineRunLogRepository>(relaxed = true)
        val runId = UUID.random()
        val suspended = suspendedRun(runId, awaiting = listOf(PipelineRunAwait("n1")))
        coEvery { runRepo.getById(runId) } returns suspended
        // alreadyExists is declared but NOT wired (no matching edge).
        coEvery { pipelineService.decodeGraph(any()) } returns PipelineGraph(nodes = listOf(InputNode(id = "n1", acceptedType = "X")))
        coEvery { pipelineService.descriptorFor(any()) } returns NodeDescriptor(
            key = "x", label = "X", category = NodeCategory.ACTION,
            outputs = listOf(NodeOutputSlot(name = "alreadyExists", error = true)),
        )
        coEvery { store.get(runId, "n1") } returns
            PipelineRunNodeResult(buildJsonObject { put("id", "c1") }, port = "alreadyExists")

        service(runRepo = runRepo, logRepo = logRepo, resultStore = store, pipelineService = pipelineService)
            .resume(runId, "n1", succeeded = true, error = null)

        coVerify(exactly = 1) { runRepo.complete(runId, PipelineRunStatus.FAILED, any(), any()) }
        coVerify(exactly = 0) { runRepo.updateState(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `resume backs off when a concurrent resume already advanced it`() = runTest {
        val runRepo = mockk<PipelineRunRepository>()
        val store = mockk<PipelineRunResultStore>(relaxed = true)
        val pipelineService = stubbedPipelineService()
        val executor = mockk<PipelineExecutor>()
        val runId = UUID.random()
        val suspended = suspendedRun(runId, awaiting = listOf(PipelineRunAwait("n1")))
        coEvery { runRepo.getById(runId) } returns suspended
        coEvery { pipelineService.decodeGraph(any()) } returns PipelineGraph(nodes = listOf(InputNode(id = "n1", acceptedType = "X")))
        coEvery { pipelineService.descriptorFor(any()) } returns null
        coEvery { store.get(runId, "n1") } returns PipelineRunNodeResult(JsonPrimitive("v"))
        // updateState loses the optimistic-lock race — another resume already advanced the run.
        coEvery { runRepo.updateState(runId, PipelineRunStatus.RUNNING, any(), any(), null, suspended.version) } returns null

        service(runRepo = runRepo, resultStore = store, pipelineService = pipelineService, executor = executor)
            .resume(runId, "n1", succeeded = true, error = null)

        // It must NOT re-drive (no double execution) — the winner owns the continuation.
        coVerify(exactly = 0) { executor.execute(any(), any(), any(), any()) }
    }

    @Test
    fun `resume success with no staged result records a null output`() = runTest {
        val runRepo = mockk<PipelineRunRepository>()
        val store = mockk<PipelineRunResultStore>(relaxed = true)
        val pipelineService = stubbedPipelineService()
        val executor = mockk<PipelineExecutor>()
        val logRepo = mockk<PipelineRunLogRepository>(relaxed = true)
        val runId = UUID.random()
        val suspended = suspendedRun(runId, awaiting = listOf(PipelineRunAwait("n1")))
        coEvery { runRepo.getById(runId) } returns suspended
        coEvery { pipelineService.decodeGraph(any()) } returns PipelineGraph(nodes = listOf(InputNode(id = "n1", acceptedType = "X")))
        coEvery { pipelineService.descriptorFor(any()) } returns null
        coEvery { store.get(runId, "n1") } returns null // nothing staged
        val merged = slot<JsonElement>()
        coEvery { runRepo.updateState(runId, PipelineRunStatus.RUNNING, capture(merged), any(), null, suspended.version) } returns
            suspended.copy(status = PipelineRunStatus.RUNNING, version = suspended.version + 1)
        coEvery { executor.execute(any(), any(), any(), any()) } returns ExecutionResult.Completed(null)
        coEvery { runRepo.complete(runId, PipelineRunStatus.OK, any(), null) } returns suspended

        service(runRepo = runRepo, logRepo = logRepo, resultStore = store, pipelineService = pipelineService, executor = executor)
            .resume(runId, "n1", succeeded = true, error = null)

        // Graceful: a missing result is recorded as a JSON null output rather than crashing.
        assertEquals(JsonNull, merged.captured.jsonObject["n1"])
    }

    @Test
    fun `cancel marks the run CANCELLED`() = runTest {
        val runRepo = mockk<PipelineRunRepository>(relaxed = true)
        val logRepo = mockk<PipelineRunLogRepository>(relaxed = true)
        val runId = UUID.random()
        coEvery { runRepo.getById(runId) } returns suspendedRun(runId)

        service(runRepo = runRepo, logRepo = logRepo).cancel(runId, "operator cancelled")

        coVerify(exactly = 1) { runRepo.complete(runId, PipelineRunStatus.CANCELLED, any(), "operator cancelled") }
    }

    @Test
    fun `cancel is a no-op for an already-terminal run`() = runTest {
        val runRepo = mockk<PipelineRunRepository>(relaxed = true)
        val runId = UUID.random()
        coEvery { runRepo.getById(runId) } returns suspendedRun(runId).copy(status = PipelineRunStatus.OK)

        service(runRepo = runRepo).cancel(runId, "late")

        coVerify(exactly = 0) { runRepo.complete(any(), any(), any(), any()) }
    }

    // --- live run-update broadcasting ---

    /** Capture every [PipelineRunUpdate] the service publishes (the channel/serializer are ignored). */
    private fun captureRunUpdates(pubSub: PubSubService): MutableList<PipelineRunUpdate> {
        val updates = mutableListOf<PipelineRunUpdate>()
        coEvery { pubSub.publish(any(), any(), capture(updates)) } returns Unit
        return updates
    }

    @Test
    fun `start broadcasts a RUNNING run-level update on the run's channel`() = runTest {
        val runRepo = mockk<PipelineRunRepository>(relaxed = true)
        val pipelineService = stubbedPipelineService()
        val executor = mockk<PipelineExecutor>()
        coEvery { pipelineService.graphAsJsonElement(pipeline) } returns JsonObject(emptyMap())
        val captured = slot<PipelineRun>()
        coEvery { runRepo.add(capture(captured)) } answers { captured.captured.copy(id = UUID.random()) }
        // start returns getById(run.id) after driving — answer it so the caller gets a non-null handle.
        coEvery { runRepo.getById(any()) } answers { captured.captured.copy(id = firstArg<UUID>()) }
        coEvery { executor.execute(any(), any(), any(), any()) } returns ExecutionResult.Completed(null)
        val pubSub = mockk<PubSubService>(relaxed = true)
        val updates = captureRunUpdates(pubSub)

        val run = service(runRepo = runRepo, pipelineService = pipelineService, executor = executor, pubSub = pubSub)
            .start(pipeline, sampleInput, "e", OffsetDateTime.now())

        assertNotNull(run)
        // start now also drives to a terminal state, so a terminal update follows the RUNNING one —
        // assert the RUNNING run-level update was broadcast (at least once), not an exact count.
        coVerify(atLeast = 1) { pubSub.publish(eq(runEventChannel(run.id)), any(), any<PipelineRunUpdate>()) }
        val update = updates.first { it.runId == run.id && it.nodeId == null && it.runStatus == PipelineRunStatus.RUNNING }
        assertEquals(run.id, update.runId)
        assertEquals(PipelineRunStatus.RUNNING, update.runStatus)
        assertNull(update.nodeId, "a run-level update carries no node id")
    }

    @Test
    fun `cancel broadcasts a CANCELLED run-level update`() = runTest {
        val runRepo = mockk<PipelineRunRepository>(relaxed = true)
        val logRepo = mockk<PipelineRunLogRepository>(relaxed = true)
        val runId = UUID.random()
        coEvery { runRepo.getById(runId) } returns suspendedRun(runId)
        val pubSub = mockk<PubSubService>(relaxed = true)
        val updates = captureRunUpdates(pubSub)

        service(runRepo = runRepo, logRepo = logRepo, pubSub = pubSub).cancel(runId, "stop")

        assertTrue(
            updates.any { it.runId == runId && it.runStatus == PipelineRunStatus.CANCELLED && it.nodeId == null },
            "cancel should broadcast a CANCELLED run-level update",
        )
    }

    @Test
    fun `a publish failure is swallowed and never fails the run`() = runTest {
        val runRepo = mockk<PipelineRunRepository>(relaxed = true)
        val pipelineService = stubbedPipelineService()
        val executor = mockk<PipelineExecutor>()
        coEvery { pipelineService.graphAsJsonElement(pipeline) } returns JsonObject(emptyMap())
        coEvery { runRepo.add(any()) } answers { firstArg<PipelineRun>().copy(id = UUID.random()) }
        coEvery { executor.execute(any(), any(), any(), any()) } returns ExecutionResult.Completed(null)
        val pubSub = mockk<PubSubService>()
        coEvery { pubSub.publish(any(), any(), any<PipelineRunUpdate>()) } throws RuntimeException("broker down")

        // Observability is best-effort: the broadcast error is logged and swallowed, so start still returns.
        val run = service(runRepo = runRepo, pipelineService = pipelineService, executor = executor, pubSub = pubSub)
            .start(pipeline, sampleInput, "e", OffsetDateTime.now())
        assertNotNull(run)
        assertTrue(run.id != UUID.NIL)
    }

    @Test
    fun `sweep fails each stuck suspended run`() = runTest {
        val runRepo = mockk<PipelineRunRepository>(relaxed = true)
        val logRepo = mockk<PipelineRunLogRepository>(relaxed = true)
        val r1 = suspendedRun(UUID.random())
        val r2 = suspendedRun(UUID.random())
        coEvery { runRepo.findStuckSuspended(any(), any()) } returns listOf(r1, r2)

        val swept = service(runRepo = runRepo, logRepo = logRepo).sweepStuckSuspended()

        assertEquals(2, swept)
        coVerify(exactly = 1) { runRepo.complete(r1.id, PipelineRunStatus.FAILED, any(), any()) }
        coVerify(exactly = 1) { runRepo.complete(r2.id, PipelineRunStatus.FAILED, any(), any()) }
    }

    @Test
    fun `sweep preserves only runs actually awaiting a matching approval gate`() = runTest {
        val runRepo = mockk<PipelineRunRepository>(relaxed = true)
        val gate = suspendedRun(UUID.random(), awaiting = listOf(PipelineRunAwait("gate"))).copy(
            graphSnapshot = buildJsonObject {
                put(
                    "nodes",
                    JsonArray(
                        listOf(
                            JsonPrimitive("not-an-object"),
                            buildJsonObject { put("id", "other"); put("type", "gate.approval") },
                            buildJsonObject { put("id", "gate"); put("type", "gate.approval") },
                        ),
                    ),
                )
            },
        )
        val malformedAwait = suspendedRun(UUID.random()).copy(awaiting = JsonPrimitive("bad"))
        val emptyAwait = suspendedRun(UUID.random())
        val missingNodes = suspendedRun(UUID.random(), awaiting = listOf(PipelineRunAwait("gate")))
        val wrongType = suspendedRun(UUID.random(), awaiting = listOf(PipelineRunAwait("gate"))).copy(
            graphSnapshot = buildJsonObject {
                put(
                    "nodes",
                    JsonArray(
                        listOf(
                            buildJsonObject { put("type", "gate.approval") },
                            buildJsonObject { put("id", "gate"); put("type", "waitForInput") },
                        ),
                    ),
                )
            },
        )
        val malformedAwaitEntry = suspendedRun(UUID.random()).copy(
            awaiting = JsonArray(listOf(
                JsonPrimitive("bad"),
                buildJsonObject { put("nodeId", JsonNull) },
                buildJsonObject { put("nodeId", buildJsonObject { }) },
                buildJsonObject { put("other", "missing") },
            )),
            graphSnapshot = buildJsonObject {
                put("nodes", JsonArray(listOf(buildJsonObject { put("id", "gate"); put("type", "gate.approval") })))
            },
        )
        val nonObjectGraph = suspendedRun(UUID.random(), awaiting = listOf(PipelineRunAwait("gate"))).copy(
            graphSnapshot = JsonPrimitive("bad"),
        )
        val nonArrayNodes = suspendedRun(UUID.random(), awaiting = listOf(PipelineRunAwait("gate"))).copy(
            graphSnapshot = buildJsonObject { put("nodes", "bad") },
        )
        val malformedGateFields = suspendedRun(UUID.random(), awaiting = listOf(PipelineRunAwait("gate"))).copy(
            graphSnapshot = buildJsonObject {
                put("nodes", JsonArray(listOf(
                    buildJsonObject { put("id", JsonNull); put("type", "gate.approval") },
                    buildJsonObject { put("id", "gate"); put("type", JsonNull) },
                    buildJsonObject { put("id", buildJsonObject { }); put("type", "gate.approval") },
                    buildJsonObject { put("id", "gate"); put("type", buildJsonObject { }) },
                )))
            },
        )
        coEvery { runRepo.findStuckSuspended(any(), any()) } returns
            listOf(
                gate,
                malformedAwait,
                emptyAwait,
                missingNodes,
                wrongType,
                malformedAwaitEntry,
                nonObjectGraph,
                nonArrayNodes,
                malformedGateFields,
            )

        assertEquals(8, service(runRepo = runRepo).sweepStuckSuspended())

        coVerify(exactly = 0) { runRepo.complete(gate.id, any(), any(), any()) }
        listOf(
            malformedAwait,
            emptyAwait,
            missingNodes,
            wrongType,
            malformedAwaitEntry,
            nonObjectGraph,
            nonArrayNodes,
            malformedGateFields,
        ).forEach { run ->
            coVerify(exactly = 1) { runRepo.complete(run.id, PipelineRunStatus.FAILED, any(), any()) }
        }
    }

    // ----------------------------------------------------------------------------------------------
    // startIfAdmitted — additional guard arms
    // ----------------------------------------------------------------------------------------------

    @Test
    fun `startIfAdmitted ignores a non-positive concurrency cap and starts`() = runTest {
        val runRepo = mockk<PipelineRunRepository>(relaxed = true)
        val pipelineService = stubbedPipelineService()
        val executor = mockk<PipelineExecutor>()
        // maxConcurrentRuns = 0 → takeIf { it > 0 } yields null, so the cap branch is skipped entirely.
        val zeroCap = pipeline.copy(maxConcurrentRuns = 0)
        coEvery { pipelineService.graphAsJsonElement(zeroCap) } returns JsonObject(emptyMap())
        coEvery { runRepo.add(any()) } answers { firstArg<PipelineRun>().copy(id = UUID.random()) }
        coEvery { executor.execute(any(), any(), any(), any()) } returns ExecutionResult.Completed(null)
        val sut = service(runRepo = runRepo, pipelineService = pipelineService, executor = executor)

        assertNotNull(sut.start(zeroCap, sampleInput, "e", OffsetDateTime.now()))
        coVerify(exactly = 0) { runRepo.countActive(any()) }
        coVerify(exactly = 1) { runRepo.add(any()) }
    }

    @Test
    fun `startIfAdmitted ignores a non-positive rate cap and starts`() = runTest {
        val runRepo = mockk<PipelineRunRepository>(relaxed = true)
        val pipelineService = stubbedPipelineService()
        val executor = mockk<PipelineExecutor>()
        val zeroRate = pipeline.copy(maxRunsPerMinute = 0)
        coEvery { pipelineService.graphAsJsonElement(zeroRate) } returns JsonObject(emptyMap())
        coEvery { runRepo.add(any()) } answers { firstArg<PipelineRun>().copy(id = UUID.random()) }
        coEvery { executor.execute(any(), any(), any(), any()) } returns ExecutionResult.Completed(null)
        val sut = service(runRepo = runRepo, pipelineService = pipelineService, executor = executor)

        assertNotNull(sut.start(zeroRate, sampleInput, "e", OffsetDateTime.now()))
        coVerify(exactly = 0) { runRepo.countStartedSince(any(), any()) }
        coVerify(exactly = 1) { runRepo.add(any()) }
    }

    @Test
    fun `startIfAdmitted under both caps consults both gates then starts`() = runTest {
        val runRepo = mockk<PipelineRunRepository>(relaxed = true)
        val pipelineService = stubbedPipelineService()
        val executor = mockk<PipelineExecutor>()
        val both = pipeline.copy(maxConcurrentRuns = 3, maxRunsPerMinute = 10)
        coEvery { runRepo.countActive(both.id) } returns 1
        coEvery { runRepo.countStartedSince(both.id, any()) } returns 2
        coEvery { pipelineService.graphAsJsonElement(both) } returns JsonObject(emptyMap())
        coEvery { runRepo.add(any()) } answers { firstArg<PipelineRun>().copy(id = UUID.random()) }
        coEvery { executor.execute(any(), any(), any(), any()) } returns ExecutionResult.Completed(null)
        val sut = service(runRepo = runRepo, pipelineService = pipelineService, executor = executor)

        assertNotNull(sut.start(both, sampleInput, "e", OffsetDateTime.now()))
        coVerify(exactly = 1) { runRepo.countActive(both.id) }
        coVerify(exactly = 1) { runRepo.countStartedSince(both.id, any()) }
        coVerify(exactly = 1) { runRepo.add(any()) }
    }

    @Test
    fun `startIfAdmitted under the concurrency cap but at the rate limit sheds`() = runTest {
        val runRepo = mockk<PipelineRunRepository>()
        val both = pipeline.copy(maxConcurrentRuns = 3, maxRunsPerMinute = 10)
        coEvery { runRepo.countActive(both.id) } returns 1 // under the concurrency cap
        coEvery { runRepo.countStartedSince(both.id, any()) } returns 10 // at the rate limit
        val sut = service(runRepo = runRepo)

        assertNull(sut.start(both, sampleInput, "e", OffsetDateTime.now()))
        coVerify(exactly = 0) { runRepo.add(any()) }
    }

    // ----------------------------------------------------------------------------------------------
    // get / listActive / listDeadLetter / nodeTimeline / nodeMetrics / listRunHistory / listAllRunHistory
    // ----------------------------------------------------------------------------------------------

    @Test
    fun `get returns the run row from the repository`() = runTest {
        val runRepo = mockk<PipelineRunRepository>()
        val runId = UUID.random()
        val run = suspendedRun(runId)
        coEvery { runRepo.getById(runId) } returns run

        assertEquals(run, service(runRepo = runRepo).get(runId))
    }

    @Test
    fun `get returns null when the run does not exist`() = runTest {
        val runRepo = mockk<PipelineRunRepository>()
        val runId = UUID.random()
        coEvery { runRepo.getById(runId) } returns null

        assertNull(service(runRepo = runRepo).get(runId))
    }

    @Test
    fun `listActive forwards paging to the repository`() = runTest {
        val runRepo = mockk<PipelineRunRepository>()
        val rows = listOf(suspendedRun(UUID.random()))
        coEvery { runRepo.listActive(5, 10) } returns rows

        assertEquals(rows, service(runRepo = runRepo).listActive(5, 10))
    }

    @Test
    fun `listActive returns an empty list when there are no active runs`() = runTest {
        val runRepo = mockk<PipelineRunRepository>()
        coEvery { runRepo.listActive(0, 10) } returns emptyList()

        assertTrue(service(runRepo = runRepo).listActive(0, 10).isEmpty())
    }

    @Test
    fun `listDeadLetter forwards to listFailed`() = runTest {
        val runRepo = mockk<PipelineRunRepository>()
        val failed = listOf(suspendedRun(UUID.random()).copy(status = PipelineRunStatus.FAILED))
        coEvery { runRepo.listFailed(3, 7) } returns failed

        assertEquals(failed, service(runRepo = runRepo).listDeadLetter(3, 7))
        coVerify(exactly = 1) { runRepo.listFailed(3, 7) }
    }

    @Test
    fun `nodeTimeline forwards to the node-execution repository`() = runTest {
        val nodeRepo = mockk<NodeExecutionRepository>()
        val runId = UUID.random()
        val records = listOf(
            NodeExecutionRecord(
                runId = runId,
                nodeId = "n1",
                status = NodeExecutionStatus.OK,
                startedAt = OffsetDateTime.now(),
                finishedAt = OffsetDateTime.now(),
                durationMs = 5,
            )
        )
        coEvery { nodeRepo.listForRun(runId) } returns records

        assertEquals(records, service(nodeExecutionRepo = nodeRepo).nodeTimeline(runId))
    }

    @Test
    fun `nodeMetrics forwards to the node-execution repository`() = runTest {
        val nodeRepo = mockk<NodeExecutionRepository>()
        val metrics = listOf(NodeMetrics(nodeId = "n1", executions = 3, failures = 1, p50Ms = 10.0, p95Ms = 50.0))
        coEvery { nodeRepo.metricsForPipeline(pipeline.id) } returns metrics

        assertEquals(metrics, service(nodeExecutionRepo = nodeRepo).nodeMetrics(pipeline.id))
    }

    @Test
    fun `listRunHistory forwards paging to the log repository`() = runTest {
        val logRepo = mockk<PipelineRunLogRepository>()
        val rows = listOf(historyRow())
        coEvery { logRepo.listForPipeline(pipeline.id, 2, 25) } returns rows

        assertEquals(rows, service(logRepo = logRepo).listRunHistory(pipeline.id, 2, 25))
    }

    @Test
    fun `listAllRunHistory forwards paging to the log repository`() = runTest {
        val logRepo = mockk<PipelineRunLogRepository>()
        val rows = listOf(historyRow(), historyRow())
        coEvery { logRepo.listAll(0, 50) } returns rows

        assertEquals(rows, service(logRepo = logRepo).listAllRunHistory(0, 50))
    }

    // ----------------------------------------------------------------------------------------------
    // purgeExpiredRuns
    // ----------------------------------------------------------------------------------------------

    @Test
    fun `purgeExpiredRuns sums purged run-state and history rows`() = runTest {
        val runRepo = mockk<PipelineRunRepository>()
        val logRepo = mockk<PipelineRunLogRepository>()
        coEvery { runRepo.purgeTerminalBefore(any()) } returns 4
        coEvery { logRepo.deleteFinishedBefore(any()) } returns 9
        val cfg = mockk<PipelinesRuntimeConfiguration>(relaxed = true)
        coEvery { cfg.runStateRetentionDays } returns 30
        coEvery { cfg.runHistoryRetentionDays } returns 90

        val purged = service(runRepo = runRepo, logRepo = logRepo, config = cfg).purgeExpiredRuns()

        assertEquals(13, purged)
    }

    @Test
    fun `purgeExpiredRuns purges nothing and skips the log line when both counts are zero`() = runTest {
        val runRepo = mockk<PipelineRunRepository>()
        val logRepo = mockk<PipelineRunLogRepository>()
        coEvery { runRepo.purgeTerminalBefore(any()) } returns 0
        coEvery { logRepo.deleteFinishedBefore(any()) } returns 0
        val cfg = mockk<PipelinesRuntimeConfiguration>(relaxed = true)
        coEvery { cfg.runStateRetentionDays } returns 30
        coEvery { cfg.runHistoryRetentionDays } returns 90

        assertEquals(0, service(runRepo = runRepo, logRepo = logRepo, config = cfg).purgeExpiredRuns())
    }

    @Test
    fun `purgeExpiredRuns logs when only history rows are purged`() = runTest {
        val runRepo = mockk<PipelineRunRepository>()
        val logRepo = mockk<PipelineRunLogRepository>()
        // runs = 0, history > 0 → the OR short-circuits on the right operand.
        coEvery { runRepo.purgeTerminalBefore(any()) } returns 0
        coEvery { logRepo.deleteFinishedBefore(any()) } returns 5
        val cfg = mockk<PipelinesRuntimeConfiguration>(relaxed = true)
        coEvery { cfg.runStateRetentionDays } returns 30
        coEvery { cfg.runHistoryRetentionDays } returns 90

        assertEquals(5, service(runRepo = runRepo, logRepo = logRepo, config = cfg).purgeExpiredRuns())
    }

    // ----------------------------------------------------------------------------------------------
    // replay
    // ----------------------------------------------------------------------------------------------

    @Test
    fun `replay returns null when the source run does not exist`() = runTest {
        val runRepo = mockk<PipelineRunRepository>()
        val runId = UUID.random()
        coEvery { runRepo.getById(runId) } returns null

        assertNull(service(runRepo = runRepo).restart(runId))
    }

    @Test
    fun `replay is shed when the current pipeline is already at its concurrency cap`() = runTest {
        val runRepo = mockk<PipelineRunRepository>()
        val pipelineService = stubbedPipelineService()
        val sourceId = UUID.random()
        val source = suspendedRun(sourceId).copy(status = PipelineRunStatus.FAILED, input = JsonNull)
        val capped = pipeline.copy(id = source.pipelineId, maxConcurrentRuns = 1)
        coEvery { runRepo.getById(sourceId) } returns source
        coEvery { pipelineService.get(source.pipelineId) } returns capped
        coEvery { runRepo.countActive(capped.id) } returns 1

        assertNull(service(runRepo = runRepo, pipelineService = pipelineService).restart(sourceId))
        coVerify(exactly = 0) { runRepo.add(any()) }
    }

    @Test
    fun `process ignores missing and already terminal child runs`() = runTest {
        val runRepo = mockk<PipelineRunRepository>()
        val missing = UUID.random()
        val terminal = suspendedRun(UUID.random()).copy(status = PipelineRunStatus.OK)
        coEvery { runRepo.getById(missing) } returns null
        coEvery { runRepo.getById(terminal.id) } returns terminal
        val sut = service(runRepo = runRepo)

        sut.process(missing)
        sut.process(terminal.id)

        coVerify(exactly = 0) { runRepo.complete(any(), any(), any(), any()) }
    }

    @Test
    fun `process drives a captured caller principal under that principal`() = runTest {
        val runRepo = mockk<PipelineRunRepository>(relaxed = true)
        val pipelineService = stubbedPipelineService()
        val executor = mockk<PipelineExecutor>()
        val securityService = mockk<SecurityService>()
        val runId = UUID.random()
        val principalId = UUID.random()
        val run = suspendedRun(runId).copy(
            status = PipelineRunStatus.RUNNING,
            principalId = principalId,
            input = JsonNull,
            inputType = null,
        )
        coEvery { runRepo.getById(runId) } returns run
        coEvery { pipelineService.decodeGraph(run.graphSnapshot) } returns PipelineGraph()
        coEvery { securityService.getPrincipalById(principalId) } returns Principal(id = principalId)
        coEvery { securityService.getPrincipalGroups(principalId) } returns emptyList()
        coEvery { executor.execute(any(), any(), any(), any()) } returns ExecutionResult.Completed(null)

        service(
            runRepo = runRepo,
            pipelineService = pipelineService,
            executor = executor,
            securityService = securityService,
        ).process(runId)

        coVerify(exactly = 1) { securityService.getPrincipalById(principalId) }
        coVerify(exactly = 1) { securityService.getPrincipalGroups(principalId) }
    }

    @Test
    fun `replay re-runs against the current pipeline when it still exists`() = runTest {
        val runRepo = mockk<PipelineRunRepository>(relaxed = true)
        val logRepo = mockk<PipelineRunLogRepository>(relaxed = true)
        val pipelineService = stubbedPipelineService()
        val executor = mockk<PipelineExecutor>()
        val sourceId = UUID.random()
        val source = suspendedRun(sourceId).copy(
            status = PipelineRunStatus.FAILED,
            input = JsonPrimitive("seed"),
            inputType = null,
        )
        coEvery { runRepo.getById(sourceId) } returns source
        coEvery { pipelineService.get(source.pipelineId) } returns pipeline
        coEvery { pipelineService.graphAsJsonElement(pipeline) } returns JsonObject(emptyMap())
        val replayed = slot<PipelineRun>()
        val newRunId = UUID.random()
        coEvery { runRepo.add(capture(replayed)) } answers { replayed.captured.copy(id = newRunId) }
        coEvery { executor.execute(any(), any(), any(), any()) } returns ExecutionResult.Completed(null)
        coEvery { runRepo.complete(newRunId, PipelineRunStatus.OK, any(), null) } returns suspendedRun(newRunId)
        val refreshed = suspendedRun(newRunId).copy(status = PipelineRunStatus.OK)
        coEvery { runRepo.getById(newRunId) } returns refreshed

        val result = service(runRepo = runRepo, logRepo = logRepo, pipelineService = pipelineService, executor = executor)
            .restart(sourceId)

        assertEquals(refreshed, result)
        // Replayed under the RESTART event name; the current pipeline (not the snapshot) was used.
        assertEquals(PipelineRunServiceImpl.RESTART_EVENT, replayed.captured.eventName)
        coVerify(exactly = 0) { pipelineService.decodeGraph(any()) }
    }

    @Test
    fun `replay falls back to the source snapshot graph when the pipeline was deleted`() = runTest {
        val runRepo = mockk<PipelineRunRepository>(relaxed = true)
        val logRepo = mockk<PipelineRunLogRepository>(relaxed = true)
        val pipelineService = stubbedPipelineService()
        val executor = mockk<PipelineExecutor>()
        val sourceId = UUID.random()
        val source = suspendedRun(sourceId).copy(status = PipelineRunStatus.FAILED, input = JsonNull, inputType = null)
        coEvery { runRepo.getById(sourceId) } returns source
        // The pipeline was removed since the run — get returns null, so we decode the snapshot graph.
        coEvery { pipelineService.get(source.pipelineId) } returns null
        coEvery { pipelineService.decodeGraph(source.graphSnapshot) } returns
            PipelineGraph(nodes = listOf(InputNode(id = "n1", acceptedType = "X")))
        coEvery { pipelineService.graphAsJsonElement(any()) } returns JsonObject(emptyMap())
        val newRunId = UUID.random()
        coEvery { runRepo.add(any()) } answers { firstArg<PipelineRun>().copy(id = newRunId) }
        coEvery { executor.execute(any(), any(), any(), any()) } returns ExecutionResult.Completed(null)
        coEvery { runRepo.complete(newRunId, PipelineRunStatus.OK, any(), null) } returns suspendedRun(newRunId)
        coEvery { runRepo.getById(newRunId) } returns suspendedRun(newRunId).copy(status = PipelineRunStatus.OK)

        val result = service(runRepo = runRepo, logRepo = logRepo, pipelineService = pipelineService, executor = executor)
            .restart(sourceId)

        assertNotNull(result)
        coVerify(exactly = 1) { pipelineService.decodeGraph(source.graphSnapshot) }
    }

    // ----------------------------------------------------------------------------------------------
    // runChildPipeline
    // ----------------------------------------------------------------------------------------------

    @Test
    fun `runChildPipeline errors when the body pipeline is not found`() = runTest {
        val pipelineService = stubbedPipelineService()
        val bodyId = UUID.random()
        coEvery { pipelineService.get(bodyId) } returns null

        val ex = assertFailsWith<IllegalStateException> {
            service(pipelineService = pipelineService)
                .runChildPipeline(UUID.random(), "node", bodyId, sampleInput, OffsetDateTime.now())
        }
        assertTrue(ex.message!!.contains("body pipeline not found"))
    }

    @Test
    fun `runChildPipeline starts and drives a child when there is no input schema`() = runTest {
        val runRepo = mockk<PipelineRunRepository>(relaxed = true)
        val logRepo = mockk<PipelineRunLogRepository>(relaxed = true)
        val pipelineService = stubbedPipelineService()
        val bodyId = UUID.random()
        // Body has an InputNode with no schema → the schema-validation branch is skipped.
        val body = pipeline.copy(id = bodyId, nodes = listOf(InputNode(id = "in", acceptedType = "X", schema = null)))
        coEvery { pipelineService.get(bodyId) } returns body
        coEvery { pipelineService.graphAsJsonElement(body) } returns JsonObject(emptyMap())
        val child = slot<PipelineRun>()
        val childRunId = UUID.random()
        coEvery { runRepo.add(capture(child)) } answers { child.captured.copy(id = childRunId) }
        // The child run is created and a child-run job is enqueued to drive it later (a real runner
        // would dequeue it and call driveExistingRun); the enqueuer captures the job config.
        val enqueuer = registerChildRunEnqueuer()

        val parentId = UUID.random()
        service(runRepo = runRepo, logRepo = logRepo, pipelineService = pipelineService)
            .runChildPipeline(parentId, "callNode", bodyId, sampleInput, OffsetDateTime.now())

        // itemIndex = null marks a single-child (scalar passthrough) parent; linkage is wired through.
        assertNull(child.captured.itemIndex)
        assertEquals(parentId, child.captured.parentRunId)
        assertEquals("callNode", child.captured.parentNodeId)
        assertEquals(PipelineRunServiceImpl.RUN_PIPELINE_EVENT, child.captured.eventName)
        // A child-run job was enqueued carrying the just-created child run's id.
        assertEquals(
            listOf(childRunId),
            enqueuer.configs.map { json.decodeFromJsonElement(PipelineChildRunJob.serializer(), it).childRunId },
        )
    }

    @Test
    fun `runChildPipeline validates a matching input schema and proceeds`() = runTest {
        val runRepo = mockk<PipelineRunRepository>(relaxed = true)
        val logRepo = mockk<PipelineRunLogRepository>(relaxed = true)
        val pipelineService = stubbedPipelineService()
        val bodyId = UUID.random()
        // Schema requires an object with a string `id` — SampleEvent("e") satisfies it.
        val schema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject { put("id", buildJsonObject { put("type", "string") }) })
            put("required", JsonArray(listOf(JsonPrimitive("id"))))
        }
        val body = pipeline.copy(id = bodyId, name = "child", nodes = listOf(InputNode(id = "in", acceptedType = "JSON", schema = schema)))
        coEvery { pipelineService.get(bodyId) } returns body
        coEvery { pipelineService.graphAsJsonElement(body) } returns JsonObject(emptyMap())
        coEvery { runRepo.add(any()) } answers { firstArg<PipelineRun>().copy(id = UUID.random()) }
        // The schema validates before the child run is created and its child-run job enqueued.
        val enqueuer = registerChildRunEnqueuer()

        service(runRepo = runRepo, logRepo = logRepo, pipelineService = pipelineService)
            .runChildPipeline(UUID.random(), "n", bodyId, sampleInput, OffsetDateTime.now())

        // Validation passed, the child run was created, and exactly one child-run job was enqueued.
        coVerify(exactly = 1) { runRepo.add(any()) }
        assertEquals(1, enqueuer.configs.size)
    }

    @Test
    fun `runChildPipeline fails the check when the input violates the schema`() = runTest {
        val pipelineService = stubbedPipelineService()
        val bodyId = UUID.random()
        // Schema requires a `count` integer, which SampleEvent does not have → violation.
        val schema = buildJsonObject {
            put("type", "object")
            put("required", JsonArray(listOf(JsonPrimitive("count"))))
        }
        val body = pipeline.copy(id = bodyId, name = "strict", nodes = listOf(InputNode(id = "in", acceptedType = "JSON", schema = schema)))
        coEvery { pipelineService.get(bodyId) } returns body

        val ex = assertFailsWith<IllegalStateException> {
            service(pipelineService = pipelineService)
                .runChildPipeline(UUID.random(), "n", bodyId, sampleInput, OffsetDateTime.now())
        }
        assertTrue(ex.message!!.contains("does not match pipeline 'strict' input schema"))
    }

    // ----------------------------------------------------------------------------------------------
    // startIteration
    // ----------------------------------------------------------------------------------------------

    @Test
    fun `startIteration errors when the body pipeline is not found`() = runTest {
        val pipelineService = stubbedPipelineService()
        val iterRepo = mockk<PipelineRunIterationRepository>(relaxed = true)
        val bodyId = UUID.random()
        coEvery { pipelineService.get(bodyId) } returns null

        val ex = assertFailsWith<IllegalStateException> {
            service(pipelineService = pipelineService, iterationRepo = iterRepo)
                .startIteration(UUID.random(), "fe", bodyId, listOf(JsonPrimitive(1)), continueOnError = false, OffsetDateTime.now())
        }
        assertTrue(ex.message!!.contains("body pipeline not found"))
        coVerify(exactly = 1) { iterRepo.create(any(), any(), 1, false, 0, null) }
    }

    @Test
    fun `startIteration with no items opens the aggregation and starts no children`() = runTest {
        val runRepo = mockk<PipelineRunRepository>(relaxed = true)
        val pipelineService = stubbedPipelineService()
        val iterRepo = mockk<PipelineRunIterationRepository>(relaxed = true)
        val bodyId = UUID.random()
        val body = pipeline.copy(id = bodyId)
        coEvery { pipelineService.get(bodyId) } returns body
        coEvery { pipelineService.graphAsJsonElement(body) } returns JsonObject(emptyMap())

        service(runRepo = runRepo, pipelineService = pipelineService, iterationRepo = iterRepo)
            .startIteration(UUID.random(), "fe", bodyId, emptyList(), continueOnError = true, OffsetDateTime.now())

        coVerify(exactly = 1) { iterRepo.create(any(), "fe", 0, true, 0, null) }
        coVerify(exactly = 0) { runRepo.add(any()) }
    }

    @Test
    fun `startIteration starts one indexed child per item`() = runTest {
        val runRepo = mockk<PipelineRunRepository>(relaxed = true)
        val logRepo = mockk<PipelineRunLogRepository>(relaxed = true)
        val pipelineService = stubbedPipelineService()
        val iterRepo = mockk<PipelineRunIterationRepository>(relaxed = true)
        val bodyId = UUID.random()
        val body = pipeline.copy(id = bodyId)
        coEvery { pipelineService.get(bodyId) } returns body
        coEvery { pipelineService.graphAsJsonElement(body) } returns JsonObject(emptyMap())
        val children = mutableListOf<PipelineRun>()
        val childRunIds = listOf(UUID.random(), UUID.random())
        var nextChild = 0
        coEvery { runRepo.add(capture(children)) } answers { firstArg<PipelineRun>().copy(id = childRunIds[nextChild++]) }
        // Each item's child run is created and a child-run job enqueued to drive it later.
        val enqueuer = registerChildRunEnqueuer()

        service(runRepo = runRepo, logRepo = logRepo, pipelineService = pipelineService, iterationRepo = iterRepo)
            .startIteration(
                UUID.random(), "fe", bodyId,
                listOf(JsonPrimitive("a"), JsonPrimitive("b")),
                continueOnError = false, OffsetDateTime.now(),
            )

        coVerify(exactly = 1) { iterRepo.create(any(), "fe", 2, false, 0, null) }
        assertEquals(2, children.size)
        assertEquals(listOf(0, 1), children.map { it.itemIndex })
        assertTrue(children.all { it.eventName == PipelineRunServiceImpl.ITERATION_EVENT })
        // One child-run job per item, each carrying the corresponding child run's id (index order).
        assertEquals(
            childRunIds,
            enqueuer.configs.map { json.decodeFromJsonElement(PipelineChildRunJob.serializer(), it).childRunId },
        )
    }

    @Test
    fun `startIteration with a concurrency bound starts only the first children and stores the items`() = runTest {
        val runRepo = mockk<PipelineRunRepository>(relaxed = true)
        val logRepo = mockk<PipelineRunLogRepository>(relaxed = true)
        val pipelineService = stubbedPipelineService()
        val iterRepo = mockk<PipelineRunIterationRepository>(relaxed = true)
        val bodyId = UUID.random()
        val body = pipeline.copy(id = bodyId)
        coEvery { pipelineService.get(bodyId) } returns body
        coEvery { pipelineService.graphAsJsonElement(body) } returns JsonObject(emptyMap())
        val children = mutableListOf<PipelineRun>()
        coEvery { runRepo.add(capture(children)) } answers { firstArg<PipelineRun>().copy(id = UUID.random()) }
        registerChildRunEnqueuer()

        val items = listOf<JsonElement>(JsonPrimitive("a"), JsonPrimitive("b"), JsonPrimitive("c"))
        service(runRepo = runRepo, logRepo = logRepo, pipelineService = pipelineService, iterationRepo = iterRepo)
            .startIteration(
                UUID.random(), "fe", bodyId, items,
                continueOnError = false, OffsetDateTime.now(), maxConcurrency = 1,
            )

        // The bound and the raw items are persisted so completions can start items 1 and 2 later.
        coVerify(exactly = 1) { iterRepo.create(any(), "fe", 3, false, 1, JsonArray(items)) }
        // Only item 0 started.
        assertEquals(listOf(0), children.map { it.itemIndex })
    }

    @Test
    fun `startIteration with a bound covering every item starts them all and stores nothing`() = runTest {
        val runRepo = mockk<PipelineRunRepository>(relaxed = true)
        val logRepo = mockk<PipelineRunLogRepository>(relaxed = true)
        val pipelineService = stubbedPipelineService()
        val iterRepo = mockk<PipelineRunIterationRepository>(relaxed = true)
        val bodyId = UUID.random()
        val body = pipeline.copy(id = bodyId)
        coEvery { pipelineService.get(bodyId) } returns body
        coEvery { pipelineService.graphAsJsonElement(body) } returns JsonObject(emptyMap())
        val children = mutableListOf<PipelineRun>()
        coEvery { runRepo.add(capture(children)) } answers { firstArg<PipelineRun>().copy(id = UUID.random()) }
        registerChildRunEnqueuer()

        service(runRepo = runRepo, logRepo = logRepo, pipelineService = pipelineService, iterationRepo = iterRepo)
            .startIteration(
                UUID.random(), "fe", bodyId, listOf(JsonPrimitive("a"), JsonPrimitive("b")),
                continueOnError = false, OffsetDateTime.now(), maxConcurrency = 5,
            )

        // Bound >= item count degenerates to the unbounded fan-out — no lazy state to keep.
        coVerify(exactly = 1) { iterRepo.create(any(), "fe", 2, false, 0, null) }
        assertEquals(listOf(0, 1), children.map { it.itemIndex })
    }

    // ----------------------------------------------------------------------------------------------
    // runOnDemand — block-vs-suspend, completed-in-time, terminal failure
    // ----------------------------------------------------------------------------------------------

    @Test
    fun `runOnDemand returns OK with the output for a run that completes in the first pass`() = runTest {
        val runRepo = mockk<PipelineRunRepository>(relaxed = true)
        val logRepo = mockk<PipelineRunLogRepository>(relaxed = true)
        val pipelineService = stubbedPipelineService()
        val executor = mockk<PipelineExecutor>()
        coEvery { pipelineService.graphAsJsonElement(pipeline) } returns JsonObject(emptyMap())
        val runId = UUID.random()
        coEvery { runRepo.add(any()) } answers { firstArg<PipelineRun>().copy(id = runId) }
        coEvery { executor.execute(any(), any(), any(), any()) } returns
            ExecutionResult.Completed(PipelineValue.of(SampleEvent("done"), SampleEvent.serializer()))
        coEvery { runRepo.complete(runId, PipelineRunStatus.OK, any(), null) } returns null
        // The run is already terminal-OK when we poll, so the block loop never iterates.
        val finished = suspendedRun(runId).copy(
            status = PipelineRunStatus.OK,
            output = json.encodeToJsonElement(SampleEvent.serializer(), SampleEvent("done")),
        )
        coEvery { runRepo.getById(runId) } returns finished

        val run = service(runRepo = runRepo, logRepo = logRepo, pipelineService = pipelineService, executor = executor)
            .start(pipeline, sampleInput, "manual", OffsetDateTime.now(), AuthenticationContext(null, null))

        assertEquals(runId, run!!.id)
        assertEquals(PipelineRunStatus.OK, run.status)
        assertEquals(finished.output, run.output)
        assertNull(run.error)
        assertTrue(run.status != PipelineRunStatus.FAILED && run.status != PipelineRunStatus.CANCELLED)
    }

    @Test
    fun `runOnDemand returns SUSPENDED when the run is still parked past the block window`() = runTest {
        val runRepo = mockk<PipelineRunRepository>(relaxed = true)
        val pipelineService = stubbedPipelineService()
        val executor = mockk<PipelineExecutor>()
        coEvery { pipelineService.graphAsJsonElement(pipeline) } returns JsonObject(emptyMap())
        val runId = UUID.random()
        coEvery { runRepo.add(any()) } answers { firstArg<PipelineRun>().copy(id = runId) }
        // The first drive suspends; persisting the suspended checkpoint succeeds.
        coEvery { executor.execute(any(), any(), any(), any()) } returns
            ExecutionResult.Suspended(ExecutionState(emptyMap()), emptyList())
        coEvery { runRepo.updateState(any(), PipelineRunStatus.SUSPENDED, any(), any(), null, any()) } answers
            { suspendedRun(runId) }
        // Still SUSPENDED on every poll; block window = 0 so the loop body never runs.
        coEvery { runRepo.getById(runId) } returns suspendedRun(runId)
        val cfg = mockk<PipelinesRuntimeConfiguration>(relaxed = true)
        coEvery { cfg.onDemandRunMaxBlockMillis } returns 0

        val run = service(runRepo = runRepo, pipelineService = pipelineService, executor = executor, config = cfg)
            .start(pipeline, sampleInput, "manual", OffsetDateTime.now(), AuthenticationContext(null, null))

        assertEquals(PipelineRunStatus.SUSPENDED, run!!.status)
        assertNull(run.output)
        assertNull(run.error)
        assertTrue(run.status == PipelineRunStatus.SUSPENDED)
        assertTrue(run.status != PipelineRunStatus.FAILED && run.status != PipelineRunStatus.CANCELLED)
    }

    @Test
    fun `runOnDemand returns FAILED with the error for a terminally failed run`() = runTest {
        val runRepo = mockk<PipelineRunRepository>(relaxed = true)
        val logRepo = mockk<PipelineRunLogRepository>(relaxed = true)
        val pipelineService = stubbedPipelineService()
        val executor = mockk<PipelineExecutor>()
        coEvery { pipelineService.graphAsJsonElement(pipeline) } returns JsonObject(emptyMap())
        val runId = UUID.random()
        coEvery { runRepo.add(any()) } answers { firstArg<PipelineRun>().copy(id = runId) }
        coEvery { executor.execute(any(), any(), any(), any()) } throws RuntimeException("kaboom")
        coEvery { runRepo.complete(runId, PipelineRunStatus.FAILED, any(), "kaboom") } returns null
        coEvery { runRepo.getById(runId) } returns suspendedRun(runId).copy(status = PipelineRunStatus.FAILED, error = "kaboom")

        val run = service(runRepo = runRepo, logRepo = logRepo, pipelineService = pipelineService, executor = executor)
            .start(pipeline, sampleInput, "manual", OffsetDateTime.now(), AuthenticationContext(null, null))

        assertEquals(PipelineRunStatus.FAILED, run!!.status)
        assertNull(run.output)
        assertEquals("kaboom", run.error)
        assertFalse(run.status != PipelineRunStatus.FAILED && run.status != PipelineRunStatus.CANCELLED)
    }

    @Test
    fun `runOnDemand reports run not found when the row vanished`() = runTest {
        val runRepo = mockk<PipelineRunRepository>(relaxed = true)
        val logRepo = mockk<PipelineRunLogRepository>(relaxed = true)
        val pipelineService = stubbedPipelineService()
        val executor = mockk<PipelineExecutor>()
        coEvery { pipelineService.graphAsJsonElement(pipeline) } returns JsonObject(emptyMap())
        val runId = UUID.random()
        coEvery { runRepo.add(any()) } answers { firstArg<PipelineRun>().copy(id = runId) }
        coEvery { executor.execute(any(), any(), any(), any()) } returns ExecutionResult.Completed(null)
        coEvery { runRepo.complete(any(), any(), any(), any()) } returns null
        // getById returns null → current == null → the loop is skipped, status falls back to FAILED.
        coEvery { runRepo.getById(runId) } returns null

        val run = service(runRepo = runRepo, logRepo = logRepo, pipelineService = pipelineService, executor = executor)
            .start(pipeline, sampleInput, "manual", OffsetDateTime.now(), AuthenticationContext(null, null))

        // getById returns null → the run handle the caller gets back is null (run not found).
        assertNull(run)
    }

    @Test
    fun `runOnDemand enqueues a run job to drive the run and never drives inline`() = runTest {
        val runRepo = mockk<PipelineRunRepository>(relaxed = true)
        val pipelineService = stubbedPipelineService()
        // Strict executor: if the on-demand path drove inline, execute() would be called and fail the test.
        val executor = mockk<PipelineExecutor>()
        coEvery { pipelineService.graphAsJsonElement(pipeline) } returns JsonObject(emptyMap())
        val runId = UUID.random()
        coEvery { runRepo.add(any()) } answers { firstArg<PipelineRun>().copy(id = runId) }
        coEvery { runRepo.getById(runId) } returns suspendedRun(runId)

        service(runRepo = runRepo, pipelineService = pipelineService, executor = executor)
            .start(pipeline, sampleInput, "manual", OffsetDateTime.now(), AuthenticationContext(null, null))

        // The run is driven by an enqueued run job (so a node that suspends has a job to attach its
        // backing work to and resume from) — never inline, which would orphan a suspending on-demand run.
        coVerify(exactly = 0) { executor.execute(any(), any(), any(), any()) }
        assertEquals(
            listOf(runId),
            manualRunEnqueuer.configs.map { json.decodeFromJsonElement(PipelineManualRunJob.serializer(), it).runId },
        )
    }

    @Test
    fun `runOnDemand captures the caller principal on the run so security traverses the whole run`() = runTest {
        val runRepo = mockk<PipelineRunRepository>(relaxed = true)
        val pipelineService = stubbedPipelineService()
        coEvery { pipelineService.graphAsJsonElement(pipeline) } returns JsonObject(emptyMap())
        val runId = UUID.random()
        val added = slot<PipelineRun>()
        coEvery { runRepo.add(capture(added)) } answers { added.captured.copy(id = runId) }
        coEvery { runRepo.getById(runId) } returns suspendedRun(runId)
        val principalId = UUID.random()
        val auth = mockk<AuthenticationContext> { every { principal() } returns mockk { every { id } returns principalId } }

        service(runRepo = runRepo, pipelineService = pipelineService)
            .start(pipeline, sampleInput, "manual", OffsetDateTime.now(), auth)

        // The caller's principal is persisted on the run so the run job, its backing work, and every
        // resume re-impersonate it — the caller's security context traverses the whole durable run,
        // rather than the run dropping to the service account once it leaves the request thread.
        assertEquals(principalId, added.captured.principalId)
    }

    // ----------------------------------------------------------------------------------------------
    // execute (the public fresh-run entry point) — completion vs suspension persistence
    // ----------------------------------------------------------------------------------------------

    @Test
    fun `execute finishes a completed run with OK and appends history`() = runTest {
        val runRepo = mockk<PipelineRunRepository>(relaxed = true)
        val logRepo = mockk<PipelineRunLogRepository>(relaxed = true)
        val executor = mockk<PipelineExecutor>()
        val runId = UUID.random()
        val run = suspendedRun(runId).copy(status = PipelineRunStatus.RUNNING)
        coEvery { runRepo.add(any()) } returns run
        coEvery { executor.execute(pipeline, any(), any(), null) } returns
            ExecutionResult.Completed(PipelineValue.of(SampleEvent("o"), SampleEvent.serializer()))
        coEvery { runRepo.complete(runId, PipelineRunStatus.OK, any(), null) } returns null
        // No parent linkage → reportIterationResult returns early.
        val logSlot = slot<PipelineRunLog>()
        coEvery { logRepo.add(capture(logSlot)) } answers { logSlot.captured }

        service(runRepo = runRepo, logRepo = logRepo, executor = executor)
            .start(pipeline, sampleInput, "e", OffsetDateTime.now())

        coVerify(exactly = 1) { runRepo.complete(runId, PipelineRunStatus.OK, any(), null) }
        assertEquals(PipelineRunStatus.OK, logSlot.captured.outcome)
        assertEquals(runId, logSlot.captured.runId)
    }

    @Test
    fun `execute persists a suspended checkpoint and enqueues newly parked work`() = runTest {
        val runRepo = mockk<PipelineRunRepository>(relaxed = true)
        val executor = mockk<PipelineExecutor>()
        val runId = UUID.random()
        val run = suspendedRun(runId).copy(status = PipelineRunStatus.RUNNING, version = 7)
        coEvery { runRepo.add(any()) } returns run
        var enqueued = false
        val parked = Parked(nodeId = "wait", enqueue = { enqueued = true })
        coEvery { executor.execute(any(), any(), any(), null) } returns
            ExecutionResult.Suspended(ExecutionState(mapOf("n0" to null)), listOf(parked))
        coEvery { runRepo.updateState(runId, PipelineRunStatus.SUSPENDED, any(), any(), null, 7) } returns run

        service(runRepo = runRepo, executor = executor).start(pipeline, sampleInput, "e", OffsetDateTime.now())

        coVerify(exactly = 1) { runRepo.updateState(runId, PipelineRunStatus.SUSPENDED, any(), any(), null, 7) }
        assertTrue(enqueued, "the newly-parked node's backing work is enqueued after the checkpoint is persisted")
    }

    @Test
    fun `suspending announces only newly parked approval gates with name and empty fallbacks`() = runTest {
        val runRepo = mockk<PipelineRunRepository>(relaxed = true)
        val executor = mockk<PipelineExecutor>()
        val pipelineService = stubbedPipelineService()
        val pubSub = mockk<PubSubService>(relaxed = true)
        val runId = UUID.random()
        val snapshot = buildJsonObject {
            put("nodes", JsonArray(listOf(
                JsonPrimitive("not-an-object"),
                buildJsonObject { put("type", "gate.approval") },
                buildJsonObject { put("id", JsonNull); put("type", "gate.approval") },
                buildJsonObject { put("id", buildJsonObject { }); put("type", "gate.approval") },
                buildJsonObject { put("id", "not-parked"); put("type", "gate.approval") },
                buildJsonObject { put("id", "wrong-type-null"); put("type", JsonNull) },
                buildJsonObject { put("id", "wrong-type"); put("type", "waitForInput") },
                buildJsonObject { put("id", "prompted"); put("type", "gate.approval"); put("prompt", "Approve release?") },
                buildJsonObject { put("id", "named"); put("type", "gate.approval"); put("name", "Named approval") },
                buildJsonObject { put("id", "blank"); put("type", "gate.approval") },
            )))
        }
        coEvery { pipelineService.graphAsJsonElement(pipeline) } returns snapshot
        coEvery { runRepo.add(any()) } answers { firstArg<PipelineRun>().copy(id = runId) }
        var enqueued = 0
        coEvery { executor.execute(any(), any(), any(), null) } returns ExecutionResult.Suspended(
            ExecutionState(emptyMap()),
            listOf(
                Parked("named") { enqueued++ },
                Parked("blank") { enqueued++ },
                Parked("wrong-type") { enqueued++ },
                Parked("prompted") { enqueued++ },
            ),
        )
        coEvery { runRepo.updateState(any(), PipelineRunStatus.SUSPENDED, any(), any(), null, any()) } answers {
            firstArg<UUID>().let { suspendedRun(it) }
        }
        coEvery {
            pubSub.publish(
                bosca.pipelines.model.PipelineAwaitingApproval.CHANNEL,
                bosca.pipelines.model.PipelineAwaitingApproval.serializer(),
                match<bosca.pipelines.model.PipelineAwaitingApproval> { it.nodeId == "named" },
            )
        } throws RuntimeException("notifications unavailable")

        service(runRepo = runRepo, pipelineService = pipelineService, executor = executor, pubSub = pubSub)
            .start(pipeline, sampleInput, "e", OffsetDateTime.now())

        assertEquals(4, enqueued)
        coVerify(exactly = 1) {
            pubSub.publish(
                bosca.pipelines.model.PipelineAwaitingApproval.CHANNEL,
                bosca.pipelines.model.PipelineAwaitingApproval.serializer(),
                match<bosca.pipelines.model.PipelineAwaitingApproval> {
                    it.nodeId == "prompted" && it.prompt == "Approve release?"
                },
            )
        }
        coVerify(exactly = 1) {
            pubSub.publish(
                bosca.pipelines.model.PipelineAwaitingApproval.CHANNEL,
                bosca.pipelines.model.PipelineAwaitingApproval.serializer(),
                match<bosca.pipelines.model.PipelineAwaitingApproval> {
                    it.nodeId == "named" && it.prompt == "Named approval"
                },
            )
        }
        coVerify(exactly = 1) {
            pubSub.publish(
                bosca.pipelines.model.PipelineAwaitingApproval.CHANNEL,
                bosca.pipelines.model.PipelineAwaitingApproval.serializer(),
                match<bosca.pipelines.model.PipelineAwaitingApproval> {
                    it.nodeId == "blank" && it.prompt.isEmpty()
                },
            )
        }
    }

    @Test
    fun `suspending skips approval announcements for non-object snapshots and non-array nodes`() = runTest {
        val runRepo = mockk<PipelineRunRepository>(relaxed = true)
        val executor = mockk<PipelineExecutor>()
        val pipelineService = stubbedPipelineService()
        val pubSub = mockk<PubSubService>(relaxed = true)
        coEvery { pipelineService.graphAsJsonElement(pipeline) } returnsMany listOf(
            JsonPrimitive("not-an-object"),
            buildJsonObject { put("nodes", "not-an-array") },
        )
        coEvery { runRepo.add(any()) } answers { firstArg<PipelineRun>().copy(id = UUID.random()) }
        var enqueued = 0
        coEvery { executor.execute(any(), any(), any(), null) } returns ExecutionResult.Suspended(
            ExecutionState(emptyMap()),
            listOf(Parked("gate") { enqueued++ }),
        )
        coEvery { runRepo.updateState(any(), PipelineRunStatus.SUSPENDED, any(), any(), null, any()) } answers {
            suspendedRun(firstArg())
        }
        val sut = service(runRepo = runRepo, pipelineService = pipelineService, executor = executor, pubSub = pubSub)

        sut.start(pipeline, sampleInput, "one", OffsetDateTime.now())
        sut.start(pipeline, sampleInput, "two", OffsetDateTime.now())

        assertEquals(2, enqueued)
        coVerify(exactly = 0) {
            pubSub.publish(
                bosca.pipelines.model.PipelineAwaitingApproval.CHANNEL,
                bosca.pipelines.model.PipelineAwaitingApproval.serializer(),
                any<bosca.pipelines.model.PipelineAwaitingApproval>(),
            )
        }
    }

    @Test
    fun `execute throws on an optimistic-lock conflict while suspending`() = runTest {
        val runRepo = mockk<PipelineRunRepository>(relaxed = true)
        val logRepo = mockk<PipelineRunLogRepository>()
        val executor = mockk<PipelineExecutor>()
        val runId = UUID.random()
        val run = suspendedRun(runId).copy(status = PipelineRunStatus.RUNNING)
        coEvery { runRepo.add(any()) } returns run
        var enqueued = false
        coEvery { executor.execute(any(), any(), any(), null) } returns
            ExecutionResult.Suspended(ExecutionState(emptyMap()), listOf(Parked("n", { enqueued = true })))
        // updateState loses the race → the suspend path errors and the work is NOT enqueued.
        coEvery { runRepo.updateState(any(), PipelineRunStatus.SUSPENDED, any(), any(), null, any()) } returns null
        coEvery { runRepo.complete(runId, PipelineRunStatus.FAILED, any(), any()) } returns null
        // drive()'s catch arm calls finish(), which appends the FAILED terminal row to run history.
        coEvery { logRepo.add(any()) } answers { firstArg() }

        // drive() catches the error and finishes the run FAILED rather than propagating.
        service(runRepo = runRepo, logRepo = logRepo, executor = executor).start(pipeline, sampleInput, "e", OffsetDateTime.now())

        assertFalse(enqueued)
        coVerify(exactly = 1) { runRepo.complete(runId, PipelineRunStatus.FAILED, any(), any()) }
        // The optimistic-lock conflict message surfaces as the recorded run-history error.
        coVerify(exactly = 1) { logRepo.add(match { it.outcome == PipelineRunStatus.FAILED }) }
    }

    @Test
    fun `execute marks the run FAILED when the executor throws`() = runTest {
        val runRepo = mockk<PipelineRunRepository>(relaxed = true)
        val logRepo = mockk<PipelineRunLogRepository>(relaxed = true)
        val executor = mockk<PipelineExecutor>()
        val runId = UUID.random()
        val run = suspendedRun(runId).copy(status = PipelineRunStatus.RUNNING)
        coEvery { runRepo.add(any()) } returns run
        coEvery { executor.execute(any(), any(), any(), null) } throws IllegalArgumentException("bad node")
        coEvery { runRepo.complete(runId, PipelineRunStatus.FAILED, any(), "bad node") } returns null

        service(runRepo = runRepo, logRepo = logRepo, executor = executor)
            .start(pipeline, sampleInput, "e", OffsetDateTime.now())

        coVerify(exactly = 1) { runRepo.complete(runId, PipelineRunStatus.FAILED, any(), "bad node") }
    }

    @Test
    fun `execute uses the exception toString when the message is null`() = runTest {
        val runRepo = mockk<PipelineRunRepository>(relaxed = true)
        val logRepo = mockk<PipelineRunLogRepository>(relaxed = true)
        val executor = mockk<PipelineExecutor>()
        val runId = UUID.random()
        val run = suspendedRun(runId).copy(status = PipelineRunStatus.RUNNING)
        coEvery { runRepo.add(any()) } returns run
        // An exception with a null message → the elvis falls through to e.toString().
        coEvery { executor.execute(any(), any(), any(), null) } throws RuntimeException()
        val errorSlot = slot<String?>()
        coEvery { runRepo.complete(runId, PipelineRunStatus.FAILED, any(), captureNullable(errorSlot)) } returns null

        service(runRepo = runRepo, logRepo = logRepo, executor = executor)
            .start(pipeline, sampleInput, "e", OffsetDateTime.now())

        assertNotNull(errorSlot.captured)
        assertTrue(errorSlot.captured!!.contains("RuntimeException"))
    }

    // ----------------------------------------------------------------------------------------------
    // resume — re-drive remaining awaits & terminal-after-resume reporting
    // ----------------------------------------------------------------------------------------------

    @Test
    fun `resume re-drives but stays parked on the other outstanding await`() = runTest {
        val runRepo = mockk<PipelineRunRepository>(relaxed = true)
        val store = mockk<PipelineRunResultStore>(relaxed = true)
        val pipelineService = stubbedPipelineService()
        val executor = mockk<PipelineExecutor>()
        val runId = UUID.random()
        // Two awaits outstanding; resolving "n1" leaves "n2" still parked.
        val suspended = suspendedRun(
            runId,
            awaiting = listOf(PipelineRunAwait("n1"), PipelineRunAwait("n2")),
        )
        coEvery { runRepo.getById(runId) } returns suspended
        coEvery { pipelineService.decodeGraph(any()) } returns PipelineGraph(nodes = listOf(InputNode(id = "n1", acceptedType = "X")))
        coEvery { pipelineService.descriptorFor(any()) } returns null
        coEvery { store.get(runId, "n1") } returns PipelineRunNodeResult(JsonPrimitive("v"))
        val running = suspended.copy(status = PipelineRunStatus.RUNNING, version = suspended.version + 1)
        val awaitsSlot = slot<JsonElement>()
        coEvery {
            runRepo.updateState(runId, PipelineRunStatus.RUNNING, any(), capture(awaitsSlot), null, suspended.version)
        } returns running
        // The re-drive suspends again on the remaining await; persisting that checkpoint succeeds.
        coEvery { executor.execute(any(), any(), any(), any()) } returns
            ExecutionResult.Suspended(ExecutionState(emptyMap(), setOf("n2")), emptyList())
        coEvery { runRepo.updateState(runId, PipelineRunStatus.SUSPENDED, any(), any(), null, running.version) } returns running

        service(runRepo = runRepo, resultStore = store, pipelineService = pipelineService, executor = executor)
            .resume(runId, "n1", succeeded = true, error = null)

        // The transition-back-to-RUNNING dropped n1 from the awaits but kept n2.
        val remaining = json.decodeFromJsonElement(ListSerializer(PipelineRunAwait.serializer()), awaitsSlot.captured)
        assertEquals(listOf(PipelineRunAwait("n2")), remaining)
        coVerify(exactly = 1) { runRepo.updateState(runId, PipelineRunStatus.SUSPENDED, any(), any(), null, running.version) }
    }

    @Test
    fun `resume failure with no error port reports the failure to a live parent`() = runTest {
        val runRepo = mockk<PipelineRunRepository>(relaxed = true)
        val store = mockk<PipelineRunResultStore>(relaxed = true)
        val logRepo = mockk<PipelineRunLogRepository>(relaxed = true)
        val pipelineService = stubbedPipelineService()
        val rollbackRepo = mockk<RollbackRepository>(relaxed = true)
        coEvery { pipelineService.decodeGraph(any()) } returns PipelineGraph()
        val runId = UUID.random()
        val parentId = UUID.random()
        // This run is a single child (itemIndex null) of a live parent parked on its node.
        val child = suspendedRun(runId, awaiting = listOf(PipelineRunAwait("n1"))).copy(
            parentRunId = parentId, parentNodeId = "callNode", itemIndex = null,
        )
        coEvery { runRepo.getById(runId) } returns child
        coEvery { runRepo.complete(runId, PipelineRunStatus.FAILED, any(), any()) } returns null
        // No rollback records → runRollbacks returns early.
        coEvery { rollbackRepo.listForRunReversed(runId) } returns emptyList()
        // The parent is live and parked on callNode → reportIterationResult resumes it as a failure.
        val parent = suspendedRun(parentId, awaiting = listOf(PipelineRunAwait("callNode")))
        coEvery { runRepo.getById(parentId) } returns parent
        // The parent's resume reads the parent's graph; give it an empty graph so the parent fails too.
        coEvery { runRepo.complete(parentId, PipelineRunStatus.FAILED, any(), any()) } returns null

        service(
            runRepo = runRepo, logRepo = logRepo, resultStore = store,
            pipelineService = pipelineService, rollbackRepo = rollbackRepo,
        ).resume(runId, "n1", succeeded = false, error = "child boom")

        // The child failed AND propagated a sub-pipeline failure into the parent.
        coVerify(exactly = 1) { runRepo.complete(runId, PipelineRunStatus.FAILED, any(), any()) }
        coVerify(exactly = 1) { runRepo.complete(parentId, PipelineRunStatus.FAILED, any(), match { it!!.contains("sub-pipeline failed") }) }
    }

    // ----------------------------------------------------------------------------------------------
    // resume / cancel — non-existent run guard
    // ----------------------------------------------------------------------------------------------

    @Test
    fun `resume is a no-op when the run does not exist`() = runTest {
        val runRepo = mockk<PipelineRunRepository>()
        val runId = UUID.random()
        coEvery { runRepo.getById(runId) } returns null

        service(runRepo = runRepo).resume(runId, "n1", succeeded = true, error = null)

        coVerify(exactly = 0) { runRepo.updateState(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `cancel is a no-op when the run does not exist`() = runTest {
        val runRepo = mockk<PipelineRunRepository>()
        val runId = UUID.random()
        coEvery { runRepo.getById(runId) } returns null

        service(runRepo = runRepo).cancel(runId, "reason")

        coVerify(exactly = 0) { runRepo.complete(any(), any(), any(), any()) }
    }

    @Test
    fun `cancel reports the cancellation to a live parent and runs rollbacks`() = runTest {
        val runRepo = mockk<PipelineRunRepository>(relaxed = true)
        val logRepo = mockk<PipelineRunLogRepository>(relaxed = true)
        val rollbackRepo = mockk<RollbackRepository>(relaxed = true)
        val pipelineService = stubbedPipelineService()
        val executor = mockk<PipelineExecutor>()
        val runId = UUID.random()
        val child = suspendedRun(runId).copy(parentRunId = null, parentNodeId = null)
        coEvery { runRepo.getById(runId) } returns child
        coEvery { runRepo.complete(runId, PipelineRunStatus.CANCELLED, any(), "stop") } returns null
        // One rollback record present → its rollback pipeline runs to completion.
        val rollbackPipelineId = UUID.random()
        coEvery { rollbackRepo.listForRunReversed(runId) } returns listOf(
            bosca.pipelines.model.RollbackRecord(runId, "n1", rollbackPipelineId, JsonPrimitive("undo-me")),
        )
        val rollbackPipeline = pipeline.copy(id = rollbackPipelineId, name = "rb")
        coEvery { pipelineService.get(rollbackPipelineId) } returns rollbackPipeline
        coEvery { executor.execute(rollbackPipeline, any(), any(), null) } returns ExecutionResult.Completed(null)

        service(runRepo = runRepo, logRepo = logRepo, rollbackRepo = rollbackRepo, pipelineService = pipelineService, executor = executor)
            .cancel(runId, "stop")

        coVerify(exactly = 1) { runRepo.complete(runId, PipelineRunStatus.CANCELLED, any(), "stop") }
        // The rollback pipeline was replayed with the node's output.
        coVerify(exactly = 1) { executor.execute(rollbackPipeline, any(), any(), null) }
    }

    // ----------------------------------------------------------------------------------------------
    // runRollbacks (via finish, exercised through cancel) — reverse order, missing pipeline, failure isolation
    // ----------------------------------------------------------------------------------------------

    @Test
    fun `rollbacks replay every record in repository (reverse) order`() = runTest {
        val runRepo = mockk<PipelineRunRepository>(relaxed = true)
        val logRepo = mockk<PipelineRunLogRepository>(relaxed = true)
        val rollbackRepo = mockk<RollbackRepository>(relaxed = true)
        val pipelineService = stubbedPipelineService()
        val executor = mockk<PipelineExecutor>()
        val runId = UUID.random()
        coEvery { runRepo.getById(runId) } returns suspendedRun(runId)
        coEvery { runRepo.complete(runId, PipelineRunStatus.CANCELLED, any(), any()) } returns null
        val p1 = UUID.random()
        val p2 = UUID.random()
        // listForRunReversed already yields reverse-completion order; the loop replays in that order.
        coEvery { rollbackRepo.listForRunReversed(runId) } returns listOf(
            bosca.pipelines.model.RollbackRecord(runId, "second", p2, JsonPrimitive("b")),
            bosca.pipelines.model.RollbackRecord(runId, "first", p1, JsonPrimitive("a")),
        )
        val rb1 = pipeline.copy(id = p1)
        val rb2 = pipeline.copy(id = p2)
        coEvery { pipelineService.get(p2) } returns rb2
        coEvery { pipelineService.get(p1) } returns rb1
        val replayedOrder = mutableListOf<UUID>()
        coEvery { executor.execute(any(), any(), any(), null) } answers {
            replayedOrder.add(firstArg<Pipeline>().id)
            ExecutionResult.Completed(null)
        }

        service(runRepo = runRepo, logRepo = logRepo, rollbackRepo = rollbackRepo, pipelineService = pipelineService, executor = executor)
            .cancel(runId, "x")

        assertEquals(listOf(p2, p1), replayedOrder)
    }

    @Test
    fun `rollbacks skip a missing rollback pipeline and continue`() = runTest {
        val runRepo = mockk<PipelineRunRepository>(relaxed = true)
        val logRepo = mockk<PipelineRunLogRepository>(relaxed = true)
        val rollbackRepo = mockk<RollbackRepository>(relaxed = true)
        val pipelineService = stubbedPipelineService()
        val executor = mockk<PipelineExecutor>()
        val runId = UUID.random()
        coEvery { runRepo.getById(runId) } returns suspendedRun(runId)
        coEvery { runRepo.complete(runId, PipelineRunStatus.CANCELLED, any(), any()) } returns null
        val missing = UUID.random()
        val present = UUID.random()
        coEvery { rollbackRepo.listForRunReversed(runId) } returns listOf(
            bosca.pipelines.model.RollbackRecord(runId, "gone", missing, null),
            bosca.pipelines.model.RollbackRecord(runId, "here", present, JsonPrimitive("v")),
        )
        coEvery { pipelineService.get(missing) } returns null // not found → continue
        val presentPipeline = pipeline.copy(id = present)
        coEvery { pipelineService.get(present) } returns presentPipeline
        coEvery { executor.execute(presentPipeline, any(), any(), null) } returns ExecutionResult.Completed(null)

        service(runRepo = runRepo, logRepo = logRepo, rollbackRepo = rollbackRepo, pipelineService = pipelineService, executor = executor)
            .cancel(runId, "x")

        // The missing one was skipped; the present one still ran (its null output feeds JsonNull).
        coVerify(exactly = 1) { executor.execute(presentPipeline, any(), any(), null) }
    }

    @Test
    fun `rollbacks isolate a failing rollback so the others still run`() = runTest {
        val runRepo = mockk<PipelineRunRepository>(relaxed = true)
        val logRepo = mockk<PipelineRunLogRepository>(relaxed = true)
        val rollbackRepo = mockk<RollbackRepository>(relaxed = true)
        val pipelineService = stubbedPipelineService()
        val executor = mockk<PipelineExecutor>()
        val runId = UUID.random()
        coEvery { runRepo.getById(runId) } returns suspendedRun(runId)
        coEvery { runRepo.complete(runId, PipelineRunStatus.CANCELLED, any(), any()) } returns null
        val bad = UUID.random()
        val good = UUID.random()
        coEvery { rollbackRepo.listForRunReversed(runId) } returns listOf(
            bosca.pipelines.model.RollbackRecord(runId, "bad", bad, null),
            bosca.pipelines.model.RollbackRecord(runId, "good", good, null),
        )
        val badPipeline = pipeline.copy(id = bad)
        val goodPipeline = pipeline.copy(id = good)
        coEvery { pipelineService.get(bad) } returns badPipeline
        coEvery { pipelineService.get(good) } returns goodPipeline
        // The first rollback throws; it must be logged and swallowed so the second still runs.
        coEvery { executor.execute(badPipeline, any(), any(), null) } throws RuntimeException("rollback failed")
        coEvery { executor.execute(goodPipeline, any(), any(), null) } returns ExecutionResult.Completed(null)

        service(runRepo = runRepo, logRepo = logRepo, rollbackRepo = rollbackRepo, pipelineService = pipelineService, executor = executor)
            .cancel(runId, "x")

        coVerify(exactly = 1) { executor.execute(goodPipeline, any(), any(), null) }
    }

    @Test
    fun `a successful terminal run runs no rollbacks`() = runTest {
        val runRepo = mockk<PipelineRunRepository>(relaxed = true)
        val logRepo = mockk<PipelineRunLogRepository>(relaxed = true)
        val rollbackRepo = mockk<RollbackRepository>(relaxed = true)
        val executor = mockk<PipelineExecutor>()
        val runId = UUID.random()
        val run = suspendedRun(runId).copy(status = PipelineRunStatus.RUNNING)
        coEvery { runRepo.add(any()) } returns run
        coEvery { executor.execute(any(), any(), any(), null) } returns ExecutionResult.Completed(null)
        coEvery { runRepo.complete(runId, PipelineRunStatus.OK, any(), null) } returns null

        service(runRepo = runRepo, logRepo = logRepo, rollbackRepo = rollbackRepo, executor = executor)
            .start(pipeline, sampleInput, "e", OffsetDateTime.now())

        // status == OK → the non-OK branch (reportIterationResult + runRollbacks) is skipped.
        coVerify(exactly = 0) { rollbackRepo.listForRunReversed(any()) }
    }

    // ----------------------------------------------------------------------------------------------
    // reportIterationResult (ForEach) — via a child completion drive
    // ----------------------------------------------------------------------------------------------

    @Test
    fun `foreach child completion that finishes the iteration signals the joined array`() = runTest {
        val runRepo = mockk<PipelineRunRepository>(relaxed = true)
        val logRepo = mockk<PipelineRunLogRepository>(relaxed = true)
        val iterRepo = mockk<PipelineRunIterationRepository>(relaxed = true)
        val store = mockk<PipelineRunResultStore>(relaxed = true)
        val pipelineService = stubbedPipelineService()
        val executor = mockk<PipelineExecutor>()
        val childId = UUID.random()
        val parentId = UUID.random()
        // The child is item index 0 of a 1-item ForEach on the parent.
        val child = suspendedRun(childId).copy(
            status = PipelineRunStatus.RUNNING, parentRunId = parentId, parentNodeId = "fe", itemIndex = 0,
        )
        coEvery { runRepo.add(any()) } returns child
        coEvery { executor.execute(pipeline, any(), any(), null) } returns
            ExecutionResult.Completed(PipelineValue.of(SampleEvent("r0"), SampleEvent.serializer()))
        coEvery { runRepo.complete(childId, PipelineRunStatus.OK, any(), null) } returns null
        // Parent is live and parked on "fe".
        val parent = suspendedRun(parentId, awaiting = listOf(PipelineRunAwait("fe")))
        coEvery { runRepo.getById(parentId) } returns parent
        // recordResult returns a complete iteration (1 of 1) → the join+signal fires.
        coEvery { iterRepo.recordResult(parentId, "fe", "0", any()) } returns PipelineRunIteration(
            parentRunId = parentId, nodeId = "fe", total = 1, continueOnError = false,
            results = buildJsonObject { put("0", JsonPrimitive("r0")) },
        )
        // The signal path resumes the parent — give it a routable graph + staged result so it advances.
        coEvery { pipelineService.decodeGraph(any()) } returns PipelineGraph(nodes = listOf(InputNode(id = "fe", acceptedType = "X")))
        coEvery { pipelineService.descriptorFor(any()) } returns null
        val staged = slot<JsonElement>()
        coEvery { store.put(parentId, "fe", capture(staged), any()) } returns Unit
        coEvery { store.get(parentId, "fe") } answers { PipelineRunNodeResult(staged.captured) }
        coEvery { runRepo.updateState(parentId, PipelineRunStatus.RUNNING, any(), any(), null, parent.version) } returns
            parent.copy(status = PipelineRunStatus.RUNNING, version = parent.version + 1)
        coEvery { executor.execute(any(), any(), any(), any()) } returns ExecutionResult.Completed(null)
        coEvery { runRepo.complete(parentId, PipelineRunStatus.OK, any(), null) } returns parent

        service(runRepo = runRepo, logRepo = logRepo, iterationRepo = iterRepo, resultStore = store, pipelineService = pipelineService, executor = executor)
            .start(pipeline, sampleInput, "e", OffsetDateTime.now())

        // The completed iteration signalled the parent with the joined results array.
        assertTrue(staged.captured is JsonArray)
        coVerify(exactly = 1) { store.put(parentId, "fe", any(), any()) }
    }

    @Test
    fun `foreach child completion that does not finish the iteration does not signal`() = runTest {
        val runRepo = mockk<PipelineRunRepository>(relaxed = true)
        val logRepo = mockk<PipelineRunLogRepository>(relaxed = true)
        val iterRepo = mockk<PipelineRunIterationRepository>(relaxed = true)
        val store = mockk<PipelineRunResultStore>(relaxed = true)
        val executor = mockk<PipelineExecutor>()
        val childId = UUID.random()
        val parentId = UUID.random()
        val child = suspendedRun(childId).copy(
            status = PipelineRunStatus.RUNNING, parentRunId = parentId, parentNodeId = "fe", itemIndex = 0,
        )
        coEvery { runRepo.add(any()) } returns child
        coEvery { executor.execute(pipeline, any(), any(), null) } returns ExecutionResult.Completed(null)
        coEvery { runRepo.complete(childId, PipelineRunStatus.OK, any(), null) } returns null
        coEvery { runRepo.getById(parentId) } returns suspendedRun(parentId, awaiting = listOf(PipelineRunAwait("fe")))
        // Only 1 of 2 items reported → results.size < total → no signal.
        coEvery { iterRepo.recordResult(parentId, "fe", "0", any()) } returns PipelineRunIteration(
            parentRunId = parentId, nodeId = "fe", total = 2, continueOnError = false,
            results = buildJsonObject { put("0", JsonNull) },
        )

        service(runRepo = runRepo, logRepo = logRepo, iterationRepo = iterRepo, resultStore = store, executor = executor)
            .start(pipeline, sampleInput, "e", OffsetDateTime.now())

        coVerify(exactly = 0) { store.put(parentId, "fe", any(), any()) }
    }

    @Test
    fun `child completion is a no-op when its parent row has disappeared`() = runTest {
        val runRepo = mockk<PipelineRunRepository>(relaxed = true)
        val iterRepo = mockk<PipelineRunIterationRepository>(relaxed = true)
        val executor = mockk<PipelineExecutor>()
        val childId = UUID.random()
        val parentId = UUID.random()
        val child = suspendedRun(childId).copy(
            status = PipelineRunStatus.RUNNING,
            parentRunId = parentId,
            parentNodeId = "fe",
            itemIndex = 0,
        )
        coEvery { runRepo.add(any()) } returns child
        coEvery { runRepo.getById(parentId) } returns null
        coEvery { executor.execute(any(), any(), any(), any()) } returns ExecutionResult.Completed(null)

        service(runRepo = runRepo, iterationRepo = iterRepo, executor = executor)
            .start(pipeline, sampleInput, "e", OffsetDateTime.now())

        coVerify(exactly = 0) { iterRepo.recordResult(any(), any(), any(), any()) }
    }

    @Test
    fun `bounded completion tolerates a malformed stored item collection`() = runTest {
        val runRepo = mockk<PipelineRunRepository>(relaxed = true)
        val iterRepo = mockk<PipelineRunIterationRepository>(relaxed = true)
        val store = mockk<PipelineRunResultStore>(relaxed = true)
        val executor = mockk<PipelineExecutor>()
        val childId = UUID.random()
        val parentId = UUID.random()
        val child = suspendedRun(childId).copy(
            status = PipelineRunStatus.RUNNING,
            parentRunId = parentId,
            parentNodeId = "fe",
            itemIndex = 0,
        )
        coEvery { runRepo.add(any()) } returns child
        coEvery { runRepo.getById(parentId) } returns suspendedRun(parentId, awaiting = listOf(PipelineRunAwait("fe")))
        coEvery { executor.execute(any(), any(), any(), any()) } returns ExecutionResult.Completed(null)
        coEvery { iterRepo.recordResult(parentId, "fe", "0", any()) } returns PipelineRunIteration(
            parentRunId = parentId,
            nodeId = "fe",
            total = 2,
            continueOnError = false,
            results = JsonObject(emptyMap()),
            maxConcurrency = 1,
            items = JsonNull,
        )

        service(runRepo = runRepo, iterationRepo = iterRepo, resultStore = store, executor = executor)
            .start(pipeline, sampleInput, "e", OffsetDateTime.now())

        coVerify(exactly = 1) { runRepo.add(any()) }
        coVerify(exactly = 0) { store.put(any(), any(), any(), any()) }
    }

    @Test
    fun `bounded completion stops at both the declared total and stored item size`() = runTest {
        suspend fun exercise(total: Int, items: JsonArray) {
            val runRepo = mockk<PipelineRunRepository>(relaxed = true)
            val iterRepo = mockk<PipelineRunIterationRepository>(relaxed = true)
            val executor = mockk<PipelineExecutor>()
            val childId = UUID.random()
            val parentId = UUID.random()
            val child = suspendedRun(childId).copy(
                status = PipelineRunStatus.RUNNING,
                parentRunId = parentId,
                parentNodeId = "fe",
                itemIndex = 0,
            )
            coEvery { runRepo.add(any()) } returns child
            coEvery { runRepo.getById(parentId) } returns suspendedRun(parentId, awaiting = listOf(PipelineRunAwait("fe")))
            coEvery { executor.execute(any(), any(), any(), any()) } returns ExecutionResult.Completed(null)
            coEvery { iterRepo.recordResult(parentId, "fe", "0", any()) } returns PipelineRunIteration(
                parentRunId = parentId,
                nodeId = "fe",
                total = total,
                continueOnError = false,
                results = JsonObject(emptyMap()),
                maxConcurrency = 1,
                items = items,
            )

            service(runRepo = runRepo, iterationRepo = iterRepo, executor = executor)
                .start(pipeline, sampleInput, "e", OffsetDateTime.now())

            coVerify(exactly = 1) { runRepo.add(any()) }
        }

        exercise(total = 1, items = JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b"))))
        exercise(total = 2, items = JsonArray(listOf(JsonPrimitive("a"))))
    }

    @Test
    fun `a bounded child completion starts the next item from the stored items`() = runTest {
        val runRepo = mockk<PipelineRunRepository>(relaxed = true)
        val logRepo = mockk<PipelineRunLogRepository>(relaxed = true)
        val iterRepo = mockk<PipelineRunIterationRepository>(relaxed = true)
        val store = mockk<PipelineRunResultStore>(relaxed = true)
        val executor = mockk<PipelineExecutor>()
        val childId = UUID.random()
        val parentId = UUID.random()
        val child = suspendedRun(childId).copy(
            status = PipelineRunStatus.RUNNING, parentRunId = parentId, parentNodeId = "fe", itemIndex = 0,
        )
        val added = mutableListOf<PipelineRun>()
        // The FIRST add is the driving run — return the parent-linked child so its completion reports
        // to the parent's iteration (the same trick the other foreach-completion tests use). Later
        // adds are the lazily-started siblings; they keep their own row.
        coEvery { runRepo.add(capture(added)) } answers {
            if (added.size == 1) child else firstArg<PipelineRun>().copy(id = UUID.random())
        }
        coEvery { executor.execute(pipeline, any(), any(), null) } returns ExecutionResult.Completed(null)
        coEvery { runRepo.complete(childId, PipelineRunStatus.OK, any(), null) } returns null
        coEvery { runRepo.getById(parentId) } returns suspendedRun(parentId, awaiting = listOf(PipelineRunAwait("fe")))
        // Item 0 of a 2-item, maxConcurrency-1 iteration reports → item 1 must start from the stored items.
        coEvery { iterRepo.recordResult(parentId, "fe", "0", any()) } returns PipelineRunIteration(
            parentRunId = parentId, nodeId = "fe", total = 2, continueOnError = false,
            results = buildJsonObject { put("0", JsonNull) },
            maxConcurrency = 1,
            items = JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b"))),
        )
        val enqueuer = registerChildRunEnqueuer()

        service(runRepo = runRepo, logRepo = logRepo, iterationRepo = iterRepo, resultStore = store, executor = executor)
            .start(pipeline, sampleInput, "e", OffsetDateTime.now())

        // The first add() is the driving run itself; the second is the lazily-started item 1, running
        // the same body (the completing child's pipeline/graph) with the stored item as its input.
        assertEquals(2, added.size)
        val next = added[1]
        assertEquals(1, next.itemIndex)
        assertEquals(parentId, next.parentRunId)
        assertEquals("fe", next.parentNodeId)
        assertEquals(JsonPrimitive("b"), next.input)
        assertEquals(1, enqueuer.configs.size)
        // 1 of 2 reported → not complete → no parent signal yet.
        coVerify(exactly = 0) { store.put(parentId, "fe", any(), any()) }
    }

    @Test
    fun `a bounded child completion still starts the next item when the run job is gone`() = runTest {
        // the run job's queue state can be gone by the time item N reports (it fully
        // completed before the next item could attach). The next item must still start — enqueued
        // unparented — rather than the iteration failing with "item N failed to start: Job not found".
        val runRepo = mockk<PipelineRunRepository>(relaxed = true)
        val logRepo = mockk<PipelineRunLogRepository>(relaxed = true)
        val iterRepo = mockk<PipelineRunIterationRepository>(relaxed = true)
        val store = mockk<PipelineRunResultStore>(relaxed = true)
        val executor = mockk<PipelineExecutor>()
        val childId = UUID.random()
        val parentId = UUID.random()
        val runJobId = UUID.random()
        val child = suspendedRun(childId).copy(
            status = PipelineRunStatus.RUNNING, parentRunId = parentId, parentNodeId = "fe", itemIndex = 0,
        )
        val added = mutableListOf<PipelineRun>()
        coEvery { runRepo.add(capture(added)) } answers {
            if (added.size == 1) child else firstArg<PipelineRun>().copy(id = UUID.random())
        }
        coEvery { executor.execute(pipeline, any(), any(), null) } returns ExecutionResult.Completed(null)
        coEvery { runRepo.complete(childId, PipelineRunStatus.OK, any(), null) } returns null
        // The parent carries a run job id whose state no longer exists on the queue.
        coEvery { runRepo.getById(parentId) } returns
            suspendedRun(parentId, awaiting = listOf(PipelineRunAwait("fe"))).copy(runJobId = runJobId)
        coEvery { iterRepo.recordResult(parentId, "fe", "0", any()) } returns PipelineRunIteration(
            parentRunId = parentId, nodeId = "fe", total = 2, continueOnError = false,
            results = buildJsonObject { put("0", JsonNull) },
            maxConcurrency = 1,
            items = JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b"))),
        )
        val goneJobQueue = mockk<JobQueue>(relaxed = true)
        coEvery { goneJobQueue.getJob(runJobId, any<suspend (Job?) -> Unit>()) } throws
            IllegalStateException("Job $runJobId not found")
        provides<JobConfigurationEnqueuer>(name = PipelineChildRunJobExecutor.NAME) {
            object : JobConfigurationEnqueuer {
                override val queueName = "pipeline-child-run"
                override suspend fun prepare(configuration: JsonElement, initializer: suspend Job.() -> Unit): Job {
                    val job = InternalJobConstructor(configuration, DummyExecutor::class)
                    job.initializer()
                    return job
                }
                override suspend fun enqueue(configuration: JsonElement, initializer: suspend Job.() -> Unit): Job =
                    prepare(configuration, initializer)
                override suspend fun enqueueLater(configuration: JsonElement, timeout: Duration, initializer: suspend Job.() -> Unit): Job =
                    prepare(configuration, initializer)
                override suspend fun queue(): JobQueue = goneJobQueue
            }
        }

        service(runRepo = runRepo, logRepo = logRepo, iterationRepo = iterRepo, resultStore = store, executor = executor)
            .start(pipeline, sampleInput, "e", OffsetDateTime.now())

        // Item 1 was still created AND enqueued (unparented) — before the fix, the missing run job
        // threw out of the attach and the child job was never enqueued, failing the parent's await.
        assertEquals(2, added.size)
        assertEquals(1, added[1].itemIndex)
        coVerify(exactly = 1) { goneJobQueue.enqueue(any()) }
        coVerify(exactly = 0) { runRepo.complete(parentId, PipelineRunStatus.FAILED, any(), any()) }
    }

    @Test
    fun `a bounded child creation failure without an existing unique row fails the parent await`() = runTest {
        val runRepo = mockk<PipelineRunRepository>(relaxed = true)
        val logRepo = mockk<PipelineRunLogRepository>(relaxed = true)
        val iterRepo = mockk<PipelineRunIterationRepository>(relaxed = true)
        val store = mockk<PipelineRunResultStore>(relaxed = true)
        val rollbackRepo = mockk<RollbackRepository>(relaxed = true)
        val pipelineService = stubbedPipelineService()
        val executor = mockk<PipelineExecutor>()
        val childId = UUID.random()
        val parentId = UUID.random()
        val child = suspendedRun(childId).copy(
            status = PipelineRunStatus.RUNNING, parentRunId = parentId, parentNodeId = "fe", itemIndex = 0,
        )
        var addCalls = 0
        coEvery { runRepo.add(any()) } answers {
            if (addCalls++ == 0) child else throw IllegalStateException("insert unavailable")
        }
        coEvery { runRepo.getByParentItem(parentId, "fe", 1) } returns null
        coEvery { executor.execute(pipeline, any(), any(), null) } returns ExecutionResult.Completed(null)
        coEvery { runRepo.complete(childId, PipelineRunStatus.OK, any(), null) } returns null
        val parent = suspendedRun(parentId, awaiting = listOf(PipelineRunAwait("fe")))
        coEvery { runRepo.getById(parentId) } returns parent
        coEvery { iterRepo.recordResult(parentId, "fe", "0", any()) } returns PipelineRunIteration(
            parentRunId = parentId, nodeId = "fe", total = 2, continueOnError = false,
            results = buildJsonObject { put("0", JsonNull) },
            maxConcurrency = 1,
            items = JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b"))),
        )
        coEvery { pipelineService.decodeGraph(any()) } returns PipelineGraph()
        coEvery { runRepo.complete(parentId, PipelineRunStatus.FAILED, any(), any()) } returns null

        service(
            runRepo = runRepo,
            logRepo = logRepo,
            resultStore = store,
            iterationRepo = iterRepo,
            rollbackRepo = rollbackRepo,
            pipelineService = pipelineService,
            executor = executor,
        ).start(pipeline, sampleInput, "e", OffsetDateTime.now())

        coVerify(exactly = 1) {
            runRepo.complete(
                parentId,
                PipelineRunStatus.FAILED,
                any(),
                match { it?.contains("item 1 failed to start: insert unavailable") == true },
            )
        }
    }

    @Test
    fun `a bounded unique-child conflict reuses the row and attaches its job to the persisted parent`() = runTest {
        val runRepo = mockk<PipelineRunRepository>(relaxed = true)
        val logRepo = mockk<PipelineRunLogRepository>(relaxed = true)
        val iterRepo = mockk<PipelineRunIterationRepository>(relaxed = true)
        val store = mockk<PipelineRunResultStore>(relaxed = true)
        val executor = mockk<PipelineExecutor>()
        val childId = UUID.random()
        val parentId = UUID.random()
        val parentJobId = UUID.random()
        val child = suspendedRun(childId).copy(
            status = PipelineRunStatus.RUNNING, parentRunId = parentId, parentNodeId = "fe", itemIndex = 0,
        )
        val existingNext = child.copy(id = UUID.random(), itemIndex = 1)
        var addCalls = 0
        coEvery { runRepo.add(any()) } answers {
            if (addCalls++ == 0) child else throw IllegalStateException("unique child")
        }
        coEvery { runRepo.getByParentItem(parentId, "fe", 1) } returns existingNext
        coEvery { executor.execute(pipeline, any(), any(), null) } returns ExecutionResult.Completed(null)
        coEvery { runRepo.complete(childId, PipelineRunStatus.OK, any(), null) } returns null
        coEvery { runRepo.getById(parentId) } returns
            suspendedRun(parentId, awaiting = listOf(PipelineRunAwait("fe"))).copy(runJobId = parentJobId)
        coEvery { iterRepo.recordResult(parentId, "fe", "0", any()) } returns PipelineRunIteration(
            parentRunId = parentId, nodeId = "fe", total = 2, continueOnError = false,
            results = buildJsonObject { put("0", JsonNull) },
            maxConcurrency = 1,
            items = JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b"))),
        )
        val queue = mockk<JobQueue>(relaxed = true)
        val parentJob = mockk<Job>(relaxed = true)
        coEvery { queue.getJob<Unit>(parentJobId, any()) } coAnswers {
            @Suppress("UNCHECKED_CAST")
            (invocation.args[1] as suspend (Job?) -> Unit).invoke(parentJob)
        }
        provides<JobConfigurationEnqueuer>(name = PipelineChildRunJobExecutor.NAME) {
            object : JobConfigurationEnqueuer {
                override val queueName = "pipeline-child-run"
                override suspend fun prepare(configuration: JsonElement, initializer: suspend Job.() -> Unit): Job =
                    InternalJobConstructor(configuration, DummyExecutor::class).also { it.initializer() }
                override suspend fun enqueue(configuration: JsonElement, initializer: suspend Job.() -> Unit): Job =
                    prepare(configuration, initializer)
                override suspend fun enqueueLater(
                    configuration: JsonElement,
                    timeout: Duration,
                    initializer: suspend Job.() -> Unit,
                ): Job = prepare(configuration, initializer)
                override suspend fun queue(): JobQueue = queue
            }
        }

        service(runRepo = runRepo, logRepo = logRepo, iterationRepo = iterRepo, resultStore = store, executor = executor)
            .start(pipeline, sampleInput, "e", OffsetDateTime.now())

        coVerify(exactly = 1) { runRepo.getByParentItem(parentId, "fe", 1) }
        io.mockk.verify(exactly = 1) { parentJob.addChild(any(), runOnParentComplete = false) }
        coVerify(exactly = 1) { queue.setJob(parentJob) }
        coVerify(exactly = 1) { queue.enqueue(any()) }
    }

    @Test
    fun `an unbounded child completion starts nothing extra`() = runTest {
        val runRepo = mockk<PipelineRunRepository>(relaxed = true)
        val logRepo = mockk<PipelineRunLogRepository>(relaxed = true)
        val iterRepo = mockk<PipelineRunIterationRepository>(relaxed = true)
        val store = mockk<PipelineRunResultStore>(relaxed = true)
        val executor = mockk<PipelineExecutor>()
        val childId = UUID.random()
        val parentId = UUID.random()
        val child = suspendedRun(childId).copy(
            status = PipelineRunStatus.RUNNING, parentRunId = parentId, parentNodeId = "fe", itemIndex = 0,
        )
        val added = mutableListOf<PipelineRun>()
        coEvery { runRepo.add(capture(added)) } returns child
        coEvery { executor.execute(pipeline, any(), any(), null) } returns ExecutionResult.Completed(null)
        coEvery { runRepo.complete(childId, PipelineRunStatus.OK, any(), null) } returns null
        coEvery { runRepo.getById(parentId) } returns suspendedRun(parentId, awaiting = listOf(PipelineRunAwait("fe")))
        // Legacy/unbounded iteration row (maxConcurrency 0, no items) → completion starts no siblings.
        coEvery { iterRepo.recordResult(parentId, "fe", "0", any()) } returns PipelineRunIteration(
            parentRunId = parentId, nodeId = "fe", total = 2, continueOnError = false,
            results = buildJsonObject { put("0", JsonNull) },
        )

        service(runRepo = runRepo, logRepo = logRepo, iterationRepo = iterRepo, resultStore = store, executor = executor)
            .start(pipeline, sampleInput, "e", OffsetDateTime.now())

        assertEquals(1, added.size)
        coVerify(exactly = 0) { store.put(parentId, "fe", any(), any()) }
    }

    @Test
    fun `foreach child failure with fail-fast fails the parent`() = runTest {
        val runRepo = mockk<PipelineRunRepository>(relaxed = true)
        val logRepo = mockk<PipelineRunLogRepository>(relaxed = true)
        val iterRepo = mockk<PipelineRunIterationRepository>(relaxed = true)
        val rollbackRepo = mockk<RollbackRepository>(relaxed = true)
        val store = mockk<PipelineRunResultStore>()
        val pipelineService = stubbedPipelineService()
        val executor = mockk<PipelineExecutor>()
        val childId = UUID.random()
        val parentId = UUID.random()
        val child = suspendedRun(childId).copy(
            status = PipelineRunStatus.RUNNING, parentRunId = parentId, parentNodeId = "fe", itemIndex = 1,
        )
        coEvery { runRepo.add(any()) } returns child
        // The child fails → finish() reports the failure to the parent's iteration.
        coEvery { executor.execute(pipeline, any(), any(), null) } throws RuntimeException("item exploded")
        coEvery { runRepo.complete(childId, PipelineRunStatus.FAILED, any(), "item exploded") } returns null
        coEvery { rollbackRepo.listForRunReversed(childId) } returns emptyList()
        val parent = suspendedRun(parentId, awaiting = listOf(PipelineRunAwait("fe")))
        coEvery { runRepo.getById(parentId) } returns parent
        // continueOnError = false → fail-fast resumes the parent as a failure.
        coEvery { iterRepo.recordResult(parentId, "fe", "1", any()) } returns PipelineRunIteration(
            parentRunId = parentId, nodeId = "fe", total = 3, continueOnError = false,
            results = buildJsonObject { put("1", buildJsonObject { put("error", "item exploded"); put("index", 1) }) },
        )
        coEvery { pipelineService.decodeGraph(any()) } returns PipelineGraph()
        // The parent's fail-fast resume has no wired error port on "fe" → it clears the node's staged
        // result before failing the parent run.
        coEvery { store.remove(parentId, "fe") } returns Unit
        coEvery { runRepo.complete(parentId, PipelineRunStatus.FAILED, any(), any()) } returns null
        coEvery { rollbackRepo.listForRunReversed(parentId) } returns emptyList()

        service(
            runRepo = runRepo, logRepo = logRepo, resultStore = store, iterationRepo = iterRepo, rollbackRepo = rollbackRepo,
            pipelineService = pipelineService, executor = executor,
        ).start(pipeline, sampleInput, "e", OffsetDateTime.now())

        coVerify(exactly = 1) { runRepo.complete(parentId, PipelineRunStatus.FAILED, any(), match { it!!.contains("item 1 failed") }) }
        coVerify(exactly = 1) { store.remove(parentId, "fe") }
    }

    @Test
    fun `foreach child failure with continueOnError records the marker without failing the parent`() = runTest {
        val runRepo = mockk<PipelineRunRepository>(relaxed = true)
        val logRepo = mockk<PipelineRunLogRepository>(relaxed = true)
        val iterRepo = mockk<PipelineRunIterationRepository>(relaxed = true)
        val rollbackRepo = mockk<RollbackRepository>(relaxed = true)
        val store = mockk<PipelineRunResultStore>(relaxed = true)
        val executor = mockk<PipelineExecutor>()
        val childId = UUID.random()
        val parentId = UUID.random()
        val child = suspendedRun(childId).copy(
            status = PipelineRunStatus.RUNNING, parentRunId = parentId, parentNodeId = "fe", itemIndex = 0,
        )
        coEvery { runRepo.add(any()) } returns child
        coEvery { executor.execute(pipeline, any(), any(), null) } throws RuntimeException("soft fail")
        coEvery { runRepo.complete(childId, PipelineRunStatus.FAILED, any(), "soft fail") } returns null
        coEvery { rollbackRepo.listForRunReversed(childId) } returns emptyList()
        coEvery { runRepo.getById(parentId) } returns suspendedRun(parentId, awaiting = listOf(PipelineRunAwait("fe")))
        // continueOnError = true AND not yet complete (1 of 2) → just record, no parent failure, no signal.
        coEvery { iterRepo.recordResult(parentId, "fe", "0", any()) } returns PipelineRunIteration(
            parentRunId = parentId, nodeId = "fe", total = 2, continueOnError = true,
            results = buildJsonObject { put("0", buildJsonObject { put("error", "soft fail"); put("index", 0) }) },
        )

        service(
            runRepo = runRepo, logRepo = logRepo, iterationRepo = iterRepo, rollbackRepo = rollbackRepo,
            resultStore = store, executor = executor,
        ).start(pipeline, sampleInput, "e", OffsetDateTime.now())

        // Parent not failed by this item, and not signalled (iteration incomplete).
        coVerify(exactly = 0) { runRepo.complete(parentId, any(), any(), any()) }
        coVerify(exactly = 0) { store.put(parentId, "fe", any(), any()) }
    }

    @Test
    fun `child completion is a no-op when the parent is already terminal`() = runTest {
        val runRepo = mockk<PipelineRunRepository>(relaxed = true)
        val logRepo = mockk<PipelineRunLogRepository>(relaxed = true)
        val iterRepo = mockk<PipelineRunIterationRepository>(relaxed = true)
        val executor = mockk<PipelineExecutor>()
        val childId = UUID.random()
        val parentId = UUID.random()
        val child = suspendedRun(childId).copy(
            status = PipelineRunStatus.RUNNING, parentRunId = parentId, parentNodeId = "fe", itemIndex = 0,
        )
        coEvery { runRepo.add(any()) } returns child
        coEvery { executor.execute(pipeline, any(), any(), null) } returns ExecutionResult.Completed(null)
        coEvery { runRepo.complete(childId, PipelineRunStatus.OK, any(), null) } returns null
        // Parent is already terminal → reportIterationResult returns before recording anything.
        coEvery { runRepo.getById(parentId) } returns suspendedRun(parentId).copy(status = PipelineRunStatus.OK)

        service(runRepo = runRepo, logRepo = logRepo, iterationRepo = iterRepo, executor = executor)
            .start(pipeline, sampleInput, "e", OffsetDateTime.now())

        coVerify(exactly = 0) { iterRepo.recordResult(any(), any(), any(), any()) }
    }

    @Test
    fun `child completion is a no-op when the parent no longer parks on the node`() = runTest {
        val runRepo = mockk<PipelineRunRepository>(relaxed = true)
        val logRepo = mockk<PipelineRunLogRepository>(relaxed = true)
        val iterRepo = mockk<PipelineRunIterationRepository>(relaxed = true)
        val executor = mockk<PipelineExecutor>()
        val childId = UUID.random()
        val parentId = UUID.random()
        val child = suspendedRun(childId).copy(
            status = PipelineRunStatus.RUNNING, parentRunId = parentId, parentNodeId = "fe", itemIndex = 0,
        )
        coEvery { runRepo.add(any()) } returns child
        coEvery { executor.execute(pipeline, any(), any(), null) } returns ExecutionResult.Completed(null)
        coEvery { runRepo.complete(childId, PipelineRunStatus.OK, any(), null) } returns null
        // Parent is live but parked on a different node → no matching awaitKey → no-op.
        coEvery { runRepo.getById(parentId) } returns suspendedRun(parentId, awaiting = listOf(PipelineRunAwait("other")))

        service(runRepo = runRepo, logRepo = logRepo, iterationRepo = iterRepo, executor = executor)
            .start(pipeline, sampleInput, "e", OffsetDateTime.now())

        coVerify(exactly = 0) { iterRepo.recordResult(any(), any(), any(), any()) }
    }

    // ----------------------------------------------------------------------------------------------
    // Node-output bounding via execute → flushNodeRecords → persistNodeEvent → boundOutput
    // ----------------------------------------------------------------------------------------------

    @Test
    fun `execute persists buffered node events with an oversized output replaced by a truncation marker`() = runTest {
        val runRepo = mockk<PipelineRunRepository>(relaxed = true)
        val logRepo = mockk<PipelineRunLogRepository>(relaxed = true)
        val nodeRepo = mockk<NodeExecutionRepository>(relaxed = true)
        val executor = mockk<PipelineExecutor>()
        val runId = UUID.random()
        val run = suspendedRun(runId).copy(status = PipelineRunStatus.RUNNING)
        coEvery { runRepo.add(any()) } returns run
        // The executor records a node event with a huge output into the sink it receives.
        val bigValue = "x".repeat(20_000)
        coEvery { executor.execute(any(), any(), any(), null) } coAnswers {
            val ctx = arg<PipelineContext>(2)
            ctx.nodeSink?.record(
                NodeExecutionEvent(
                    nodeId = "big",
                    status = NodeExecutionStatus.OK,
                    startedAt = OffsetDateTime.now(),
                    finishedAt = OffsetDateTime.now(),
                    port = null,
                    error = null,
                    output = JsonPrimitive(bigValue),
                )
            )
            ExecutionResult.Completed(null)
        }
        coEvery { runRepo.complete(runId, PipelineRunStatus.OK, any(), null) } returns null
        val recorded = slot<NodeExecutionRecord>()
        coEvery { nodeRepo.add(capture(recorded)) } answers { recorded.captured }

        service(runRepo = runRepo, logRepo = logRepo, nodeExecutionRepo = nodeRepo, executor = executor)
            .start(pipeline, sampleInput, "e", OffsetDateTime.now())

        // The oversized value was swapped for a small truncation marker.
        val out = recorded.captured.output as JsonObject
        assertEquals(JsonPrimitive(true), out["_truncated"])
    }

    @Test
    fun `persistNodeEvent swallows a node-execution write failure without failing the run`() = runTest {
        val runRepo = mockk<PipelineRunRepository>(relaxed = true)
        val logRepo = mockk<PipelineRunLogRepository>(relaxed = true)
        val nodeRepo = mockk<NodeExecutionRepository>()
        val executor = mockk<PipelineExecutor>()
        val runId = UUID.random()
        val run = suspendedRun(runId).copy(status = PipelineRunStatus.RUNNING)
        coEvery { runRepo.add(any()) } returns run
        coEvery { executor.execute(any(), any(), any(), null) } coAnswers {
            val ctx = arg<PipelineContext>(2)
            ctx.nodeSink?.record(
                NodeExecutionEvent(
                    nodeId = "n", status = NodeExecutionStatus.OK,
                    startedAt = OffsetDateTime.now(), finishedAt = OffsetDateTime.now(),
                    port = null, error = null, output = JsonPrimitive("small"),
                )
            )
            ExecutionResult.Completed(null)
        }
        // The timeline write throws — it must be logged and swallowed (observability never fails a run).
        coEvery { nodeRepo.add(any()) } throws RuntimeException("db down")
        coEvery { runRepo.complete(runId, PipelineRunStatus.OK, any(), null) } returns null

        service(runRepo = runRepo, logRepo = logRepo, nodeExecutionRepo = nodeRepo, executor = executor)
            .start(pipeline, sampleInput, "e", OffsetDateTime.now())

        // The run still completed OK despite the failed timeline write.
        coVerify(exactly = 1) { runRepo.complete(runId, PipelineRunStatus.OK, any(), null) }
    }

    @Test
    fun `flushRollback swallows a rollback-log write failure`() = runTest {
        val runRepo = mockk<PipelineRunRepository>(relaxed = true)
        val logRepo = mockk<PipelineRunLogRepository>(relaxed = true)
        val rollbackRepo = mockk<RollbackRepository>(relaxed = true)
        val executor = mockk<PipelineExecutor>()
        val runId = UUID.random()
        val run = suspendedRun(runId).copy(status = PipelineRunStatus.RUNNING)
        coEvery { runRepo.add(any()) } returns run
        val rbPipelineId = UUID.random()
        coEvery { executor.execute(any(), any(), any(), null) } coAnswers {
            val ctx = arg<PipelineContext>(2)
            ctx.rollbackSink?.record(
                RollbackEvent(nodeId = "n", rollbackPipelineId = rbPipelineId, output = JsonPrimitive("o")),
            )
            ExecutionResult.Completed(null)
        }
        // The saga-log write throws — logged and swallowed.
        coEvery { rollbackRepo.add(any()) } throws RuntimeException("saga down")
        coEvery { runRepo.complete(runId, PipelineRunStatus.OK, any(), null) } returns null

        service(runRepo = runRepo, logRepo = logRepo, rollbackRepo = rollbackRepo, executor = executor)
            .start(pipeline, sampleInput, "e", OffsetDateTime.now())

        coVerify(exactly = 1) { runRepo.complete(runId, PipelineRunStatus.OK, any(), null) }
    }

    @Test
    fun `flushRollback records a buffered rollback completion to the saga log`() = runTest {
        val runRepo = mockk<PipelineRunRepository>(relaxed = true)
        val logRepo = mockk<PipelineRunLogRepository>(relaxed = true)
        val rollbackRepo = mockk<RollbackRepository>(relaxed = true)
        val executor = mockk<PipelineExecutor>()
        val runId = UUID.random()
        val run = suspendedRun(runId).copy(status = PipelineRunStatus.RUNNING)
        coEvery { runRepo.add(any()) } returns run
        val rbPipelineId = UUID.random()
        coEvery { executor.execute(any(), any(), any(), null) } coAnswers {
            val ctx = arg<PipelineContext>(2)
            ctx.rollbackSink?.record(
                RollbackEvent(nodeId = "rbNode", rollbackPipelineId = rbPipelineId, output = JsonPrimitive("snap")),
            )
            ExecutionResult.Completed(null)
        }
        val record = slot<bosca.pipelines.model.RollbackRecord>()
        coEvery { rollbackRepo.add(capture(record)) } returns Unit
        coEvery { runRepo.complete(runId, PipelineRunStatus.OK, any(), null) } returns null

        service(runRepo = runRepo, logRepo = logRepo, rollbackRepo = rollbackRepo, executor = executor)
            .start(pipeline, sampleInput, "e", OffsetDateTime.now())

        assertEquals(runId, record.captured.runId)
        assertEquals("rbNode", record.captured.nodeId)
        assertEquals(rbPipelineId, record.captured.rollbackPipelineId)
        assertEquals(JsonPrimitive("snap"), record.captured.output)
    }

    // ----------------------------------------------------------------------------------------------
    // runChildPipeline — body with no Input node skips schema validation entirely (line 156 null arm)
    // ----------------------------------------------------------------------------------------------

    @Test
    fun `runChildPipeline skips schema validation when the body has no input node`() = runTest {
        val runRepo = mockk<PipelineRunRepository>(relaxed = true)
        val logRepo = mockk<PipelineRunLogRepository>(relaxed = true)
        val pipelineService = stubbedPipelineService()
        val bodyId = UUID.random()
        // No InputNode at all → body.inputNode is null → the `?.schema?.let` chain short-circuits.
        val body = pipeline.copy(id = bodyId, nodes = emptyList())
        coEvery { pipelineService.get(bodyId) } returns body
        coEvery { pipelineService.graphAsJsonElement(body) } returns JsonObject(emptyMap())
        coEvery { runRepo.add(any()) } answers { firstArg<PipelineRun>().copy(id = UUID.random()) }
        // With no schema to validate, the child run is created and its child-run job enqueued straight away.
        val enqueuer = registerChildRunEnqueuer()

        service(runRepo = runRepo, logRepo = logRepo, pipelineService = pipelineService)
            .runChildPipeline(UUID.random(), "n", bodyId, sampleInput, OffsetDateTime.now())

        coVerify(exactly = 1) { runRepo.add(any()) }
        assertEquals(1, enqueuer.configs.size)
    }

    // ----------------------------------------------------------------------------------------------
    // runOnDemand — the block loop actually iterates before the run turns terminal (lines 203-205)
    // ----------------------------------------------------------------------------------------------

    @Test
    fun `runOnDemand blocks and polls until a suspended run resolves to OK`() = runTest {
        val runRepo = mockk<PipelineRunRepository>(relaxed = true)
        val logRepo = mockk<PipelineRunLogRepository>(relaxed = true)
        val pipelineService = stubbedPipelineService()
        val executor = mockk<PipelineExecutor>()
        coEvery { pipelineService.graphAsJsonElement(pipeline) } returns JsonObject(emptyMap())
        val runId = UUID.random()
        coEvery { runRepo.add(any()) } answers { firstArg<PipelineRun>().copy(id = runId) }
        // The first drive suspends; persisting the suspended checkpoint succeeds.
        coEvery { executor.execute(any(), any(), any(), any()) } returns
            ExecutionResult.Suspended(ExecutionState(emptyMap()), emptyList())
        coEvery { runRepo.updateState(any(), PipelineRunStatus.SUSPENDED, any(), any(), null, any()) } answers
            { suspendedRun(runId) }
        // A generous block window so the while-loop body (delay + re-poll) runs at least once: the
        // first poll is still SUSPENDED, the second poll observes the resolved OK row.
        val cfg = mockk<PipelinesRuntimeConfiguration>(relaxed = true)
        coEvery { cfg.onDemandRunMaxBlockMillis } returns 10_000
        val finished = suspendedRun(runId).copy(
            status = PipelineRunStatus.OK,
            output = json.encodeToJsonElement(SampleEvent.serializer(), SampleEvent("late")),
        )
        coEvery { runRepo.getById(runId) } returnsMany listOf(suspendedRun(runId), finished)

        val run = service(runRepo = runRepo, logRepo = logRepo, pipelineService = pipelineService, executor = executor, config = cfg)
            .start(pipeline, sampleInput, "manual", OffsetDateTime.now(), AuthenticationContext(null, null))

        assertEquals(PipelineRunStatus.OK, run!!.status)
        assertEquals(finished.output, run.output)
        // The loop iterated: getById was consulted more than the single initial read.
        coVerify(atLeast = 2) { runRepo.getById(runId) }
    }

    @Test
    fun `runOnDemand returns CANCELLED with the error for a terminally cancelled run`() = runTest {
        val runRepo = mockk<PipelineRunRepository>(relaxed = true)
        val logRepo = mockk<PipelineRunLogRepository>(relaxed = true)
        val pipelineService = stubbedPipelineService()
        val executor = mockk<PipelineExecutor>()
        coEvery { pipelineService.graphAsJsonElement(pipeline) } returns JsonObject(emptyMap())
        val runId = UUID.random()
        coEvery { runRepo.add(any()) } answers { firstArg<PipelineRun>().copy(id = runId) }
        coEvery { executor.execute(any(), any(), any(), any()) } returns ExecutionResult.Completed(null)
        coEvery { runRepo.complete(any(), any(), any(), any()) } returns null
        // Already CANCELLED on the first poll → the CANCELLED arm of the terminal `when` runs.
        coEvery { runRepo.getById(runId) } returns
            suspendedRun(runId).copy(status = PipelineRunStatus.CANCELLED, error = "operator stop")

        val run = service(runRepo = runRepo, logRepo = logRepo, pipelineService = pipelineService, executor = executor)
            .start(pipeline, sampleInput, "manual", OffsetDateTime.now(), AuthenticationContext(null, null))

        assertEquals(PipelineRunStatus.CANCELLED, run!!.status)
        assertEquals("operator stop", run.error)
        assertNull(run.output)
    }

    // ----------------------------------------------------------------------------------------------
    // replay — reconstructInput decodes a typed input through the Event Catalog serializer
    // (lines 253-261: inputType present, registry lookup hits, serializer != null branch)
    // ----------------------------------------------------------------------------------------------

    @Test
    fun `replay reconstructs a typed input through the catalogued event serializer`() = runTest {
        val runRepo = mockk<PipelineRunRepository>(relaxed = true)
        val logRepo = mockk<PipelineRunLogRepository>(relaxed = true)
        val pipelineService = stubbedPipelineService()
        val executor = mockk<PipelineExecutor>()
        val typeName = SampleEvent.serializer().descriptor.serialName
        // Register an EventCatalogRegistrar that exposes SampleEvent's compiled serializer by fqdn,
        // so reconstructInput resolves serializer != null and decodes the stored JSON to the typed value.
        provides<EventCatalogRegistrar> {
            object : EventCatalogRegistrar {
                override val events: List<EventDescriptor> = emptyList()
                override val serializers: Map<String, KSerializer<*>> = mapOf(typeName to SampleEvent.serializer())
            }
        }
        val sourceId = UUID.random()
        val source = suspendedRun(sourceId).copy(
            status = PipelineRunStatus.FAILED,
            input = json.encodeToJsonElement(SampleEvent.serializer(), SampleEvent("seed")),
            inputType = typeName,
        )
        coEvery { runRepo.getById(sourceId) } returns source
        coEvery { pipelineService.get(source.pipelineId) } returns pipeline
        coEvery { pipelineService.graphAsJsonElement(pipeline) } returns JsonObject(emptyMap())
        val newRunId = UUID.random()
        val replayed = slot<PipelineRun>()
        coEvery { runRepo.add(capture(replayed)) } answers { replayed.captured.copy(id = newRunId) }
        coEvery { executor.execute(any(), any(), any(), any()) } returns ExecutionResult.Completed(null)
        coEvery { runRepo.complete(newRunId, PipelineRunStatus.OK, any(), null) } returns null
        coEvery { runRepo.getById(newRunId) } returns suspendedRun(newRunId).copy(status = PipelineRunStatus.OK)

        val result = service(runRepo = runRepo, logRepo = logRepo, pipelineService = pipelineService, executor = executor)
            .restart(sourceId)

        assertNotNull(result)
        // The replay seeded the TYPED input (its origin type survives), not a raw JSON passthrough.
        assertEquals(typeName, replayed.captured.inputType)
        assertEquals(source.input, replayed.captured.input)
    }

    @Test
    fun `replay passes a null input through as JsonNull when no catalogued serializer matches the type`() = runTest {
        val runRepo = mockk<PipelineRunRepository>(relaxed = true)
        val logRepo = mockk<PipelineRunLogRepository>(relaxed = true)
        val pipelineService = stubbedPipelineService()
        val executor = mockk<PipelineExecutor>()
        // A registrar that EXISTS but whose serializer map does NOT contain the run's inputType, so
        // `firstNotNullOfOrNull { it.get().serializers[typeName] }` evaluates the lookup to null
        // (the no-match arm) and yields no serializer overall (the exhausted-to-null arm).
        provides<EventCatalogRegistrar> {
            object : EventCatalogRegistrar {
                override val events: List<EventDescriptor> = emptyList()
                override val serializers: Map<String, KSerializer<*>> =
                    mapOf("some.other.type" to SampleEvent.serializer())
            }
        }
        val sourceId = UUID.random()
        // input is the NULL column (not JsonNull) → `run.input ?: JsonNull` takes its right arm, and
        // inputType is set but uncatalogued → reconstructInput falls to PipelineValue.ofJson(JsonNull).
        val source = suspendedRun(sourceId).copy(
            status = PipelineRunStatus.FAILED,
            input = null,
            inputType = "unknown.event.type",
        )
        coEvery { runRepo.getById(sourceId) } returns source
        coEvery { pipelineService.get(source.pipelineId) } returns pipeline
        coEvery { pipelineService.graphAsJsonElement(pipeline) } returns JsonObject(emptyMap())
        val newRunId = UUID.random()
        val replayed = slot<PipelineRun>()
        coEvery { runRepo.add(capture(replayed)) } answers { replayed.captured.copy(id = newRunId) }
        coEvery { executor.execute(any(), any(), any(), any()) } returns ExecutionResult.Completed(null)
        coEvery { runRepo.complete(newRunId, PipelineRunStatus.OK, any(), null) } returns null
        coEvery { runRepo.getById(newRunId) } returns suspendedRun(newRunId).copy(status = PipelineRunStatus.OK)

        val result = service(runRepo = runRepo, logRepo = logRepo, pipelineService = pipelineService, executor = executor)
            .restart(sourceId)

        assertNotNull(result)
        // The uncatalogued type → raw JSON passthrough of a null input, encoded back as JsonNull.
        assertEquals(JsonNull, replayed.captured.input)
    }

    // ----------------------------------------------------------------------------------------------
    // resume — nodeOutputs / awaiting / rehydrate fallback arms
    // ----------------------------------------------------------------------------------------------

    @Test
    fun `resume treats a non-object nodeOutputs checkpoint as empty (line 295 else arm)`() = runTest {
        val runRepo = mockk<PipelineRunRepository>(relaxed = true)
        val store = mockk<PipelineRunResultStore>(relaxed = true)
        val logRepo = mockk<PipelineRunLogRepository>(relaxed = true)
        val pipelineService = stubbedPipelineService()
        val executor = mockk<PipelineExecutor>()
        val runId = UUID.random()
        // nodeOutputs is JsonNull (not a JsonObject) → `as? JsonObject ?: JsonObject(emptyMap())`
        // takes the else arm, so containsKey is evaluated against the empty fallback (false → proceed).
        val suspended = suspendedRun(runId, awaiting = listOf(PipelineRunAwait("n1")))
            .copy(nodeOutputs = JsonNull)
        coEvery { runRepo.getById(runId) } returns suspended
        coEvery { pipelineService.decodeGraph(any()) } returns PipelineGraph(nodes = listOf(InputNode(id = "n1", acceptedType = "X")))
        coEvery { pipelineService.descriptorFor(any()) } returns null
        coEvery { store.get(runId, "n1") } returns PipelineRunNodeResult(JsonPrimitive("v"))
        coEvery { runRepo.updateState(runId, PipelineRunStatus.RUNNING, any(), any(), null, suspended.version) } returns
            suspended.copy(status = PipelineRunStatus.RUNNING, version = suspended.version + 1, nodeOutputs = JsonObject(emptyMap()))
        coEvery { executor.execute(any(), any(), any(), any()) } returns ExecutionResult.Completed(null)
        coEvery { runRepo.complete(runId, PipelineRunStatus.OK, any(), null) } returns suspended

        service(runRepo = runRepo, logRepo = logRepo, resultStore = store, pipelineService = pipelineService, executor = executor)
            .resume(runId, "n1", succeeded = true, error = null)

        // It advanced (the non-object checkpoint did not block the resume).
        coVerify(exactly = 1) { runRepo.updateState(runId, PipelineRunStatus.RUNNING, any(), any(), null, suspended.version) }
    }

    @Test
    fun `resume treats a non-array awaiting set as empty so a stale resume is a no-op (line 686 else arm)`() = runTest {
        val runRepo = mockk<PipelineRunRepository>(relaxed = true)
        val runId = UUID.random()
        // awaiting is JsonNull (not a JsonArray) → decodeAwaits falls back to emptyList → no match → no-op.
        val suspended = suspendedRun(runId).copy(awaiting = JsonNull)
        coEvery { runRepo.getById(runId) } returns suspended

        service(runRepo = runRepo).resume(runId, "n1", succeeded = true, error = null)

        coVerify(exactly = 0) { runRepo.updateState(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `resume rehydrates a non-empty checkpoint with both null and value entries (lines 678-679)`() = runTest {
        val runRepo = mockk<PipelineRunRepository>(relaxed = true)
        val store = mockk<PipelineRunResultStore>(relaxed = true)
        val logRepo = mockk<PipelineRunLogRepository>(relaxed = true)
        val pipelineService = stubbedPipelineService()
        val executor = mockk<PipelineExecutor>()
        val runId = UUID.random()
        val suspended = suspendedRun(runId, awaiting = listOf(PipelineRunAwait("n1")))
        coEvery { runRepo.getById(runId) } returns suspended
        coEvery { pipelineService.decodeGraph(any()) } returns PipelineGraph(nodes = listOf(InputNode(id = "n1", acceptedType = "X")))
        coEvery { pipelineService.descriptorFor(any()) } returns null
        coEvery { store.get(runId, "n1") } returns PipelineRunNodeResult(JsonPrimitive("v"))
        // The post-update running row carries a real checkpoint: one JsonNull entry (→ null) and one
        // value entry (→ PipelineValue.ofJson) — exercising both branches of rehydrate's mapValues.
        val running = suspended.copy(
            status = PipelineRunStatus.RUNNING,
            version = suspended.version + 1,
            nodeOutputs = buildJsonObject {
                put("done", JsonPrimitive("earlier"))
                put("skipped", JsonNull)
            },
        )
        coEvery { runRepo.updateState(runId, PipelineRunStatus.RUNNING, any(), any(), null, suspended.version) } returns running
        // Capture the executor's resume state to confirm the rehydrated map was threaded through.
        val stateSlot = slot<ExecutionState?>()
        coEvery { executor.execute(any(), any(), any(), captureNullable(stateSlot)) } returns ExecutionResult.Completed(null)
        coEvery { runRepo.complete(runId, PipelineRunStatus.OK, any(), null) } returns suspended

        service(runRepo = runRepo, logRepo = logRepo, resultStore = store, pipelineService = pipelineService, executor = executor)
            .resume(runId, "n1", succeeded = true, error = null)

        val rehydrated = stateSlot.captured!!.outputs
        assertNull(rehydrated["skipped"], "a JsonNull checkpoint entry rehydrates to a null value")
        assertNotNull(rehydrated["done"], "a value checkpoint entry rehydrates to a PipelineValue")
        // The resumed node is also threaded in alongside the rehydrated checkpoint.
        assertNotNull(rehydrated["n1"])
    }

    @Test
    fun `resume rehydrates a non-object running checkpoint as empty (line 678 else arm)`() = runTest {
        val runRepo = mockk<PipelineRunRepository>(relaxed = true)
        val store = mockk<PipelineRunResultStore>(relaxed = true)
        val logRepo = mockk<PipelineRunLogRepository>(relaxed = true)
        val pipelineService = stubbedPipelineService()
        val executor = mockk<PipelineExecutor>()
        val runId = UUID.random()
        val suspended = suspendedRun(runId, awaiting = listOf(PipelineRunAwait("n1")))
        coEvery { runRepo.getById(runId) } returns suspended
        coEvery { pipelineService.decodeGraph(any()) } returns PipelineGraph(nodes = listOf(InputNode(id = "n1", acceptedType = "X")))
        coEvery { pipelineService.descriptorFor(any()) } returns null
        coEvery { store.get(runId, "n1") } returns PipelineRunNodeResult(JsonPrimitive("v"))
        // running.nodeOutputs is JsonNull (not a JsonObject) → rehydrate's `as? JsonObject ?:` else arm.
        val running = suspended.copy(status = PipelineRunStatus.RUNNING, version = suspended.version + 1, nodeOutputs = JsonNull)
        coEvery { runRepo.updateState(runId, PipelineRunStatus.RUNNING, any(), any(), null, suspended.version) } returns running
        val stateSlot = slot<ExecutionState?>()
        coEvery { executor.execute(any(), any(), any(), captureNullable(stateSlot)) } returns ExecutionResult.Completed(null)
        coEvery { runRepo.complete(runId, PipelineRunStatus.OK, any(), null) } returns suspended

        service(runRepo = runRepo, logRepo = logRepo, resultStore = store, pipelineService = pipelineService, executor = executor)
            .resume(runId, "n1", succeeded = true, error = null)

        // Only the resumed node is present; the non-object checkpoint contributed nothing.
        assertEquals(setOf("n1"), stateSlot.captured!!.outputs.keys)
    }

    // ----------------------------------------------------------------------------------------------
    // execute — encodeOutputs keeps a non-null checkpoint value on suspend (line 674 non-null arm)
    // ----------------------------------------------------------------------------------------------

    @Test
    fun `execute encodes a non-null checkpoint value when suspending`() = runTest {
        val runRepo = mockk<PipelineRunRepository>(relaxed = true)
        val executor = mockk<PipelineExecutor>()
        val runId = UUID.random()
        val run = suspendedRun(runId).copy(status = PipelineRunStatus.RUNNING, version = 3)
        coEvery { runRepo.add(any()) } returns run
        // The suspended state carries a real (non-null) value for an already-evaluated node, so
        // encodeOutputs takes the `value.encode(json)` arm rather than the JsonNull fallback.
        coEvery { executor.execute(any(), any(), any(), null) } returns ExecutionResult.Suspended(
            ExecutionState(mapOf("done" to PipelineValue.of(SampleEvent("kept"), SampleEvent.serializer()))),
            emptyList(),
        )
        val outputsSlot = slot<JsonElement>()
        coEvery { runRepo.updateState(runId, PipelineRunStatus.SUSPENDED, capture(outputsSlot), any(), null, 3) } returns run

        service(runRepo = runRepo, executor = executor).start(pipeline, sampleInput, "e", OffsetDateTime.now())

        // The non-null value survived into the persisted checkpoint as its encoded JSON.
        val persisted = outputsSlot.captured.jsonObject["done"]
        assertEquals(json.encodeToJsonElement(SampleEvent.serializer(), SampleEvent("kept")), persisted)
    }

    // ----------------------------------------------------------------------------------------------
    // reportIterationResult — single-child success signals a live parent; parentNodeId-null guard;
    // recordResult-null guard; non-object results guard; finish error-fallback (lines 496,508,519,526,553)
    // ----------------------------------------------------------------------------------------------

    @Test
    fun `single-child success signals the live parent with the child output`() = runTest {
        val runRepo = mockk<PipelineRunRepository>(relaxed = true)
        val logRepo = mockk<PipelineRunLogRepository>(relaxed = true)
        val store = mockk<PipelineRunResultStore>(relaxed = true)
        val pipelineService = stubbedPipelineService()
        val executor = mockk<PipelineExecutor>()
        val childId = UUID.random()
        val parentId = UUID.random()
        // itemIndex = null → single-child (RunPipeline) report path.
        val child = suspendedRun(childId).copy(
            status = PipelineRunStatus.RUNNING, parentRunId = parentId, parentNodeId = "callNode", itemIndex = null,
        )
        coEvery { runRepo.add(any()) } returns child
        // The parent's resume re-drives its own (distinct) pipeline → a generic fallback covers it.
        coEvery { executor.execute(any(), any(), any(), any()) } returns ExecutionResult.Completed(null)
        coEvery { executor.execute(pipeline, any(), any(), null) } returns
            ExecutionResult.Completed(PipelineValue.of(SampleEvent("childOut"), SampleEvent.serializer()))
        coEvery { runRepo.complete(childId, PipelineRunStatus.OK, any(), null) } returns null
        // The parent is live and parked on callNode → signal stages the child's output and resumes it.
        val parent = suspendedRun(parentId, awaiting = listOf(PipelineRunAwait("callNode")))
        coEvery { runRepo.getById(parentId) } returns parent
        coEvery { pipelineService.decodeGraph(any()) } returns PipelineGraph(nodes = listOf(InputNode(id = "callNode", acceptedType = "X")))
        coEvery { pipelineService.descriptorFor(any()) } returns null
        val staged = slot<JsonElement>()
        coEvery { store.put(parentId, "callNode", capture(staged), any()) } returns Unit
        coEvery { store.get(parentId, "callNode") } answers { PipelineRunNodeResult(staged.captured) }
        coEvery { runRepo.updateState(parentId, PipelineRunStatus.RUNNING, any(), any(), null, parent.version) } returns
            parent.copy(status = PipelineRunStatus.RUNNING, version = parent.version + 1)
        coEvery { runRepo.complete(parentId, PipelineRunStatus.OK, any(), null) } returns parent

        service(runRepo = runRepo, logRepo = logRepo, resultStore = store, pipelineService = pipelineService, executor = executor)
            .start(pipeline, sampleInput, "e", OffsetDateTime.now())

        // The single child's output scalar was signalled to the parent's await.
        assertEquals(json.encodeToJsonElement(SampleEvent.serializer(), SampleEvent("childOut")), staged.captured)
        coVerify(exactly = 1) { store.put(parentId, "callNode", any(), any()) }
    }

    @Test
    fun `child report is a no-op when the child has a parent run but no parent node`() = runTest {
        val runRepo = mockk<PipelineRunRepository>(relaxed = true)
        val logRepo = mockk<PipelineRunLogRepository>(relaxed = true)
        val executor = mockk<PipelineExecutor>()
        val childId = UUID.random()
        val parentId = UUID.random()
        // parentRunId set but parentNodeId null → reportIterationResult returns at the parentNodeId guard.
        val child = suspendedRun(childId).copy(
            status = PipelineRunStatus.RUNNING, parentRunId = parentId, parentNodeId = null, itemIndex = 0,
        )
        coEvery { runRepo.add(any()) } returns child
        coEvery { executor.execute(pipeline, any(), any(), null) } returns ExecutionResult.Completed(null)
        coEvery { runRepo.complete(childId, PipelineRunStatus.OK, any(), null) } returns null

        service(runRepo = runRepo, logRepo = logRepo, executor = executor)
            .start(pipeline, sampleInput, "e", OffsetDateTime.now())

        // The parent was never looked up — the report short-circuited on the missing parent node id.
        coVerify(exactly = 0) { runRepo.getById(parentId) }
    }

    @Test
    fun `foreach child completion is a no-op when recordResult returns null`() = runTest {
        val runRepo = mockk<PipelineRunRepository>(relaxed = true)
        val logRepo = mockk<PipelineRunLogRepository>(relaxed = true)
        val iterRepo = mockk<PipelineRunIterationRepository>(relaxed = true)
        val store = mockk<PipelineRunResultStore>(relaxed = true)
        val executor = mockk<PipelineExecutor>()
        val childId = UUID.random()
        val parentId = UUID.random()
        val child = suspendedRun(childId).copy(
            status = PipelineRunStatus.RUNNING, parentRunId = parentId, parentNodeId = "fe", itemIndex = 0,
        )
        coEvery { runRepo.add(any()) } returns child
        coEvery { executor.execute(pipeline, any(), any(), null) } returns ExecutionResult.Completed(null)
        coEvery { runRepo.complete(childId, PipelineRunStatus.OK, any(), null) } returns null
        coEvery { runRepo.getById(parentId) } returns suspendedRun(parentId, awaiting = listOf(PipelineRunAwait("fe")))
        // recordResult races and returns null (row vanished) → the report returns before joining.
        coEvery { iterRepo.recordResult(parentId, "fe", "0", any()) } returns null

        service(runRepo = runRepo, logRepo = logRepo, iterationRepo = iterRepo, resultStore = store, executor = executor)
            .start(pipeline, sampleInput, "e", OffsetDateTime.now())

        coVerify(exactly = 0) { store.put(parentId, "fe", any(), any()) }
    }

    @Test
    fun `foreach child completion is a no-op when the iteration results are not an object`() = runTest {
        val runRepo = mockk<PipelineRunRepository>(relaxed = true)
        val logRepo = mockk<PipelineRunLogRepository>(relaxed = true)
        val iterRepo = mockk<PipelineRunIterationRepository>(relaxed = true)
        val store = mockk<PipelineRunResultStore>(relaxed = true)
        val executor = mockk<PipelineExecutor>()
        val childId = UUID.random()
        val parentId = UUID.random()
        val child = suspendedRun(childId).copy(
            status = PipelineRunStatus.RUNNING, parentRunId = parentId, parentNodeId = "fe", itemIndex = 0,
        )
        coEvery { runRepo.add(any()) } returns child
        coEvery { executor.execute(pipeline, any(), any(), null) } returns ExecutionResult.Completed(null)
        coEvery { runRepo.complete(childId, PipelineRunStatus.OK, any(), null) } returns null
        coEvery { runRepo.getById(parentId) } returns suspendedRun(parentId, awaiting = listOf(PipelineRunAwait("fe")))
        // results is JsonNull (not a JsonObject) → the `results as? JsonObject ?: return` guard fires.
        coEvery { iterRepo.recordResult(parentId, "fe", "0", any()) } returns PipelineRunIteration(
            parentRunId = parentId, nodeId = "fe", total = 1, continueOnError = false, results = JsonNull,
        )

        service(runRepo = runRepo, logRepo = logRepo, iterationRepo = iterRepo, resultStore = store, executor = executor)
            .start(pipeline, sampleInput, "e", OffsetDateTime.now())

        coVerify(exactly = 0) { store.put(parentId, "fe", any(), any()) }
    }

    @Test
    fun `cancel with no reason reports the status name to a live single-child parent (line 553 fallback)`() = runTest {
        val runRepo = mockk<PipelineRunRepository>(relaxed = true)
        val logRepo = mockk<PipelineRunLogRepository>(relaxed = true)
        val rollbackRepo = mockk<RollbackRepository>(relaxed = true)
        val store = mockk<PipelineRunResultStore>(relaxed = true)
        val pipelineService = stubbedPipelineService()
        val runId = UUID.random()
        val parentId = UUID.random()
        // A single child (itemIndex null) of a live parent; cancelled with a null reason so finish()
        // reports `error ?: status.name` → "CANCELLED" into the parent's failure.
        val child = suspendedRun(runId).copy(parentRunId = parentId, parentNodeId = "callNode", itemIndex = null)
        coEvery { runRepo.getById(runId) } returns child
        coEvery { runRepo.complete(runId, PipelineRunStatus.CANCELLED, any(), null) } returns null
        coEvery { rollbackRepo.listForRunReversed(runId) } returns emptyList()
        val parent = suspendedRun(parentId, awaiting = listOf(PipelineRunAwait("callNode")))
        coEvery { runRepo.getById(parentId) } returns parent
        // The parent's failure-resume reads its graph (empty → no error port → fail the parent).
        coEvery { pipelineService.decodeGraph(any()) } returns PipelineGraph()
        coEvery { runRepo.complete(parentId, PipelineRunStatus.FAILED, any(), any()) } returns null
        coEvery { rollbackRepo.listForRunReversed(parentId) } returns emptyList()

        service(runRepo = runRepo, logRepo = logRepo, rollbackRepo = rollbackRepo, resultStore = store, pipelineService = pipelineService)
            .cancel(runId, null)

        // The status name became the propagated sub-pipeline failure message (no explicit error).
        coVerify(exactly = 1) {
            runRepo.complete(parentId, PipelineRunStatus.FAILED, any(), match { it!!.contains("CANCELLED") })
        }
    }

    // ----------------------------------------------------------------------------------------------
    // runOnDemand — terminal FAILED row whose error is null falls back to "run not found"
    // (line 210 `current?.error ?: "run not found"` right arm with current NON-null; lines 207/208)
    // ----------------------------------------------------------------------------------------------

    @Test
    fun `runOnDemand on a terminally failed run with a null error falls back to run not found`() = runTest {
        val runRepo = mockk<PipelineRunRepository>(relaxed = true)
        val logRepo = mockk<PipelineRunLogRepository>(relaxed = true)
        val pipelineService = stubbedPipelineService()
        val executor = mockk<PipelineExecutor>()
        coEvery { pipelineService.graphAsJsonElement(pipeline) } returns JsonObject(emptyMap())
        val runId = UUID.random()
        coEvery { runRepo.add(any()) } answers { firstArg<PipelineRun>().copy(id = runId) }
        coEvery { executor.execute(any(), any(), any(), any()) } returns ExecutionResult.Completed(null)
        coEvery { runRepo.complete(any(), any(), any(), any()) } returns null
        // current is NON-null and FAILED, but its error column is null → `current?.error` resolves to
        // null (current non-null arm) and the elvis falls through to the "run not found" default.
        coEvery { runRepo.getById(runId) } returns
            suspendedRun(runId).copy(status = PipelineRunStatus.FAILED, error = null)

        val run = service(runRepo = runRepo, logRepo = logRepo, pipelineService = pipelineService, executor = executor)
            .start(pipeline, sampleInput, "manual", OffsetDateTime.now(), AuthenticationContext(null, null))

        assertEquals(PipelineRunStatus.FAILED, run!!.status)
        assertNull(run.error)
        assertNull(run.output)
    }

    @Test
    fun `runOnDemand on a terminally OK run with a null output returns a null-output OK outcome`() = runTest {
        val runRepo = mockk<PipelineRunRepository>(relaxed = true)
        val logRepo = mockk<PipelineRunLogRepository>(relaxed = true)
        val pipelineService = stubbedPipelineService()
        val executor = mockk<PipelineExecutor>()
        coEvery { pipelineService.graphAsJsonElement(pipeline) } returns JsonObject(emptyMap())
        val runId = UUID.random()
        coEvery { runRepo.add(any()) } answers { firstArg<PipelineRun>().copy(id = runId) }
        coEvery { executor.execute(any(), any(), any(), any()) } returns ExecutionResult.Completed(null)
        coEvery { runRepo.complete(any(), any(), any(), any()) } returns null
        // current NON-null + OK but with a null output column (the `current?.output` non-null arm,
        // distinct from the run-not-found case where current itself is null).
        coEvery { runRepo.getById(runId) } returns
            suspendedRun(runId).copy(status = PipelineRunStatus.OK, output = null)

        val run = service(runRepo = runRepo, logRepo = logRepo, pipelineService = pipelineService, executor = executor)
            .start(pipeline, sampleInput, "manual", OffsetDateTime.now(), AuthenticationContext(null, null))

        assertEquals(PipelineRunStatus.OK, run!!.status)
        assertNull(run.output)
        assertNull(run.error)
    }

    // ----------------------------------------------------------------------------------------------
    // resume — error-message fallbacks when the resume carries no error string
    // (lines 320/321 `error ?: "backing work for node ..."`; line 324 `error ?: "backing work failed"`)
    // ----------------------------------------------------------------------------------------------

    @Test
    fun `resume failure with no error port and a null error message uses the default failure message`() = runTest {
        val runRepo = mockk<PipelineRunRepository>(relaxed = true)
        val store = mockk<PipelineRunResultStore>(relaxed = true)
        val logRepo = mockk<PipelineRunLogRepository>(relaxed = true)
        val pipelineService = stubbedPipelineService()
        coEvery { pipelineService.decodeGraph(any()) } returns PipelineGraph()
        val runId = UUID.random()
        coEvery { runRepo.getById(runId) } returns suspendedRun(runId, awaiting = listOf(PipelineRunAwait("n1")))
        val errSlot = slot<String?>()
        coEvery { runRepo.complete(runId, PipelineRunStatus.FAILED, any(), captureNullable(errSlot)) } returns null

        // succeeded = false with error = null → both `error ?: "backing work for node ..."` elvis
        // right arms (recordResumedNode + finish) take the default message.
        service(runRepo = runRepo, logRepo = logRepo, resultStore = store, pipelineService = pipelineService)
            .resume(runId, "n1", succeeded = false, error = null)

        assertNotNull(errSlot.captured)
        assertTrue(errSlot.captured!!.contains("backing work for node 'n1' failed"))
        coVerify(exactly = 1) { store.remove(runId, "n1") }
    }

    @Test
    fun `resume failure on a wired error port with a null error message uses the default error payload`() = runTest {
        val runRepo = mockk<PipelineRunRepository>()
        val store = mockk<PipelineRunResultStore>(relaxed = true)
        val pipelineService = stubbedPipelineService()
        val executor = mockk<PipelineExecutor>()
        val logRepo = mockk<PipelineRunLogRepository>(relaxed = true)
        val runId = UUID.random()
        val suspended = suspendedRun(runId, awaiting = listOf(PipelineRunAwait("n1")))
        coEvery { runRepo.getById(runId) } returns suspended
        // n1 declares a wired error port, so the failure routes (errorPort != null) rather than failing.
        val graph = PipelineGraph(
            nodes = listOf(InputNode(id = "n1", acceptedType = "X")),
            edges = listOf(PipelineEdge(id = "e", source = "n1", target = "g", sourcePort = "error")),
        )
        coEvery { pipelineService.decodeGraph(any()) } returns graph
        coEvery { pipelineService.descriptorFor(any()) } returns NodeDescriptor(
            key = "x", label = "X", category = NodeCategory.ACTION,
            outputs = listOf(NodeOutputSlot(name = "error", error = true)),
        )
        val merged = slot<JsonElement>()
        coEvery {
            runRepo.updateState(runId, PipelineRunStatus.RUNNING, capture(merged), any(), null, suspended.version)
        } returns suspended.copy(status = PipelineRunStatus.RUNNING, version = suspended.version + 1)
        coEvery { executor.execute(any(), any(), any(), any()) } returns ExecutionResult.Completed(null)
        coEvery { runRepo.complete(runId, PipelineRunStatus.OK, any(), null) } returns suspended

        // succeeded = false, error = null → line 324 `error ?: "backing work failed"` right arm builds
        // the routed payload {error: "backing work failed"}.
        service(runRepo = runRepo, logRepo = logRepo, resultStore = store, pipelineService = pipelineService, executor = executor)
            .resume(runId, "n1", succeeded = false, error = null)

        val routed = merged.captured.jsonObject["n1"]!!.jsonObject
        assertEquals(JsonPrimitive("backing work failed"), routed["error"])
        coVerify(exactly = 1) { runRepo.updateState(runId, PipelineRunStatus.RUNNING, any(), any(), null, suspended.version) }
    }

    // ----------------------------------------------------------------------------------------------
    // resume — unhandled-error-port guard with a non-null error (line 335 `error?.let { ": $it" }`
    // non-null arm); descriptor-null and predicate-false arms of line 331
    // ----------------------------------------------------------------------------------------------

    @Test
    fun `resume on an unwired error port with an error appends the error to the unhandled-port message`() = runTest {
        val runRepo = mockk<PipelineRunRepository>(relaxed = true)
        val store = mockk<PipelineRunResultStore>(relaxed = true)
        val pipelineService = stubbedPipelineService()
        val logRepo = mockk<PipelineRunLogRepository>(relaxed = true)
        val runId = UUID.random()
        val suspended = suspendedRun(runId, awaiting = listOf(PipelineRunAwait("n1")))
        coEvery { runRepo.getById(runId) } returns suspended
        // alreadyExists declared error port but NOT wired (no edge) → the unhandled-error-port guard fires.
        coEvery { pipelineService.decodeGraph(any()) } returns PipelineGraph(nodes = listOf(InputNode(id = "n1", acceptedType = "X")))
        coEvery { pipelineService.descriptorFor(any()) } returns NodeDescriptor(
            key = "x", label = "X", category = NodeCategory.ACTION,
            outputs = listOf(NodeOutputSlot(name = "alreadyExists", error = true)),
        )
        coEvery { store.get(runId, "n1") } returns
            PipelineRunNodeResult(buildJsonObject { put("id", "c1") }, port = "alreadyExists")
        val errSlot = slot<String?>()
        coEvery { runRepo.complete(runId, PipelineRunStatus.FAILED, any(), captureNullable(errSlot)) } returns null

        // succeeded = true (staged outcome routes onto the unwired error port) BUT a non-null error is
        // also supplied → `error?.let { ": $it" }` takes its non-null arm and appends the detail.
        service(runRepo = runRepo, logRepo = logRepo, resultStore = store, pipelineService = pipelineService)
            .resume(runId, "n1", succeeded = true, error = "recovered 409")

        assertNotNull(errSlot.captured)
        assertTrue(errSlot.captured!!.contains("emitted on unhandled error port 'alreadyExists'"))
        assertTrue(errSlot.captured!!.contains(": recovered 409"), "the supplied error is appended")
        coVerify(exactly = 0) { runRepo.updateState(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `resume advances a ported outcome when the node has no descriptor (line 331 descriptor-null arm)`() = runTest {
        val runRepo = mockk<PipelineRunRepository>()
        val store = mockk<PipelineRunResultStore>(relaxed = true)
        val pipelineService = stubbedPipelineService()
        val executor = mockk<PipelineExecutor>()
        val logRepo = mockk<PipelineRunLogRepository>(relaxed = true)
        val runId = UUID.random()
        val suspended = suspendedRun(runId, awaiting = listOf(PipelineRunAwait("n1")))
        coEvery { runRepo.getById(runId) } returns suspended
        coEvery { pipelineService.decodeGraph(any()) } returns PipelineGraph(nodes = listOf(InputNode(id = "n1", acceptedType = "X")))
        // descriptor is null even though the staged outcome carries a port → `descriptor?.outputs?.any`
        // resolves to `null == true` == false, so the unhandled-port guard is skipped and the run advances.
        coEvery { pipelineService.descriptorFor(any()) } returns null
        coEvery { store.get(runId, "n1") } returns
            PipelineRunNodeResult(JsonPrimitive("v"), port = "out")
        coEvery {
            runRepo.updateState(runId, PipelineRunStatus.RUNNING, any(), any(), null, suspended.version)
        } returns suspended.copy(status = PipelineRunStatus.RUNNING, version = suspended.version + 1)
        coEvery { executor.execute(any(), any(), any(), any()) } returns ExecutionResult.Completed(null)
        coEvery { runRepo.complete(runId, PipelineRunStatus.OK, any(), null) } returns suspended

        service(runRepo = runRepo, logRepo = logRepo, resultStore = store, pipelineService = pipelineService, executor = executor)
            .resume(runId, "n1", succeeded = true, error = null)

        coVerify(exactly = 1) { runRepo.updateState(runId, PipelineRunStatus.RUNNING, any(), any(), null, suspended.version) }
        coVerify(exactly = 0) { runRepo.complete(runId, PipelineRunStatus.FAILED, any(), any()) }
    }

    @Test
    fun `resume advances a ported outcome on a declared NON-error port (line 331 predicate-false arm)`() = runTest {
        val runRepo = mockk<PipelineRunRepository>()
        val store = mockk<PipelineRunResultStore>(relaxed = true)
        val pipelineService = stubbedPipelineService()
        val executor = mockk<PipelineExecutor>()
        val logRepo = mockk<PipelineRunLogRepository>(relaxed = true)
        val runId = UUID.random()
        val suspended = suspendedRun(runId, awaiting = listOf(PipelineRunAwait("n1")))
        coEvery { runRepo.getById(runId) } returns suspended
        coEvery { pipelineService.decodeGraph(any()) } returns PipelineGraph(nodes = listOf(InputNode(id = "n1", acceptedType = "X")))
        // The staged outcome's port matches a declared slot whose `error` flag is FALSE → the
        // `it.name == port && it.error` predicate short-circuits on the right (error == false) → any() == false.
        coEvery { pipelineService.descriptorFor(any()) } returns NodeDescriptor(
            key = "x", label = "X", category = NodeCategory.ACTION,
            outputs = listOf(NodeOutputSlot(name = "out", error = false)),
        )
        coEvery { store.get(runId, "n1") } returns
            PipelineRunNodeResult(JsonPrimitive("v"), port = "out")
        coEvery {
            runRepo.updateState(runId, PipelineRunStatus.RUNNING, any(), any(), null, suspended.version)
        } returns suspended.copy(status = PipelineRunStatus.RUNNING, version = suspended.version + 1)
        coEvery { executor.execute(any(), any(), any(), any()) } returns ExecutionResult.Completed(null)
        coEvery { runRepo.complete(runId, PipelineRunStatus.OK, any(), null) } returns suspended

        service(runRepo = runRepo, logRepo = logRepo, resultStore = store, pipelineService = pipelineService, executor = executor)
            .resume(runId, "n1", succeeded = true, error = null)

        // A declared non-error port is never an unhandled error port → the run advances normally.
        coVerify(exactly = 1) { runRepo.updateState(runId, PipelineRunStatus.RUNNING, any(), any(), null, suspended.version) }
        coVerify(exactly = 0) { runRepo.complete(runId, PipelineRunStatus.FAILED, any(), any()) }
    }

    // ----------------------------------------------------------------------------------------------
    // runOnDemand — the post-drive poll observes the run, then a LATER poll observes it vanished
    // (lines 207/208/210): the first poll returns a non-terminal SUSPENDED row so the block loop runs,
    // then getById returns null inside the loop → `current` is observed BOTH non-null and then null, and
    // `current?.status ?: FAILED` resolves through the null arm reached from a populated loop variable.
    // ----------------------------------------------------------------------------------------------

    @Test
    fun `runOnDemand blocks then reports FAILED when the run row vanishes mid-poll`() = runTest {
        val runRepo = mockk<PipelineRunRepository>(relaxed = true)
        val logRepo = mockk<PipelineRunLogRepository>(relaxed = true)
        val pipelineService = stubbedPipelineService()
        val executor = mockk<PipelineExecutor>()
        coEvery { pipelineService.graphAsJsonElement(pipeline) } returns JsonObject(emptyMap())
        val runId = UUID.random()
        coEvery { runRepo.add(any()) } answers { firstArg<PipelineRun>().copy(id = runId) }
        // The first drive suspends; persisting the suspended checkpoint succeeds.
        coEvery { executor.execute(any(), any(), any(), any()) } returns
            ExecutionResult.Suspended(ExecutionState(emptyMap()), emptyList())
        coEvery { runRepo.updateState(any(), PipelineRunStatus.SUSPENDED, any(), any(), null, any()) } answers
            { suspendedRun(runId) }
        // A generous block window so the loop iterates. First poll: still SUSPENDED (non-terminal → the
        // while body runs); second poll: the row has vanished (null) → loop exits with `current` == null,
        // so `current?.status ?: FAILED` takes the FAILED right arm AFTER `current` held a real row.
        val cfg = mockk<PipelinesRuntimeConfiguration>(relaxed = true)
        coEvery { cfg.onDemandRunMaxBlockMillis } returns 10_000
        coEvery { runRepo.getById(runId) } returnsMany listOf(suspendedRun(runId), null)

        val run = service(runRepo = runRepo, logRepo = logRepo, pipelineService = pipelineService, executor = executor, config = cfg)
            .start(pipeline, sampleInput, "manual", OffsetDateTime.now(), AuthenticationContext(null, null))

        // current ended up null → the run handle the caller gets back is null (run not found).
        assertNull(run)
        // The loop iterated past the initial read before observing the vanished row.
        coVerify(atLeast = 2) { runRepo.getById(runId) }
    }

    // ----------------------------------------------------------------------------------------------
    // resume — the staged success outcome carries NO port (PipelineRunNodeResult.port == null), so the
    // resolved value has port == null and `resolved.port?.let` (line 330) takes its null (skip) arm AND
    // the `outcome?.port` (line 315) `if (outcomePort != null)` takes its else arm. Distinct from the
    // alreadyExists test (which stages a port) and from the no-staged-result test (outcome itself null).
    // ----------------------------------------------------------------------------------------------

    @Test
    fun `resume success with a staged value but no port skips the unhandled-error-port guard`() = runTest {
        val runRepo = mockk<PipelineRunRepository>()
        val store = mockk<PipelineRunResultStore>(relaxed = true)
        val pipelineService = stubbedPipelineService()
        val executor = mockk<PipelineExecutor>()
        val logRepo = mockk<PipelineRunLogRepository>(relaxed = true)
        val runId = UUID.random()
        val suspended = suspendedRun(runId, awaiting = listOf(PipelineRunAwait("n1")))
        coEvery { runRepo.getById(runId) } returns suspended
        // n1 declares an (unwired) error port, so the unhandled-error-port guard WOULD fire IF the
        // resolved value carried that port — but the staged outcome has port == null, so it does not.
        coEvery { pipelineService.decodeGraph(any()) } returns PipelineGraph(nodes = listOf(InputNode(id = "n1", acceptedType = "X")))
        coEvery { pipelineService.descriptorFor(any()) } returns NodeDescriptor(
            key = "x", label = "X", category = NodeCategory.ACTION,
            outputs = listOf(NodeOutputSlot(name = "error", error = true)),
        )
        // The staged outcome has a value but a NULL port → `if (outcomePort != null)` else arm → the
        // resolved value's port stays null → `resolved.port?.let` (330) skips the guard entirely.
        coEvery { store.get(runId, "n1") } returns PipelineRunNodeResult(JsonPrimitive("plain"), port = null)
        val merged = slot<JsonElement>()
        coEvery {
            runRepo.updateState(runId, PipelineRunStatus.RUNNING, capture(merged), any(), null, suspended.version)
        } returns suspended.copy(status = PipelineRunStatus.RUNNING, version = suspended.version + 1)
        coEvery { executor.execute(any(), any(), any(), any()) } returns ExecutionResult.Completed(null)
        coEvery { runRepo.complete(runId, PipelineRunStatus.OK, any(), null) } returns suspended

        service(runRepo = runRepo, logRepo = logRepo, resultStore = store, pipelineService = pipelineService, executor = executor)
            .resume(runId, "n1", succeeded = true, error = null)

        // The port-less staged value advanced the run (the guard was skipped, the run was not failed).
        assertEquals(JsonPrimitive("plain"), merged.captured.jsonObject["n1"])
        coVerify(exactly = 1) { runRepo.updateState(runId, PipelineRunStatus.RUNNING, any(), any(), null, suspended.version) }
        coVerify(exactly = 0) { runRepo.complete(runId, PipelineRunStatus.FAILED, any(), any()) }
    }

    // ----------------------------------------------------------------------------------------------
    // resume — a FAILURE that routes onto a wired error port whose descriptor slot is declared with
    // error = true: the resolved value carries that port, `resolved.port?.let` (330) runs its block, and
    // `descriptor?.outputs?.any { it.name == port && it.error }` is TRUE while the port IS wired (332),
    // so the unhandled-error-port guard's `isErrorPort && !wired` is `true && false` → guard skipped,
    // run advances. This drives the error-port branch through the resume failure path with recordResumedNode.
    // ----------------------------------------------------------------------------------------------

    @Test
    fun `resume failure routed onto a wired declared error port passes the error-port guard`() = runTest {
        val runRepo = mockk<PipelineRunRepository>()
        val store = mockk<PipelineRunResultStore>(relaxed = true)
        val pipelineService = stubbedPipelineService()
        val executor = mockk<PipelineExecutor>()
        val nodeRepo = mockk<NodeExecutionRepository>(relaxed = true)
        val logRepo = mockk<PipelineRunLogRepository>(relaxed = true)
        val runId = UUID.random()
        val suspended = suspendedRun(runId, awaiting = listOf(PipelineRunAwait("n1")))
        coEvery { runRepo.getById(runId) } returns suspended
        // n1 declares an error port that IS wired to a downstream node.
        val graph = PipelineGraph(
            nodes = listOf(InputNode(id = "n1", acceptedType = "X")),
            edges = listOf(PipelineEdge(id = "e", source = "n1", target = "g", sourcePort = "error")),
        )
        coEvery { pipelineService.decodeGraph(any()) } returns graph
        coEvery { pipelineService.descriptorFor(any()) } returns NodeDescriptor(
            key = "x", label = "X", category = NodeCategory.ACTION,
            outputs = listOf(NodeOutputSlot(name = "error", error = true)),
        )
        coEvery {
            runRepo.updateState(runId, PipelineRunStatus.RUNNING, any(), any(), null, suspended.version)
        } returns suspended.copy(status = PipelineRunStatus.RUNNING, version = suspended.version + 1)
        coEvery { executor.execute(any(), any(), any(), any()) } returns ExecutionResult.Completed(null)
        coEvery { runRepo.complete(runId, PipelineRunStatus.OK, any(), null) } returns suspended
        // The resumed node's terminal OK event closes its timeline on the wired error port.
        val recorded = slot<NodeExecutionRecord>()
        coEvery { nodeRepo.add(capture(recorded)) } answers { recorded.captured }

        // Failure with a wired error port → routed onto "error"; the guard sees isErrorPort && wired → skip.
        service(runRepo = runRepo, logRepo = logRepo, resultStore = store, nodeExecutionRepo = nodeRepo, pipelineService = pipelineService, executor = executor)
            .resume(runId, "n1", succeeded = false, error = "boom")

        coVerify(exactly = 1) { runRepo.updateState(runId, PipelineRunStatus.RUNNING, any(), any(), null, suspended.version) }
        coVerify(exactly = 0) { runRepo.complete(runId, PipelineRunStatus.FAILED, any(), any()) }
        // The resumed node was recorded OK on its wired error port.
        assertEquals(NodeExecutionStatus.OK, recorded.captured.status)
        assertEquals("error", recorded.captured.port)
    }

    // ----------------------------------------------------------------------------------------------
    // resume — the unhandled-error-port guard fires (line 335) from a FAILURE that staged onto a
    // declared-but-unwired error port via a stored success outcome, with NO error message: distinct path
    // through recordResumedNode + finish on the unhandled branch (335) using the message-only form.
    // ----------------------------------------------------------------------------------------------

    @Test
    fun `resume emits on an unhandled declared error port and fails via recordResumedNode`() = runTest {
        val runRepo = mockk<PipelineRunRepository>(relaxed = true)
        val store = mockk<PipelineRunResultStore>(relaxed = true)
        val pipelineService = stubbedPipelineService()
        val nodeRepo = mockk<NodeExecutionRepository>(relaxed = true)
        val logRepo = mockk<PipelineRunLogRepository>(relaxed = true)
        val runId = UUID.random()
        val suspended = suspendedRun(runId, awaiting = listOf(PipelineRunAwait("n1")))
        coEvery { runRepo.getById(runId) } returns suspended
        // "failed" is a declared error port that is NOT wired (no edge) → the guard at 333-339 fires.
        coEvery { pipelineService.decodeGraph(any()) } returns PipelineGraph(nodes = listOf(InputNode(id = "n1", acceptedType = "X")))
        coEvery { pipelineService.descriptorFor(any()) } returns NodeDescriptor(
            key = "x", label = "X", category = NodeCategory.ACTION,
            outputs = listOf(NodeOutputSlot(name = "failed", error = true)),
        )
        // The job completed and staged its value onto the unwired "failed" error port.
        coEvery { store.get(runId, "n1") } returns
            PipelineRunNodeResult(buildJsonObject { put("reason", "x") }, port = "failed")
        val errSlot = slot<String?>()
        coEvery { runRepo.complete(runId, PipelineRunStatus.FAILED, any(), captureNullable(errSlot)) } returns null
        // The resumed node's FAILED terminal event names the unhandled port (recordResumedNode at 336).
        val recorded = slot<NodeExecutionRecord>()
        coEvery { nodeRepo.add(capture(recorded)) } answers { recorded.captured }

        // succeeded = true, error = null → the message has no `: <detail>` suffix (line 335 null arm).
        service(runRepo = runRepo, logRepo = logRepo, resultStore = store, nodeExecutionRepo = nodeRepo, pipelineService = pipelineService)
            .resume(runId, "n1", succeeded = true, error = null)

        assertNotNull(errSlot.captured)
        assertTrue(errSlot.captured!!.contains("emitted on unhandled error port 'failed'"))
        assertFalse(errSlot.captured!!.contains(": "), "no error detail is appended when error is null")
        coVerify(exactly = 1) { store.remove(runId, "n1") }
        assertEquals(NodeExecutionStatus.FAILED, recorded.captured.status)
        assertEquals("failed", recorded.captured.port)
        coVerify(exactly = 0) { runRepo.updateState(any(), any(), any(), any(), any(), any()) }
    }

    // ----------------------------------------------------------------------------------------------
    // reportIterationResult (ForEach) — a child completes with a NULL output, so the per-index result
    // marker is built from `output?.encode(json) ?: JsonNull` (line 517) taking the JsonNull (null-output)
    // arm on the indexed (ForEach) path — distinct from the single-child line-508 null-output test.
    // ----------------------------------------------------------------------------------------------

    @Test
    fun `foreach child completion with a null output records JsonNull at its index`() = runTest {
        val runRepo = mockk<PipelineRunRepository>(relaxed = true)
        val logRepo = mockk<PipelineRunLogRepository>(relaxed = true)
        val iterRepo = mockk<PipelineRunIterationRepository>(relaxed = true)
        val store = mockk<PipelineRunResultStore>(relaxed = true)
        val executor = mockk<PipelineExecutor>()
        val childId = UUID.random()
        val parentId = UUID.random()
        val child = suspendedRun(childId).copy(
            status = PipelineRunStatus.RUNNING, parentRunId = parentId, parentNodeId = "fe", itemIndex = 0,
        )
        coEvery { runRepo.add(any()) } returns child
        // The child completes successfully but with a NULL output (no Output node value).
        coEvery { executor.execute(pipeline, any(), any(), null) } returns ExecutionResult.Completed(null)
        coEvery { runRepo.complete(childId, PipelineRunStatus.OK, any(), null) } returns null
        coEvery { runRepo.getById(parentId) } returns suspendedRun(parentId, awaiting = listOf(PipelineRunAwait("fe")))
        // Capture the recorded per-index result JSON: it must be JsonNull (the line-517 elvis right arm).
        val resultJson = slot<JsonElement>()
        // Incomplete iteration (1 of 2) so the join/signal does not fire — we only assert the recorded marker.
        coEvery { iterRepo.recordResult(parentId, "fe", "0", capture(resultJson)) } returns PipelineRunIteration(
            parentRunId = parentId, nodeId = "fe", total = 2, continueOnError = false,
            results = buildJsonObject { put("0", JsonNull) },
        )

        service(runRepo = runRepo, logRepo = logRepo, iterationRepo = iterRepo, resultStore = store, executor = executor)
            .start(pipeline, sampleInput, "e", OffsetDateTime.now())

        // A null child output is recorded as JsonNull at the item's index (not an {error,index} marker).
        assertEquals(JsonNull, resultJson.captured)
        coVerify(exactly = 0) { store.put(parentId, "fe", any(), any()) }
    }

    // ----------------------------------------------------------------------------------------------
    // reportIterationResult — single-child success with a NULL child output signals JsonNull
    // (line 508 `output?.encode(json) ?: JsonNull` both null arms)
    // ----------------------------------------------------------------------------------------------

    @Test
    fun `single-child success with a null output signals JsonNull to the live parent`() = runTest {
        val runRepo = mockk<PipelineRunRepository>(relaxed = true)
        val logRepo = mockk<PipelineRunLogRepository>(relaxed = true)
        val store = mockk<PipelineRunResultStore>(relaxed = true)
        val pipelineService = stubbedPipelineService()
        val executor = mockk<PipelineExecutor>()
        val childId = UUID.random()
        val parentId = UUID.random()
        // itemIndex = null → single-child report; the child completes with a NULL output.
        val child = suspendedRun(childId).copy(
            status = PipelineRunStatus.RUNNING, parentRunId = parentId, parentNodeId = "callNode", itemIndex = null,
        )
        coEvery { runRepo.add(any()) } returns child
        coEvery { executor.execute(pipeline, any(), any(), null) } returns ExecutionResult.Completed(null)
        coEvery { executor.execute(any(), any(), any(), any()) } returns ExecutionResult.Completed(null)
        coEvery { runRepo.complete(childId, PipelineRunStatus.OK, any(), null) } returns null
        val parent = suspendedRun(parentId, awaiting = listOf(PipelineRunAwait("callNode")))
        coEvery { runRepo.getById(parentId) } returns parent
        coEvery { pipelineService.decodeGraph(any()) } returns PipelineGraph(nodes = listOf(InputNode(id = "callNode", acceptedType = "X")))
        coEvery { pipelineService.descriptorFor(any()) } returns null
        val staged = slot<JsonElement>()
        coEvery { store.put(parentId, "callNode", capture(staged), any()) } returns Unit
        coEvery { store.get(parentId, "callNode") } answers { PipelineRunNodeResult(staged.captured) }
        coEvery { runRepo.updateState(parentId, PipelineRunStatus.RUNNING, any(), any(), null, parent.version) } returns
            parent.copy(status = PipelineRunStatus.RUNNING, version = parent.version + 1)
        coEvery { runRepo.complete(parentId, PipelineRunStatus.OK, any(), null) } returns parent

        service(runRepo = runRepo, logRepo = logRepo, resultStore = store, pipelineService = pipelineService, executor = executor)
            .start(pipeline, sampleInput, "e", OffsetDateTime.now())

        // A null child output is signalled to the parent as JsonNull (the elvis right arm).
        assertEquals(JsonNull, staged.captured)
        coVerify(exactly = 1) { store.put(parentId, "callNode", JsonNull, any()) }
    }

    // ----------------------------------------------------------------------------------------------
    // reportIterationResult — parent's awaiting list iterates past a non-matching await before the
    // matching one (line 500 firstOrNull predicate false-then-true)
    // ----------------------------------------------------------------------------------------------

    @Test
    fun `single-child report skips a non-matching await before finding the parked node`() = runTest {
        val runRepo = mockk<PipelineRunRepository>(relaxed = true)
        val logRepo = mockk<PipelineRunLogRepository>(relaxed = true)
        val store = mockk<PipelineRunResultStore>(relaxed = true)
        val pipelineService = stubbedPipelineService()
        val executor = mockk<PipelineExecutor>()
        val childId = UUID.random()
        val parentId = UUID.random()
        val child = suspendedRun(childId).copy(
            status = PipelineRunStatus.RUNNING, parentRunId = parentId, parentNodeId = "callNode", itemIndex = null,
        )
        coEvery { runRepo.add(any()) } returns child
        coEvery { executor.execute(pipeline, any(), any(), null) } returns
            ExecutionResult.Completed(PipelineValue.of(SampleEvent("x"), SampleEvent.serializer()))
        coEvery { executor.execute(any(), any(), any(), any()) } returns ExecutionResult.Completed(null)
        coEvery { runRepo.complete(childId, PipelineRunStatus.OK, any(), null) } returns null
        // The parent parks on TWO nodes; "callNode" is the SECOND, so firstOrNull's predicate must
        // evaluate false for "other" before matching "callNode".
        val parent = suspendedRun(
            parentId,
            awaiting = listOf(PipelineRunAwait("other"), PipelineRunAwait("callNode")),
        )
        coEvery { runRepo.getById(parentId) } returns parent
        coEvery { pipelineService.decodeGraph(any()) } returns PipelineGraph(nodes = listOf(InputNode(id = "callNode", acceptedType = "X")))
        coEvery { pipelineService.descriptorFor(any()) } returns null
        val staged = slot<JsonElement>()
        coEvery { store.put(parentId, "callNode", capture(staged), any()) } returns Unit
        coEvery { store.get(parentId, "callNode") } answers { PipelineRunNodeResult(staged.captured) }
        coEvery { runRepo.updateState(parentId, PipelineRunStatus.RUNNING, any(), any(), null, parent.version) } returns
            parent.copy(status = PipelineRunStatus.RUNNING, version = parent.version + 1)
        coEvery { runRepo.complete(parentId, PipelineRunStatus.OK, any(), null) } returns parent

        service(runRepo = runRepo, logRepo = logRepo, resultStore = store, pipelineService = pipelineService, executor = executor)
            .start(pipeline, sampleInput, "e", OffsetDateTime.now())

        // The await key was resolved from the SECOND ("callNode") entry, so the child output was staged.
        coVerify(exactly = 1) { store.put(parentId, "callNode", any(), any()) }
    }

    // ----------------------------------------------------------------------------------------------
    // reportIterationResult (ForEach) — join over a complete-by-size iteration that is MISSING an
    // index key, so the per-index lookup falls back to JsonNull (line 528 `?: JsonNull`)
    // ----------------------------------------------------------------------------------------------

    @Test
    fun `foreach join fills a missing index slot with JsonNull`() = runTest {
        val runRepo = mockk<PipelineRunRepository>(relaxed = true)
        val logRepo = mockk<PipelineRunLogRepository>(relaxed = true)
        val iterRepo = mockk<PipelineRunIterationRepository>(relaxed = true)
        val store = mockk<PipelineRunResultStore>(relaxed = true)
        val pipelineService = stubbedPipelineService()
        val executor = mockk<PipelineExecutor>()
        val childId = UUID.random()
        val parentId = UUID.random()
        val child = suspendedRun(childId).copy(
            status = PipelineRunStatus.RUNNING, parentRunId = parentId, parentNodeId = "fe", itemIndex = 1,
        )
        coEvery { runRepo.add(any()) } returns child
        coEvery { executor.execute(pipeline, any(), any(), null) } returns
            ExecutionResult.Completed(PipelineValue.of(SampleEvent("r1"), SampleEvent.serializer()))
        coEvery { executor.execute(any(), any(), any(), any()) } returns ExecutionResult.Completed(null)
        coEvery { runRepo.complete(childId, PipelineRunStatus.OK, any(), null) } returns null
        val parent = suspendedRun(parentId, awaiting = listOf(PipelineRunAwait("fe")))
        coEvery { runRepo.getById(parentId) } returns parent
        // total = 2 and results has TWO keys ("1" and a stray "9") so size >= total fires the join,
        // but index "0" is absent → results["0"] is null → the join uses the JsonNull fallback.
        coEvery { iterRepo.recordResult(parentId, "fe", "1", any()) } returns PipelineRunIteration(
            parentRunId = parentId, nodeId = "fe", total = 2, continueOnError = false,
            results = buildJsonObject {
                put("1", JsonPrimitive("r1"))
                put("9", JsonPrimitive("stray"))
            },
        )
        coEvery { pipelineService.decodeGraph(any()) } returns PipelineGraph(nodes = listOf(InputNode(id = "fe", acceptedType = "X")))
        coEvery { pipelineService.descriptorFor(any()) } returns null
        val staged = slot<JsonElement>()
        coEvery { store.put(parentId, "fe", capture(staged), any()) } returns Unit
        coEvery { store.get(parentId, "fe") } answers { PipelineRunNodeResult(staged.captured) }
        coEvery { runRepo.updateState(parentId, PipelineRunStatus.RUNNING, any(), any(), null, parent.version) } returns
            parent.copy(status = PipelineRunStatus.RUNNING, version = parent.version + 1)
        coEvery { runRepo.complete(parentId, PipelineRunStatus.OK, any(), null) } returns parent

        service(runRepo = runRepo, logRepo = logRepo, iterationRepo = iterRepo, resultStore = store, pipelineService = pipelineService, executor = executor)
            .start(pipeline, sampleInput, "e", OffsetDateTime.now())

        // The joined array is [JsonNull (missing index 0), "r1" (index 1)].
        val joined = staged.captured as JsonArray
        assertEquals(2, joined.size)
        assertEquals(JsonNull, joined[0])
        assertEquals(JsonPrimitive("r1"), joined[1])
    }

    // NOTE on the residual 1-missed-branch on lines 207/208/313/331/335/382/500/508/517/674/686: each is
    // an unreachable arm Kover still counts after its Kotlin filter, verified by disassembling
    // PipelineRunServiceImpl and mapping every branch instruction to its source line. Three kinds, none
    // coverable without editing src/main (barred) or a forbidden technique (reflection / process-global
    // mock):
    //  - Kotlin null-safety phantom on a NON-null type: e.g. `outcome?.value ?: JsonNull` (313),
    //    `output?.encode(json) ?: JsonNull` (508/517/674), `descriptor?.outputs?.any{}` (331),
    //    `error?.let{}` (335), `(... as? JsonArray)
    //    ?.let{}` (686). The `?.`/`?:` emits an ifnull/ifnonnull whose null arm is over a value the type
    //    system guarantees non-null (a model field, an `encode`/`getContent`/`decodeFromJsonElement`
    //    return, a found `awaitKey`). The only runtime null is from a stub returning null for
    //    a non-null member — which requires an unchecked `null as T` cast and STILL does not move Kover's
    //    count (the null arm converges on the already-covered JsonNull/empty push), confirming it is a
    //    phantom: the behavioural arm IS exercised by the tests above, the counter just can't credit it.
    //  - `when`-over-enum / `current?.status` (207) and `current?.output` (208): the status-null arm is
    //    unreachable (status is non-null and the loop reads `current.status.isTerminal` first, so a
    //    status-null mock NPEs before reaching it); 208 runs only in the OK arm, where `current` is always
    //    non-null. The reachable run-vanished and OK/FAILED/CANCELLED/SUSPENDED arms are covered above.
    //  - coroutine state-machine COROUTINE_SUSPENDED check (508 off718): an `if_acmpne` that only takes its
    //    "suspended" arm if the inner suspend call actually parks mid-call — never in a synchronous unit
    //    test (the mocks return synchronously).

    /**
     * Register a fake [JobConfigurationEnqueuer] under [PipelineChildRunJobExecutor.NAME] so a
     * RunPipeline/ForEach can enqueue its child-run job (there is no real runner in these unit tests),
     * and return it so the test can assert on the captured [PipelineChildRunJob]s. Mirrors the
     * end-to-end test's `CapturingDelayEnqueuer`.
     */
    private fun registerChildRunEnqueuer(): CapturingChildRunEnqueuer {
        val enqueuer = CapturingChildRunEnqueuer()
        provides<JobConfigurationEnqueuer>(name = PipelineChildRunJobExecutor.NAME) { enqueuer }
        return enqueuer
    }

    /**
     * Registers a capturing enqueuer for the on-demand run job ([PipelineManualRunJob]) — an on-demand
     * run ([PipelineRunService.start] with an authentication) enqueues one rather than driving inline.
     * Reuses [CapturingChildRunEnqueuer], a generic config capturer, under the manual-run job name.
     */
    private fun registerManualRunEnqueuer(): CapturingChildRunEnqueuer {
        val enqueuer = CapturingChildRunEnqueuer()
        provides<JobConfigurationEnqueuer>(name = PipelineManualRunJobExecutor.NAME) { enqueuer }
        return enqueuer
    }

    /** Captures the child-run jobs a RunPipeline/ForEach enqueues — its [PipelineChildRunJob] configs. */
    private class CapturingChildRunEnqueuer : JobConfigurationEnqueuer {
        override val queueName = "pipeline-child-run"
        val configs = mutableListOf<JsonElement>()

        override suspend fun prepare(configuration: JsonElement, initializer: suspend Job.() -> Unit): Job {
            configs.add(configuration)
            val job = InternalJobConstructor(configuration, DummyExecutor::class)
            job.initializer()
            return job
        }

        override suspend fun enqueue(configuration: JsonElement, initializer: suspend Job.() -> Unit): Job =
            prepare(configuration, initializer)

        override suspend fun enqueueLater(configuration: JsonElement, timeout: Duration, initializer: suspend Job.() -> Unit): Job =
            prepare(configuration, initializer)

        override suspend fun queue(): JobQueue = mockk(relaxed = true)
    }

    private class DummyExecutor : JobExecutor {
        override suspend fun execute() {}
    }

    private fun historyRow() = PipelineRunLogWithName(
        pipelineId = pipeline.id,
        pipelineName = pipeline.name,
        eventName = "e",
        outcome = PipelineRunStatus.OK,
        startedAt = OffsetDateTime.now(),
    )

    private fun suspendedRun(
        id: UUID,
        nodeOutputs: JsonObject = JsonObject(emptyMap()),
        awaiting: List<PipelineRunAwait> = emptyList(),
    ) = PipelineRun(
        id = id,
        pipelineId = pipeline.id,
        status = PipelineRunStatus.SUSPENDED,
        graphSnapshot = JsonObject(emptyMap()),
        nodeOutputs = nodeOutputs,
        awaiting = json.encodeToJsonElement(ListSerializer(PipelineRunAwait.serializer()), awaiting),
    )
}
