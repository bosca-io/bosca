@file:OptIn(ExperimentalUuidApi::class, bosca.di.annotation.InternalDI::class, bosca.core.annotations.Internal::class)

package bosca.pipelines

import bosca.db.ConnectionConfig
import bosca.db.ConnectionFactoryImpl
import bosca.db.ConnectionPool
import bosca.db.asCoroutineContext
import bosca.db.connection
import bosca.db.migrations.FlywayMigration
import bosca.db.transaction
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.graphql.Batch
import bosca.pipelines.configuration.PipelinesRuntimeConfiguration
import bosca.pipelines.model.Pipeline
import bosca.pipelines.model.PipelineEdge
import bosca.pipelines.model.PipelineGraph
import bosca.pipelines.model.NodeExecutionStatus
import bosca.pipelines.model.PipelineRunAwait
import bosca.pipelines.model.PipelineRunStatus
import bosca.pipelines.node.ActionNode
import bosca.pipelines.node.InputNode
import bosca.pipelines.annotation.NodeCategory
import bosca.pipelines.builtin.DelayNode
import bosca.pipelines.builtin.ForEach
import bosca.pipelines.builtin.RunPipelineNode
import bosca.pipelines.builtin.WaitUntilNode
import bosca.pipelines.node.NodeDescriptor
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.NodeOutputSlot
import bosca.pipelines.node.NodePosition
import bosca.pipelines.node.NodeResult
import bosca.pipelines.node.OutputNode
import bosca.pipelines.node.PipelineNode
import bosca.pipelines.node.PipelineValue
import bosca.pipelines.repository.RollbackRepositoryImpl
import bosca.pipelines.repository.NodeExecutionRepositoryImpl
import bosca.pipelines.repository.PipelineRecord
import bosca.pipelines.repository.PipelineRepositoryImpl
import bosca.pipelines.repository.PipelineRunIterationRepositoryImpl
import bosca.pipelines.repository.PipelineRunLogRepositoryImpl
import bosca.pipelines.repository.PipelineRunRepositoryImpl
import bosca.pipelines.repository.PipelinesMigration
import bosca.pipelines.service.PipelineRunResultStore
import bosca.pipelines.service.PipelineRunResultStoreImpl
import bosca.pipelines.service.PipelineRunService
import bosca.pipelines.node.PipelineResumeCorrelation
import bosca.pipelines.service.PipelineRunServiceImpl
import bosca.pipelines.service.PipelineService
import bosca.pipelines.trigger.PipelineChildRunJobExecutor
import bosca.pipelines.trigger.PipelineManualRunJobExecutor
import bosca.pipelines.trigger.PipelineDelayJob
import bosca.pipelines.configuration.PipelinesJobQueueNames
import bosca.security.model.EntityPermission
import bosca.security.model.PermissionAction
import bosca.security.service.SecurityService
import bosca.sharedqueue.jobs.InternalJobConstructor
import bosca.sharedqueue.jobs.Job
import bosca.sharedqueue.jobs.JobConfigurationEnqueuer
import bosca.sharedqueue.jobs.JobExecutor
import bosca.sharedqueue.jobs.JobQueue
import bosca.serialization.OffsetDateTimeSerializer
import bosca.pubsub.PubSubService
import bosca.serialization.UUID
import bosca.serialization.UUIDSerializer
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import bosca.test.resources.SharedPostgreSQLContainer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.uuid.ExperimentalUuidApi

/**
 * Real-Postgres end-to-end proof of the durable suspend/resume state machine:
 * migrations apply, a triggered run is recorded, a suspending node parks it (status flips to
 * SUSPENDED with the node in `awaiting`, checkpoint persisted), and resuming it from the result
 * store drives it to OK and appends run history — all through the real repositories (the
 * `pipeline_run_status` enum cast, jsonb columns, optimistic-locked `updateState`, the result-store
 * upsert). The backing job's completion is driven directly via `service.resume`, exactly as the
 * run job's drive listener does when a backing child bubbles up to terminal status (the queue's
 * at-least-once delivery is covered by sharedqueue's own e2e tests).
 */
class PipelineDurableExecutionEndToEndTest {

    /** An ordinary synchronous node that passes its input through — never suspends. */
    @Serializable
    @SerialName("e2ePassThrough")
    class PassThroughNode(
        override val id: String,
        override val name: String = "",
        override val description: String = "",
        override val position: NodePosition = NodePosition(),
    ) : bosca.pipelines.node.TransformNode() {
        override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue? = inputs.first
    }

    /** A node that fails until [FlakyNode.succeedOnAttempt] — for exercising durable retry. */
    @Serializable
    @SerialName("e2eFlaky")
    class FlakyNode(
        override val id: String,
        override val name: String = "",
        override val description: String = "",
        override val position: NodePosition = NodePosition(),
    ) : ActionNode() {
        override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue? {
            val n = attempts.incrementAndGet()
            if (n < succeedOnAttempt) error("flaky attempt $n")
            return inputs.first
        }
        companion object {
            val attempts = java.util.concurrent.atomic.AtomicInteger(0)
            var succeedOnAttempt = 1
        }
    }

    /** A node that parks a durable run; its enqueue is a no-op (no real queue in this test). */
    @Serializable
    @SerialName("e2eSuspend")
    class SuspendingNode(
        override val id: String,
        override val name: String = "",
        override val description: String = "",
        override val position: NodePosition = NodePosition(),
    ) : ActionNode() {
        override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue? = inputs.first
        override suspend fun run(context: PipelineContext, inputs: NodeInputs): NodeResult =
            NodeResult.Suspend { /* a real node would enqueue its backing job here */ }

        companion object {
            const val AWAIT_KEY = "await-e2e"
        }
    }

    /** A node that always fails — for forcing a run to a terminal failure (saga rollback). */
    @Serializable
    @SerialName("e2eFailing")
    class FailingNode(
        override val id: String,
        override val name: String = "",
        override val description: String = "",
        override val position: NodePosition = NodePosition(),
    ) : ActionNode() {
        override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue? = error("boom")
    }

    /** A pass-through node that records the input it received — used inside a rollback pipeline to prove it ran. */
    @Serializable
    @SerialName("e2eCompProbe")
    class RollbackProbeNode(
        override val id: String,
        override val name: String = "",
        override val description: String = "",
        override val position: NodePosition = NodePosition(),
    ) : ActionNode() {
        override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue? {
            inputs.first?.let { rolledBack.add(it.encode(context.json)) }
            return inputs.first
        }

        companion object {
            /** Inputs every rollback-probe execution saw, across runs in a test. */
            val rolledBack = java.util.concurrent.CopyOnWriteArrayList<JsonElement>()
        }
    }

    companion object {
        private val postgres = SharedPostgreSQLContainer("pgvector/pgvector:pg18").apply {
            withDatabaseName("bosca_pipelines_e2e")
            withReuse(true)
            start()
        }

        private val pool = ConnectionPool(
            ConnectionFactoryImpl(
                ConnectionConfig(url = postgres.jdbcUrl, user = postgres.username, password = postgres.password, maxConnections = 5),
                key = "test",
            ),
        )

        private var schemaInitialized = false
    }

    private val baseModule = SerializersModule {
        contextual(UUID::class, UUIDSerializer())
        contextual(java.time.OffsetDateTime::class, OffsetDateTimeSerializer())
    }
    private val json = Json { ignoreUnknownKeys = true; serializersModule = baseModule }

    /** Node-aware Json that (de)serializes the polymorphic graph including the test node. */
    private val graphJson = Json {
        ignoreUnknownKeys = true
        serializersModule = SerializersModule {
            include(baseModule)
            polymorphic(PipelineNode::class) {
                subclass(InputNode::class, InputNode.serializer())
                subclass(OutputNode::class, OutputNode.serializer())
                subclass(PassThroughNode::class, PassThroughNode.serializer())
                subclass(SuspendingNode::class, SuspendingNode.serializer())
                subclass(FlakyNode::class, FlakyNode.serializer())
                subclass(DelayNode::class, DelayNode.serializer())
                subclass(WaitUntilNode::class, WaitUntilNode.serializer())
                subclass(ForEach::class, ForEach.serializer())
                subclass(RunPipelineNode::class, RunPipelineNode.serializer())
                subclass(FailingNode::class, FailingNode.serializer())
                subclass(RollbackProbeNode::class, RollbackProbeNode.serializer())
                subclass(bosca.pipelines.builtin.ApprovalGateNode::class, bosca.pipelines.builtin.ApprovalGateNode.serializer())
            }
        }
    }

    private val runRepository = PipelineRunRepositoryImpl()
    private val runLogRepository = PipelineRunLogRepositoryImpl()
    private val resultStore = PipelineRunResultStoreImpl(bosca.pipelines.testutil.inMemoryObjectStorage(), json)
    private val nodeExecutionRepository = NodeExecutionRepositoryImpl()
    private val iterationRepository = PipelineRunIterationRepositoryImpl()
    private val rollbackRepository = RollbackRepositoryImpl()
    private val pipelineRepository = PipelineRepositoryImpl()

    private val securityService = mockk<SecurityService>().also {
        coEvery { it.getPrincipalByIdentifier(any()) } returns mockk(relaxed = true)
        coEvery { it.getPrincipalGroups(any<UUID>()) } returns emptyList()
    }

    private val testPipelineService = TestPipelineService(graphJson)

    // Live run-event broadcasting is verified in PipelineRunServiceImplTest; here it's a no-op sink.
    private val pubSub = mockk<PubSubService>(relaxed = true)

    private val service = PipelineRunServiceImpl(
        runRepository = runRepository,
        runLogRepository = runLogRepository,
        resultStore = resultStore,
        nodeExecutionRepository = nodeExecutionRepository,
        iterationRepository = iterationRepository,
        rollbackRepository = rollbackRepository,
        pipelineService = testPipelineService,
        executor = PipelineExecutorImpl(),
        securityService = securityService,
        config = PipelinesRuntimeConfiguration(),
        pubSub = pubSub,
    )

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<ConnectionPool>(singleton = true) { pool }
        provides<Json>(singleton = true) { json }
        if (!schemaInitialized) {
            withDb {
                // Cross-schema prerequisites the pipelines migration's V5 references (provided by the
                // core/security migration in production) — stubbed here so the full schema applies.
                connection().useStatement("create table if not exists groups (id uuid primary key)") { it.execute() }
                connection().useStatement(
                    """
                    do ${'$'}${'$'} begin
                        if not exists (select 1 from pg_type where typname = 'permission_action') then
                            create type permission_action as enum ('view', 'edit', 'manage', 'delete', 'execute');
                        end if;
                    end ${'$'}${'$'};
                    """.trimIndent(),
                ) { it.execute() }
            }
            runBlocking { FlywayMigration(pool).migrate(listOf(PipelinesMigration())) }
            schemaInitialized = true
        }
        withDb {
            transaction {
                connection().useStatement("delete from pipelines.pipeline_run_iteration") { it.execute() }
                connection().useStatement("delete from pipelines.pipeline_run_rollback") { it.execute() }
                connection().useStatement("delete from pipelines.pipeline_run_node") { it.execute() }
                connection().useStatement("delete from pipelines.pipeline_run_result") { it.execute() }
                connection().useStatement("delete from pipelines.pipeline_run") { it.execute() }
                connection().useStatement("delete from pipelines.pipeline_run_log") { it.execute() }
                connection().useStatement("delete from pipelines.pipelines") { it.execute() }
            }
        }
    }

    @AfterTest
    fun teardown() = ProviderRegistry.clear()

    @Test
    fun `a pipeline with no parking node runs straight through to OK in one pass`() {
        val nodes = listOf(
            InputNode(id = "in", acceptedType = "JSON"),
            PassThroughNode(id = "pass"),
            OutputNode(id = "out"),
        )
        val edges = listOf(
            PipelineEdge(id = "e1", source = "in", target = "pass"),
            PipelineEdge(id = "e2", source = "pass", target = "out"),
        )
        val graphElement = graphJson.encodeToJsonElement(PipelineGraph.serializer(), PipelineGraph(nodes, edges))
        val pipelineId = withResult {
            transaction { pipelineRepository.add(PipelineRecord(name = "e2e-straight", acceptedInputType = "JSON", graph = graphElement)).id }
        }
        val pipeline = Pipeline(id = pipelineId, name = "e2e-straight", acceptedInputType = "JSON", nodes = nodes, edges = edges)
        val input = PipelineValue.ofJson(buildJsonObject { put("hello", "world") })

        val run = withResult { service.start(pipeline, input, "test.event", java.time.OffsetDateTime.now()) }!!

        // Completed in a single pass — never suspended, so no checkpoint was written (no overhead).
        val completed = withResult { runRepository.getById(run.id) } ?: error("run row missing")
        assertEquals(PipelineRunStatus.OK, completed.status)
        assertTrue((completed.nodeOutputs as JsonObject).isEmpty(), "a non-suspending run must not checkpoint")
        val history = withResult { runLogRepository.listForPipeline(pipelineId, 0, 10) }
        assertEquals(1, history.size)
        assertEquals(PipelineRunStatus.OK, history.single().outcome)
    }

    @Test
    fun `pipeline tags round-trip through the text array column`() {
        val graphElement = graphJson.encodeToJsonElement(
            PipelineGraph.serializer(),
            PipelineGraph(listOf(InputNode(id = "in", acceptedType = "JSON")), emptyList()),
        )
        val tags = listOf("release", "ci", "nightly")
        val id = withResult {
            transaction {
                pipelineRepository.add(
                    PipelineRecord(name = "e2e-tagged", acceptedInputType = "JSON", tags = tags, graph = graphElement),
                ).id
            }
        }
        // The insert wrote `:tags` into a text[] column; the `returning *` and this reload both
        // decode it back into List<String>, proving the write + read binding end-to-end.
        val reloaded = withResult { pipelineRepository.getById(id) } ?: error("pipeline row missing")
        assertEquals(tags, reloaded.tags, "a text[] tags column must round-trip a non-empty list in order")
    }

    @Test
    fun `a triggered run suspends on a parking node then resumes to OK`() {
        val nodes = listOf(
            InputNode(id = "in", acceptedType = "JSON"),
            SuspendingNode(id = "susp"),
            OutputNode(id = "out"),
        )
        val edges = listOf(
            PipelineEdge(id = "e1", source = "in", target = "susp"),
            PipelineEdge(id = "e2", source = "susp", target = "out"),
        )
        val graphElement = graphJson.encodeToJsonElement(PipelineGraph.serializer(), PipelineGraph(nodes, edges))

        // Persist a pipeline row (FK target for the run), then drive the durable run.
        val pipelineId = withResult {
            transaction {
                pipelineRepository.add(PipelineRecord(name = "e2e", acceptedInputType = "JSON", graph = graphElement)).id
            }
        }
        val pipeline = Pipeline(id = pipelineId, name = "e2e", acceptedInputType = "JSON", nodes = nodes, edges = edges)
        val input = PipelineValue.ofJson(buildJsonObject { put("hello", "world") })

        val run = withResult { service.start(pipeline, input, "test.event", java.time.OffsetDateTime.now()) }!!

        // The run parked: SUSPENDED, awaiting the node, with the upstream checkpointed.
        val suspended = withResult { runRepository.getById(run.id) } ?: error("run row missing")
        assertEquals(PipelineRunStatus.SUSPENDED, suspended.status)
        val awaiting = json.decodeFromJsonElement(ListSerializer(PipelineRunAwait.serializer()), suspended.awaiting as JsonArray)
        assertEquals(listOf("susp"), awaiting.map { it.nodeId })

        // The backing job finished and staged its result; resume drives the run to completion.
        withDb { resultStore.put(run.id, "susp", buildJsonObject { put("written", true) }) }
        withDb { service.resume(run.id, "susp", succeeded = true, error = null) }

        val completed = withResult { runRepository.getById(run.id) } ?: error("run row missing")
        assertEquals(PipelineRunStatus.OK, completed.status)

        // History recorded exactly one finished run, OK.
        val history = withResult { runLogRepository.listForPipeline(pipelineId, 0, 10) }
        assertEquals(1, history.size)
        assertEquals(PipelineRunStatus.OK, history.single().outcome)
    }

    @Test
    fun `a backing-job failure with no error port fails the run`() {
        val nodes = listOf(
            InputNode(id = "in", acceptedType = "JSON"),
            SuspendingNode(id = "susp"),
            OutputNode(id = "out"),
        )
        val edges = listOf(
            PipelineEdge(id = "e1", source = "in", target = "susp"),
            PipelineEdge(id = "e2", source = "susp", target = "out"),
        )
        val graphElement = graphJson.encodeToJsonElement(PipelineGraph.serializer(), PipelineGraph(nodes, edges))
        val pipelineId = withResult {
            transaction { pipelineRepository.add(PipelineRecord(name = "e2e-fail", acceptedInputType = "JSON", graph = graphElement)).id }
        }
        val pipeline = Pipeline(id = pipelineId, name = "e2e-fail", acceptedInputType = "JSON", nodes = nodes, edges = edges)
        val input = PipelineValue.ofJson(buildJsonObject { put("hello", "world") })

        val run = withResult { service.start(pipeline, input, "test.event", java.time.OffsetDateTime.now()) }!!
        withDb { service.resume(run.id, "susp", succeeded = false, error = "boom") }

        val failed = withResult { runRepository.getById(run.id) } ?: error("run row missing")
        assertEquals(PipelineRunStatus.FAILED, failed.status)
        assertTrue(failed.error?.contains("boom") == true, "the failure message should surface")
    }

    @Test
    fun `a fan-out parks both branches and completes after both resume in any order`() {
        // in --> A (suspend) --> out
        //   \--> B (suspend) --/
        val nodes = listOf(
            InputNode(id = "in", acceptedType = "JSON"),
            SuspendingNode(id = "A"),
            SuspendingNode(id = "B"),
            OutputNode(id = "out"),
        )
        val edges = listOf(
            PipelineEdge(id = "e1", source = "in", target = "A"),
            PipelineEdge(id = "e2", source = "in", target = "B"),
            PipelineEdge(id = "e3", source = "A", target = "out"),
            PipelineEdge(id = "e4", source = "B", target = "out"),
        )
        val run = startAndExecute(nodes, edges, "e2e-fanout")

        val suspended = withResult { runRepository.getById(run.id) } ?: error("run row missing")
        assertEquals(PipelineRunStatus.SUSPENDED, suspended.status)
        val parked = json.decodeFromJsonElement(ListSerializer(PipelineRunAwait.serializer()), suspended.awaiting as JsonArray)
        assertEquals(setOf("A", "B"), parked.mapTo(mutableSetOf()) { it.nodeId })

        // Resume B first — A stays parked, the run stays SUSPENDED.
        withDb { resultStore.put(run.id, "B", buildJsonObject { put("b", true) }) }
        withDb { service.resume(run.id, "B", succeeded = true, error = null) }
        assertEquals(PipelineRunStatus.SUSPENDED, (withResult { runRepository.getById(run.id) })?.status)

        // Resume A — both branches resolved, the join runs, the run completes.
        withDb { resultStore.put(run.id, "A", buildJsonObject { put("a", true) }) }
        withDb { service.resume(run.id, "A", succeeded = true, error = null) }
        assertEquals(PipelineRunStatus.OK, (withResult { runRepository.getById(run.id) })?.status)
    }

    @Test
    fun `the sweeper fails a run left suspended past its max lifetime`() {
        val run = startAndExecute(
            listOf(InputNode(id = "in", acceptedType = "JSON"), SuspendingNode(id = "susp"), OutputNode(id = "out")),
            listOf(PipelineEdge(id = "e1", source = "in", target = "susp"), PipelineEdge(id = "e2", source = "susp", target = "out")),
            "e2e-sweep",
        )
        assertEquals(PipelineRunStatus.SUSPENDED, (withResult { runRepository.getById(run.id) })?.status)

        // A negative max lifetime puts the sweep cutoff (now - lifetime) one minute in the FUTURE, so
        // every currently-suspended run counts as stuck. A zero-minute lifetime made the cutoff exactly
        // "now" and raced two different clocks — the run's modified_at is set by Postgres `now()` (the
        // DB container's clock) while the cutoff is the JVM host clock; under Docker the container clock
        // routinely drifts ahead, leaving the just-suspended run newer than the cutoff and unswept.
        val sweeper = PipelineRunServiceImpl(
            runRepository, runLogRepository, resultStore, nodeExecutionRepository, iterationRepository,
            rollbackRepository, testPipelineService, PipelineExecutorImpl(), securityService,
            PipelinesRuntimeConfiguration(suspendedRunMaxLifetimeMinutes = -1), pubSub,
        )
        val swept = withResult { sweeper.sweepStuckSuspended() }
        assertTrue(swept >= 1)
        val failed = withResult { runRepository.getById(run.id) } ?: error("run row missing")
        assertEquals(PipelineRunStatus.FAILED, failed.status)
        assertTrue(failed.error?.contains("suspended longer than") == true)
    }

    @Test
    fun `the sweeper never reaps a run parked on an Approval Gate — people may wait as long as they want`() {
        // The gate's doSuspend stages its inbound value through the provided result store.
        val resultStoreForNode: PipelineRunResultStore = resultStore
        provides<PipelineRunResultStore> { resultStoreForNode }
        val run = startAndExecute(
            listOf(
                InputNode(id = "in", acceptedType = "JSON"),
                bosca.pipelines.builtin.ApprovalGateNode(id = "gate", prompt = "Promote?"),
                OutputNode(id = "out"),
            ),
            listOf(
                PipelineEdge(id = "e1", source = "in", target = "gate"),
                PipelineEdge(id = "e2", source = "gate", target = "out"),
            ),
            "e2e-gate-sweep",
        )
        assertEquals(PipelineRunStatus.SUSPENDED, (withResult { runRepository.getById(run.id) })?.status)

        // Parking on a gate announces it, so notification surfaces can reach the approvers.
        io.mockk.coVerify(atLeast = 1) {
            pubSub.publish(
                bosca.pipelines.model.PipelineAwaitingApproval.CHANNEL,
                bosca.pipelines.model.PipelineAwaitingApproval.serializer(),
                match<bosca.pipelines.model.PipelineAwaitingApproval> {
                    it.runId == run.id && it.nodeId == "gate" && it.prompt == "Promote?"
                },
            )
        }

        // Same future-cutoff trick as the sweep test: every suspended run counts as stuck — except gates.
        val sweeper = PipelineRunServiceImpl(
            runRepository, runLogRepository, resultStore, nodeExecutionRepository, iterationRepository,
            rollbackRepository, testPipelineService, PipelineExecutorImpl(), securityService,
            PipelinesRuntimeConfiguration(suspendedRunMaxLifetimeMinutes = -1), pubSub,
        )
        withResult { sweeper.sweepStuckSuspended() }
        val after = withResult { runRepository.getById(run.id) } ?: error("run row missing")
        assertEquals(PipelineRunStatus.SUSPENDED, after.status, "a gate-parked run must survive the sweep")

        // An approval still resumes it: the staged inbound value flows on and the run completes.
        withDb { service.resume(run.id, "gate", succeeded = true, error = null) }
        assertEquals(PipelineRunStatus.OK, (withResult { runRepository.getById(run.id) })?.status)
    }

    @Test
    fun `cancelling a suspended run ignores a later resume`() {
        val run = startAndExecute(
            listOf(InputNode(id = "in", acceptedType = "JSON"), SuspendingNode(id = "susp"), OutputNode(id = "out")),
            listOf(PipelineEdge(id = "e1", source = "in", target = "susp"), PipelineEdge(id = "e2", source = "susp", target = "out")),
            "e2e-cancel",
        )

        withDb { service.cancel(run.id, "operator stopped it") }
        assertEquals(PipelineRunStatus.CANCELLED, (withResult { runRepository.getById(run.id) })?.status)

        // A resume delivered after cancellation is ignored — the run stays CANCELLED.
        withDb { resultStore.put(run.id, "susp", buildJsonObject { put("late", true) }) }
        withDb { service.resume(run.id, "susp", succeeded = true, error = null) }
        assertEquals(PipelineRunStatus.CANCELLED, (withResult { runRepository.getById(run.id) })?.status)
    }

    @Test
    fun `a classified alreadyExists outcome routes down its wired error branch to OK`() {
        // in --> susp --[alreadyExists]--> out   (the success "out" port is intentionally unwired)
        val nodes = listOf(
            InputNode(id = "in", acceptedType = "JSON"),
            SuspendingNode(id = "susp"),
            OutputNode(id = "out"),
        )
        val edges = listOf(
            PipelineEdge(id = "e1", source = "in", target = "susp"),
            PipelineEdge(id = "e2", source = "susp", target = "out", sourcePort = "alreadyExists"),
        )
        val run = startAndExecute(nodes, edges, "e2e-routing")
        assertEquals(PipelineRunStatus.SUSPENDED, (withResult { runRepository.getById(run.id) })?.status)

        // The backing job staged its result on the alreadyExists port (a recovered 409).
        withDb { resultStore.put(run.id, "susp", buildJsonObject { put("id", "c1") }, port = "alreadyExists") }
        withDb { service.resume(run.id, "susp", succeeded = true, error = null) }

        // It routed down the wired alreadyExists branch and completed — not failed.
        assertEquals(PipelineRunStatus.OK, (withResult { runRepository.getById(run.id) })?.status)
    }

    @Test
    fun `listActive surfaces in-flight runs and drops terminal ones`() {
        val run = startAndExecute(
            listOf(InputNode(id = "in", acceptedType = "JSON"), SuspendingNode(id = "susp"), OutputNode(id = "out")),
            listOf(PipelineEdge(id = "e1", source = "in", target = "susp"), PipelineEdge(id = "e2", source = "susp", target = "out")),
            "e2e-active",
        )
        // The suspended run shows up in the Studio-facing active-runs query.
        assertTrue(withResult { service.listActive(0, 50) }.any { it.id == run.id }, "a suspended run should be active")

        withDb { resultStore.put(run.id, "susp", buildJsonObject { put("x", 1) }) }
        withDb { service.resume(run.id, "susp", succeeded = true, error = null) }

        // Once it completes it is no longer active.
        assertTrue(withResult { service.listActive(0, 50) }.none { it.id == run.id }, "a completed run should not be active")
    }

    @Test
    fun `a straight-through run records a per-node timeline with timing and status`() {
        val nodes = listOf(
            InputNode(id = "in", acceptedType = "JSON"),
            PassThroughNode(id = "pass"),
            OutputNode(id = "out"),
        )
        val edges = listOf(
            PipelineEdge(id = "e1", source = "in", target = "pass"),
            PipelineEdge(id = "e2", source = "pass", target = "out"),
        )
        val run = startAndExecute(nodes, edges, "e2e-timeline")
        assertEquals(PipelineRunStatus.OK, (withResult { runRepository.getById(run.id) })?.status)

        val timeline = withResult { nodeExecutionRepository.listForRun(run.id) }
        // The two executed nodes (pass, out) each recorded an OK event; the InputNode is the seed, not run.
        assertEquals(setOf("pass", "out"), timeline.map { it.nodeId }.toSet())
        timeline.forEach {
            assertEquals(NodeExecutionStatus.OK, it.status)
            assertTrue(it.durationMs >= 0)
        }
    }

    @Test
    fun `a suspend then resume records both lifecycle events for the node`() {
        val nodes = listOf(
            InputNode(id = "in", acceptedType = "JSON"),
            SuspendingNode(id = "susp"),
            OutputNode(id = "out"),
        )
        val edges = listOf(
            PipelineEdge(id = "e1", source = "in", target = "susp"),
            PipelineEdge(id = "e2", source = "susp", target = "out"),
        )
        val run = startAndExecute(nodes, edges, "e2e-timeline-suspend")

        // After the suspend, the parking node has exactly one SUSPENDED event (and nothing for `out` yet).
        val parked = withResult { nodeExecutionRepository.listForRun(run.id) }
        assertEquals(listOf(NodeExecutionStatus.SUSPENDED), parked.filter { it.nodeId == "susp" }.map { it.status })
        assertTrue(parked.none { it.nodeId == "out" }, "downstream hasn't run while parked")

        withDb { resultStore.put(run.id, "susp", buildJsonObject { put("ok", true) }) }
        withDb { service.resume(run.id, "susp", succeeded = true, error = null) }
        assertEquals(PipelineRunStatus.OK, (withResult { runRepository.getById(run.id) })?.status)

        // The node's full lifecycle is now visible: SUSPENDED (parked) then OK (resumed); `out` ran OK.
        val full = withResult { nodeExecutionRepository.listForRun(run.id) }
        assertEquals(
            listOf(NodeExecutionStatus.SUSPENDED, NodeExecutionStatus.OK),
            full.filter { it.nodeId == "susp" }.map { it.status },
        )
        assertEquals(listOf(NodeExecutionStatus.OK), full.filter { it.nodeId == "out" }.map { it.status })
    }

    @Test
    fun `a durable run retries a flaky node to success and records each attempt`() {
        FlakyNode.attempts.set(0)
        FlakyNode.succeedOnAttempt = 2 // fail once, then succeed
        val nodes = listOf(
            InputNode(id = "in", acceptedType = "JSON"),
            FlakyNode(id = "flaky").apply { retry = bosca.pipelines.model.RetryPolicy(maxAttempts = 3) },
            OutputNode(id = "out"),
        )
        val edges = listOf(
            PipelineEdge(id = "e1", source = "in", target = "flaky"),
            PipelineEdge(id = "e2", source = "flaky", target = "out"),
        )
        val run = startAndExecute(nodes, edges, "e2e-retry")

        // The retry carried the run to completion despite the first failure.
        assertEquals(PipelineRunStatus.OK, (withResult { runRepository.getById(run.id) })?.status)
        assertEquals(2, FlakyNode.attempts.get())
        // The timeline persisted both the failed attempt and the successful run for the node.
        val flaky = withResult { nodeExecutionRepository.listForRun(run.id) }.filter { it.nodeId == "flaky" }.map { it.status }
        assertEquals(listOf(NodeExecutionStatus.FAILED, NodeExecutionStatus.OK), flaky)
    }

    @Test
    fun `node metrics aggregate executions and failures across a pipeline's runs`() {
        val nodes = listOf(
            InputNode(id = "in", acceptedType = "JSON"),
            SuspendingNode(id = "susp"),
            OutputNode(id = "out"),
        )
        val edges = listOf(
            PipelineEdge(id = "e1", source = "in", target = "susp"),
            PipelineEdge(id = "e2", source = "susp", target = "out"),
        )
        val graphElement = graphJson.encodeToJsonElement(PipelineGraph.serializer(), PipelineGraph(nodes, edges))
        val pipelineId = withResult {
            transaction { pipelineRepository.add(PipelineRecord(name = "e2e-metrics", acceptedInputType = "JSON", graph = graphElement)).id }
        }
        val pipeline = Pipeline(id = pipelineId, name = "e2e-metrics", acceptedInputType = "JSON", nodes = nodes, edges = edges)
        val input = PipelineValue.ofJson(buildJsonObject { put("x", 1) })

        // Run 1: park, then resume OK → susp completes OK, out runs OK.
        val run1 = withResult { service.start(pipeline, input, "test.event", java.time.OffsetDateTime.now()) }!!
        withDb { resultStore.put(run1.id, "susp", buildJsonObject { put("ok", true) }) }
        withDb { service.resume(run1.id, "susp", succeeded = true, error = null) }

        // Run 2: park, then resume FAILED (no error port wired) → susp FAILS, out never runs.
        val run2 = withResult { service.start(pipeline, input, "test.event", java.time.OffsetDateTime.now()) }!!
        withDb { service.resume(run2.id, "susp", succeeded = false, error = "boom") }

        val metrics = withResult { nodeExecutionRepository.metricsForPipeline(pipelineId) }.associateBy { it.nodeId }
        // susp completed twice (one OK, one FAILED); SUSPENDED lifecycle events are excluded from metrics.
        assertEquals(2L, metrics["susp"]?.executions)
        assertEquals(1L, metrics["susp"]?.failures)
        // out only ran in the successful run (run 2 failed before reaching it).
        assertEquals(1L, metrics["out"]?.executions)
        assertEquals(0L, metrics["out"]?.failures)
        assertTrue((metrics["susp"]?.p95Ms ?: -1.0) >= 0.0)
    }

    @Test
    fun `a real Delay node parks the run and a fired scheduled resume drives it through with the value`() {
        // The real DelayNode: durable → suspend, staging the inbound value in the (Postgres) result
        // store and scheduling a delayed PipelineDelayJob. We capture that job and fire it exactly
        // as the queue's delayed delivery would — closing the timer loop end to end on real Postgres.
        val resultStoreForNode: PipelineRunResultStore = resultStore
        provides<PipelineRunResultStore> { resultStoreForNode }
        // Capture the delay child at the queue the generated PipelineDelayJob.enqueue(context, nodeId) targets.
        val captured = slot<Job>()
        val delayQueue = mockk<JobQueue>(relaxed = true)
        coEvery { delayQueue.enqueue(capture(captured)) } returns UUID.random()
        provides<JobQueue>(name = PipelinesJobQueueNames.jobQueue) { delayQueue }

        val nodes = listOf(
            InputNode(id = "in", acceptedType = "JSON"),
            DelayNode(id = "delay", delaySeconds = 30),
            OutputNode(id = "out"),
        )
        val edges = listOf(
            PipelineEdge(id = "e1", source = "in", target = "delay"),
            PipelineEdge(id = "e2", source = "delay", target = "out"),
        )
        val before = System.currentTimeMillis()
        val run = startAndExecute(nodes, edges, "e2e-delay")

        // Parked on the delay, with the inbound value already staged by the node's own thunk.
        val suspended = withResult { runRepository.getById(run.id) } ?: error("run row missing")
        assertEquals(PipelineRunStatus.SUSPENDED, suspended.status)
        val awaiting = json.decodeFromJsonElement(ListSerializer(PipelineRunAwait.serializer()), suspended.awaiting as JsonArray)
        assertEquals(listOf("delay"), awaiting.map { it.nodeId })
        assertTrue(withResult { resultStore.get(run.id, "delay") } != null, "the delay staged its pass-through value")

        // The node enqueued a delay child waking in ~30s, with the run/node correlation in its context.
        val delayJob = json.decodeFromJsonElement(PipelineDelayJob.serializer(), captured.captured.getDefinition())
        assertTrue(delayJob.wakeAtEpochMillis > before + 25_000, "wakes ~30s out, got ${delayJob.wakeAtEpochMillis - before}ms")
        val correlation = json.decodeFromJsonElement(PipelineResumeCorrelation.serializer(), captured.captured.getContext())
        assertEquals(run.id, correlation.runId)
        assertEquals("delay", correlation.nodeId)

        // Fire the resume the delay child's completion would drive — the run completes, passing the
        // staged value through on the implicit output.
        withDb { service.resume(correlation.runId, correlation.nodeId, true, null) }
        val completed = withResult { runRepository.getById(run.id) } ?: error("run row missing")
        assertEquals(PipelineRunStatus.OK, completed.status)
        val history = withResult { runLogRepository.listForPipeline(run.pipelineId, 0, 10) }
        assertEquals(PipelineRunStatus.OK, history.single().outcome)
    }

    @Test
    fun `a real WaitUntil node resolves a target from an inbound field, parks, and resumes through`() {
        val resultStoreForNode: PipelineRunResultStore = resultStore
        provides<PipelineRunResultStore> { resultStoreForNode }
        // Capture the delay child at the queue the generated PipelineDelayJob.enqueue(context, nodeId) targets.
        val captured = slot<Job>()
        val delayQueue = mockk<JobQueue>(relaxed = true)
        coEvery { delayQueue.enqueue(capture(captured)) } returns UUID.random()
        provides<JobQueue>(name = PipelinesJobQueueNames.jobQueue) { delayQueue }

        val nodes = listOf(
            InputNode(id = "in", acceptedType = "JSON"),
            WaitUntilNode(id = "wait", untilField = "when"),
            OutputNode(id = "out"),
        )
        val edges = listOf(
            PipelineEdge(id = "e1", source = "in", target = "wait"),
            PipelineEdge(id = "e2", source = "wait", target = "out"),
        )
        // Drive with a future target carried in the inbound value's `when` field.
        val before = System.currentTimeMillis()
        val target = java.time.OffsetDateTime.now().plusHours(1)
        val graphElement = graphJson.encodeToJsonElement(PipelineGraph.serializer(), PipelineGraph(nodes, edges))
        val pipelineId = withResult {
            transaction { pipelineRepository.add(PipelineRecord(name = "e2e-waituntil", acceptedInputType = "JSON", graph = graphElement)).id }
        }
        val pipeline = Pipeline(id = pipelineId, name = "e2e-waituntil", acceptedInputType = "JSON", nodes = nodes, edges = edges)
        val input = PipelineValue.ofJson(buildJsonObject { put("when", target.toString()) })
        val run = withResult { service.start(pipeline, input, "test.event", java.time.OffsetDateTime.now()) }!!

        // Parked on the wait, having resolved the inbound field's timestamp into a delayed resume.
        val suspended = withResult { runRepository.getById(run.id) } ?: error("run row missing")
        assertEquals(PipelineRunStatus.SUSPENDED, suspended.status)
        val awaiting = json.decodeFromJsonElement(ListSerializer(PipelineRunAwait.serializer()), suspended.awaiting as JsonArray)
        assertEquals(listOf("wait"), awaiting.map { it.nodeId })
        // The delay child wakes roughly an hour out (the resolved target), proving field resolution drove it.
        val delayJob = json.decodeFromJsonElement(PipelineDelayJob.serializer(), captured.captured.getDefinition())
        assertTrue(delayJob.wakeAtEpochMillis > before + 3_000_000, "should wait ~1h, got ${delayJob.wakeAtEpochMillis - before}ms")

        val correlation = json.decodeFromJsonElement(PipelineResumeCorrelation.serializer(), captured.captured.getContext())
        withDb { service.resume(correlation.runId, correlation.nodeId, true, null) }
        assertEquals(PipelineRunStatus.OK, (withResult { runRepository.getById(run.id) })?.status)
    }

    @Test
    fun `a durable ForEach fans each item into a child run, parks the parent, and joins per-item results`() {
        // The ForEach node's suspend thunk resolves the run service to start the per-item children.
        provides<PipelineRunService> { service }
        // The fan-out enqueues a child-run job per item; there is no runner here, so the test drives
        // each child explicitly via driveExistingRun below.
        provides<JobConfigurationEnqueuer>(name = PipelineChildRunJobExecutor.NAME) { CapturingChildRunEnqueuer() }

        // Body: in -> susp -> out. Each item SUSPENDS, so the iteration cannot run inline — it must
        // fan out into durable child runs, proving true per-item suspend/resume.
        val (bodyId, _) = persistPipeline(
            "foreach-body",
            listOf(InputNode(id = "in", acceptedType = "JSON"), SuspendingNode(id = "susp"), OutputNode(id = "out")),
            listOf(PipelineEdge(id = "be1", source = "in", target = "susp"), PipelineEdge(id = "be2", source = "susp", target = "out")),
            register = true,
        )

        // Parent: in -> each(ForEach over the inbound array) -> out.
        val (_, parent) = persistPipeline(
            "foreach-parent",
            listOf(InputNode(id = "in", acceptedType = "JSON"), ForEach(id = "each", pipelineId = bodyId), OutputNode(id = "out")),
            listOf(PipelineEdge(id = "e1", source = "in", target = "each"), PipelineEdge(id = "e2", source = "each", target = "out")),
        )

        val input = PipelineValue.ofJson(buildJsonArray { add(buildJsonObject { put("i", 0) }); add(buildJsonObject { put("i", 1) }) })
        val run = withResult { service.start(parent, input, "test.event", java.time.OffsetDateTime.now()) }!!

        // The parent parked on the single ForEach await. With the node's default maxConcurrency of 1
        // only the FIRST item's child may start — the bound is what turns a dependency-ordered item
        // list into an actual order (the release relay's providers-before-consumers builds).
        val parked = withResult { runRepository.getById(run.id) } ?: error("parent run row missing")
        assertEquals(PipelineRunStatus.SUSPENDED, parked.status)
        val parentAwaits = json.decodeFromJsonElement(ListSerializer(PipelineRunAwait.serializer()), parked.awaiting as JsonArray)
        assertEquals(listOf("each"), parentAwaits.map { it.nodeId }, "the parent parks on one ForEach await")

        val firstChildIds = childRunIds(run.id)
        assertEquals(1, firstChildIds.size, "only item 0 starts under maxConcurrency 1")
        // The Studio run detail reaches these children through the service (PipelineRunState.childRuns).
        val firstChildren = withResult { service.listChildren(run.id, "each") }
        assertEquals(firstChildIds, firstChildren.map { it.id }, "listChildren returns the started children in item order")
        assertEquals(listOf(0), firstChildren.map { it.itemIndex }, "each child carries its item index")
        // The child's run job would drive it; with no runner, drive it to its own suspend point. A
        // SUSPENDED child holds its concurrency slot — item 1 must still not exist.
        withDb { service.process(firstChildIds[0]) }
        assertEquals(PipelineRunStatus.SUSPENDED, withResult { runRepository.getById(firstChildIds[0]) }?.status)
        assertEquals(1, childRunIds(run.id).size, "a suspended item holds its slot — item 1 has not started")

        // Resume item 0 — its completion starts item 1; the parent stays parked until every item reports.
        withDb { resultStore.put(firstChildIds[0], "susp", buildJsonObject { put("done", 0) }) }
        withDb { service.resume(firstChildIds[0], "susp", succeeded = true, error = null) }
        assertEquals(PipelineRunStatus.SUSPENDED, withResult { runRepository.getById(run.id) }?.status, "parent waits for all items")
        val childIds = childRunIds(run.id)
        assertEquals(2, childIds.size, "item 0's completion started item 1")
        assertEquals(
            listOf(0, 1),
            withResult { service.listChildren(run.id, "each") }.map { it.itemIndex },
            "the lazily-started child carries the next item index",
        )

        // Drive + resume the last item — the iteration completes, joins the per-item outputs IN INDEX
        // ORDER, and resumes the parent to completion.
        withDb { service.process(childIds[1]) }
        withDb { resultStore.put(childIds[1], "susp", buildJsonObject { put("done", 1) }) }
        withDb { service.resume(childIds[1], "susp", succeeded = true, error = null) }

        val completed = withResult { runRepository.getById(run.id) } ?: error("parent run row missing")
        assertEquals(PipelineRunStatus.OK, completed.status)
        assertEquals(
            buildJsonArray { add(buildJsonObject { put("done", 0) }); add(buildJsonObject { put("done", 1) }) },
            (completed.nodeOutputs as JsonObject)["each"],
            "ForEach emits the per-item results as an ordered array",
        )
        childIds.forEach { assertEquals(PipelineRunStatus.OK, withResult { runRepository.getById(it) }?.status) }
    }

    @Test
    fun `a durable ForEach fails fast when an item fails and continueOnError is off`() {
        provides<PipelineRunService> { service }
        provides<JobConfigurationEnqueuer>(name = PipelineChildRunJobExecutor.NAME) { CapturingChildRunEnqueuer() }
        val (bodyId, _) = persistPipeline(
            "foreach-ff-body",
            listOf(InputNode(id = "in", acceptedType = "JSON"), SuspendingNode(id = "susp"), OutputNode(id = "out")),
            listOf(PipelineEdge(id = "be1", source = "in", target = "susp"), PipelineEdge(id = "be2", source = "susp", target = "out")),
            register = true,
        )
        // continueOnError defaults to false → one bad item fails the whole iteration.
        val (_, parent) = persistPipeline(
            "foreach-ff-parent",
            listOf(InputNode(id = "in", acceptedType = "JSON"), ForEach(id = "each", pipelineId = bodyId), OutputNode(id = "out")),
            listOf(PipelineEdge(id = "e1", source = "in", target = "each"), PipelineEdge(id = "e2", source = "each", target = "out")),
        )

        val input = PipelineValue.ofJson(buildJsonArray { add(buildJsonObject { put("i", 0) }); add(buildJsonObject { put("i", 1) }) })
        val run = withResult { service.start(parent, input, "test.event", java.time.OffsetDateTime.now()) }!!
        val childIds = childRunIds(run.id)
        assertEquals(1, childIds.size, "only item 0 starts under maxConcurrency 1")
        // No runner here: drive the child to its own suspend point before resuming it.
        withDb { service.process(childIds[0]) }

        // Item 0's backing work FAILS (no wired error port) → fail-fast fails the parent, and the
        // not-yet-started item 1 never starts (a dependency consumer must not build when its
        // provider's item failed).
        withDb { service.resume(childIds[0], "susp", succeeded = false, error = "boom") }

        val failed = withResult { runRepository.getById(run.id) } ?: error("parent run row missing")
        assertEquals(PipelineRunStatus.FAILED, failed.status)
        assertTrue(failed.error?.contains("item 0") == true, "the failure names the offending item index")
        assertTrue(failed.error?.contains("boom") == true, "the failure surfaces the item's error")
        assertEquals(1, childRunIds(run.id).size, "the failed iteration never started item 1")
    }

    @Test
    fun `a durable ForEach with continueOnError collects an error marker and still completes`() {
        provides<PipelineRunService> { service }
        provides<JobConfigurationEnqueuer>(name = PipelineChildRunJobExecutor.NAME) { CapturingChildRunEnqueuer() }
        val (bodyId, _) = persistPipeline(
            "foreach-coe-body",
            listOf(InputNode(id = "in", acceptedType = "JSON"), SuspendingNode(id = "susp"), OutputNode(id = "out")),
            listOf(PipelineEdge(id = "be1", source = "in", target = "susp"), PipelineEdge(id = "be2", source = "susp", target = "out")),
            register = true,
        )
        val (_, parent) = persistPipeline(
            "foreach-coe-parent",
            listOf(
                InputNode(id = "in", acceptedType = "JSON"),
                ForEach(id = "each", pipelineId = bodyId, continueOnError = true),
                OutputNode(id = "out"),
            ),
            listOf(PipelineEdge(id = "e1", source = "in", target = "each"), PipelineEdge(id = "e2", source = "each", target = "out")),
        )

        val input = PipelineValue.ofJson(buildJsonArray { add(buildJsonObject { put("i", 0) }); add(buildJsonObject { put("i", 1) }) })
        val run = withResult { service.start(parent, input, "test.event", java.time.OffsetDateTime.now()) }!!
        val firstChildIds = childRunIds(run.id)
        assertEquals(1, firstChildIds.size, "only item 0 starts under maxConcurrency 1")
        // No runner here: drive the child to its own suspend point before resuming it.
        withDb { service.process(firstChildIds[0]) }

        // Item 0 fails, item 1 succeeds — with continueOnError the failed item becomes an {error,index}
        // marker in its slot, the tolerated failure still starts item 1, and the iteration completes.
        withDb { service.resume(firstChildIds[0], "susp", succeeded = false, error = "boom") }
        assertEquals(PipelineRunStatus.SUSPENDED, withResult { runRepository.getById(run.id) }?.status, "a tolerated failure does not fail-fast")
        val childIds = childRunIds(run.id)
        assertEquals(2, childIds.size, "the tolerated failure still started item 1")
        withDb { service.process(childIds[1]) }
        withDb { resultStore.put(childIds[1], "susp", buildJsonObject { put("done", 1) }) }
        withDb { service.resume(childIds[1], "susp", succeeded = true, error = null) }

        val completed = withResult { runRepository.getById(run.id) } ?: error("parent run row missing")
        assertEquals(PipelineRunStatus.OK, completed.status)
        val joined = (completed.nodeOutputs as JsonObject)["each"] as JsonArray
        assertEquals(2, joined.size)
        assertTrue((joined[0] as JsonObject).containsKey("error"), "the failed item's slot holds an error marker")
        assertEquals(buildJsonObject { put("done", 1) }, joined[1], "the surviving item keeps its result in place")
    }

    @Test
    fun `a durable RunPipeline runs the child as a durable run, parks the parent, and passes its output through`() {
        provides<PipelineRunService> { service }
        provides<JobConfigurationEnqueuer>(name = PipelineChildRunJobExecutor.NAME) { CapturingChildRunEnqueuer() }

        // Child body: in -> susp -> out. It SUSPENDS, so the parent must run it as a durable child run
        // (not inline) and park until it resumes — the requireCompleted-everywhere fix.
        val (bodyId, _) = persistPipeline(
            "runpipeline-body",
            listOf(InputNode(id = "in", acceptedType = "JSON"), SuspendingNode(id = "susp"), OutputNode(id = "out")),
            listOf(PipelineEdge(id = "be1", source = "in", target = "susp"), PipelineEdge(id = "be2", source = "susp", target = "out")),
            register = true,
        )

        // Parent: in -> run(RunPipeline → body) -> out.
        val (_, parent) = persistPipeline(
            "runpipeline-parent",
            listOf(InputNode(id = "in", acceptedType = "JSON"), RunPipelineNode(id = "run", pipelineId = bodyId), OutputNode(id = "out")),
            listOf(PipelineEdge(id = "e1", source = "in", target = "run"), PipelineEdge(id = "e2", source = "run", target = "out")),
        )

        val input = PipelineValue.ofJson(buildJsonObject { put("hello", "world") })
        val run = withResult { service.start(parent, input, "test.event", java.time.OffsetDateTime.now()) }!!

        // Parent parked on the RunPipeline await; exactly one child run started and parked on its suspend.
        val parked = withResult { runRepository.getById(run.id) } ?: error("parent run row missing")
        assertEquals(PipelineRunStatus.SUSPENDED, parked.status)
        assertEquals(listOf("run"), json.decodeFromJsonElement(ListSerializer(PipelineRunAwait.serializer()), parked.awaiting as JsonArray).map { it.nodeId })
        val childIds = childRunIds(run.id)
        assertEquals(1, childIds.size, "one durable child run")
        // No runner here: drive the child to its own suspend point.
        withDb { service.process(childIds[0]) }
        assertEquals(PipelineRunStatus.SUSPENDED, withResult { runRepository.getById(childIds[0]) }?.status)

        // Resume the child — it completes, reports its output, and the parent resumes with that output.
        withDb { resultStore.put(childIds[0], "susp", buildJsonObject { put("childResult", 42) }) }
        withDb { service.resume(childIds[0], "susp", succeeded = true, error = null) }

        val completed = withResult { runRepository.getById(run.id) } ?: error("parent run row missing")
        assertEquals(PipelineRunStatus.OK, completed.status)
        assertEquals(
            buildJsonObject { put("childResult", 42) },
            (completed.nodeOutputs as JsonObject)["run"],
            "the child's Output value passes through as the RunPipeline node's output",
        )
    }

    @Test
    fun `a durable RunPipeline whose child fails fails the parent run`() {
        provides<PipelineRunService> { service }
        provides<JobConfigurationEnqueuer>(name = PipelineChildRunJobExecutor.NAME) { CapturingChildRunEnqueuer() }
        val (bodyId, _) = persistPipeline(
            "runpipeline-fail-body",
            listOf(InputNode(id = "in", acceptedType = "JSON"), SuspendingNode(id = "susp"), OutputNode(id = "out")),
            listOf(PipelineEdge(id = "be1", source = "in", target = "susp"), PipelineEdge(id = "be2", source = "susp", target = "out")),
            register = true,
        )
        val (_, parent) = persistPipeline(
            "runpipeline-fail-parent",
            listOf(InputNode(id = "in", acceptedType = "JSON"), RunPipelineNode(id = "run", pipelineId = bodyId), OutputNode(id = "out")),
            listOf(PipelineEdge(id = "e1", source = "in", target = "run"), PipelineEdge(id = "e2", source = "run", target = "out")),
        )
        val input = PipelineValue.ofJson(buildJsonObject { put("hello", "world") })
        val run = withResult { service.start(parent, input, "test.event", java.time.OffsetDateTime.now()) }!!
        val childIds = childRunIds(run.id)
        // No runner here: drive the child to its own suspend point before resuming it.
        withDb { service.process(childIds[0]) }

        // The child's backing work fails (no wired error port) → the child fails → the parent fails.
        withDb { service.resume(childIds[0], "susp", succeeded = false, error = "kaboom") }

        val failed = withResult { runRepository.getById(run.id) } ?: error("parent run row missing")
        assertEquals(PipelineRunStatus.FAILED, failed.status)
        assertTrue(failed.error?.contains("sub-pipeline failed") == true, "the parent failure names the sub-pipeline cause")
    }

    @Test
    fun `a failed run lands in the dead-letter queue and can be replayed`() {
        // A replay (restart) is driven by an enqueued run job, like a manual run; register its enqueuer.
        provides<JobConfigurationEnqueuer>(name = PipelineManualRunJobExecutor.NAME) { CapturingChildRunEnqueuer() }
        val (_, pipeline) = persistPipeline(
            "deadletter",
            listOf(InputNode(id = "in", acceptedType = "JSON"), SuspendingNode(id = "susp"), OutputNode(id = "out")),
            listOf(PipelineEdge(id = "e1", source = "in", target = "susp"), PipelineEdge(id = "e2", source = "susp", target = "out")),
            register = true,
        )
        val input = PipelineValue.ofJson(buildJsonObject { put("hello", "world") })
        val r1 = withResult { service.start(pipeline, input, "test.event", java.time.OffsetDateTime.now()) }!!
        // The backing work fails with no wired error port → the run terminally FAILS (dead-lettered).
        withDb { service.resume(r1.id, "susp", succeeded = false, error = "boom") }
        assertEquals(PipelineRunStatus.FAILED, withResult { runRepository.getById(r1.id) }?.status)

        // It shows up in the dead-letter queue.
        assertTrue(withResult { service.listDeadLetter(0, 50) }.any { it.id == r1.id }, "failed run is dead-lettered")

        // Replay starts a NEW durable run from the same seed input, driven by an enqueued run job (so a
        // suspending replay resumes durably rather than orphaning). With no runner here, drive it to its
        // suspend point as a real worker would when it dequeues the run job.
        val r2 = withResult { service.restart(r1.id) } ?: error("restart returned null")
        assertTrue(r2.id != r1.id, "restart is a fresh run")
        assertEquals("restart", r2.eventName)
        withDb { service.process(r2.id) }
        assertEquals(PipelineRunStatus.SUSPENDED, withResult { runRepository.getById(r2.id) }?.status)

        // The replay is a real durable run — resume it to completion.
        withDb { resultStore.put(r2.id, "susp", buildJsonObject { put("ok", true) }) }
        withDb { service.resume(r2.id, "susp", succeeded = true, error = null) }
        assertEquals(PipelineRunStatus.OK, withResult { runRepository.getById(r2.id) }?.status)
    }

    @Test
    fun `the retention sweep soft-deletes terminal runs and deletes old history`() {
        val (pipelineId, pipeline) = persistPipeline(
            "retention",
            listOf(InputNode(id = "in", acceptedType = "JSON"), PassThroughNode(id = "pass"), OutputNode(id = "out")),
            listOf(PipelineEdge(id = "e1", source = "in", target = "pass"), PipelineEdge(id = "e2", source = "pass", target = "out")),
        )
        val input = PipelineValue.ofJson(buildJsonObject { put("x", 1) })
        val run = withResult { service.start(pipeline, input, "test.event", java.time.OffsetDateTime.now()) }!! // completes OK → terminal + history
        assertEquals(PipelineRunStatus.OK, withResult { runRepository.getById(run.id) }?.status)
        assertEquals(1, withResult { runLogRepository.listForPipeline(pipelineId, 0, 10) }.size)

        // A purger with zero-day retention treats every terminal run + finished history row as expired.
        val purger = PipelineRunServiceImpl(
            runRepository, runLogRepository, resultStore, nodeExecutionRepository, iterationRepository,
            rollbackRepository, testPipelineService, PipelineExecutorImpl(), securityService,
            PipelinesRuntimeConfiguration(runStateRetentionDays = 0, runHistoryRetentionDays = 0), pubSub,
        )
        val reaped = withResult { purger.purgeExpiredRuns() }
        assertTrue(reaped >= 2, "reaped the terminal run + its history row, got $reaped")
        // The soft-deleted run no longer surfaces, and its history is gone.
        assertEquals(null, withResult { runRepository.getById(run.id) }, "terminal run soft-deleted")
        assertTrue(withResult { runLogRepository.listForPipeline(pipelineId, 0, 10) }.isEmpty(), "history purged")
    }

    @Test
    fun `a failed run rolls back its completed rollback-enabled nodes (saga rollback)`() {
        RollbackProbeNode.rolledBack.clear()

        // Rollback pipeline: in -> probe -> out. The probe records the input it was fed, proving it ran.
        val (compId, _) = persistPipeline(
            "saga-rollback",
            listOf(InputNode(id = "in", acceptedType = "JSON"), RollbackProbeNode(id = "probe"), OutputNode(id = "out")),
            listOf(PipelineEdge(id = "ce1", source = "in", target = "probe"), PipelineEdge(id = "ce2", source = "probe", target = "out")),
            register = true,
        )

        // Main: in -> step1 (rollback-enabled) -> boom (always fails) -> out. step1 completes, then boom fails.
        val step1 = PassThroughNode(id = "step1").apply { rollbackPipeline = compId }
        val (_, main) = persistPipeline(
            "saga-main",
            listOf(InputNode(id = "in", acceptedType = "JSON"), step1, FailingNode(id = "boom"), OutputNode(id = "out")),
            listOf(
                PipelineEdge(id = "e1", source = "in", target = "step1"),
                PipelineEdge(id = "e2", source = "step1", target = "boom"),
                PipelineEdge(id = "e3", source = "boom", target = "out"),
            ),
        )

        val input = PipelineValue.ofJson(buildJsonObject { put("orderId", "o1") })
        val run = withResult { service.start(main, input, "test.event", java.time.OffsetDateTime.now()) }!!

        // The run failed at boom, and step1's rollback ran with step1's output as its input.
        assertEquals(PipelineRunStatus.FAILED, withResult { runRepository.getById(run.id) }?.status)
        assertEquals(1, RollbackProbeNode.rolledBack.size, "the completed rollback-enabled node was rolled back exactly once")
        assertEquals(buildJsonObject { put("orderId", "o1") }, RollbackProbeNode.rolledBack.first())
    }

    /** Persist a pipeline row (FK target for runs); optionally register it for [PipelineService.get]. */
    private fun persistPipeline(
        name: String,
        nodes: List<PipelineNode>,
        edges: List<PipelineEdge>,
        register: Boolean = false,
    ): Pair<UUID, Pipeline> {
        val graphElement = graphJson.encodeToJsonElement(PipelineGraph.serializer(), PipelineGraph(nodes, edges))
        val id = withResult {
            transaction { pipelineRepository.add(PipelineRecord(name = name, acceptedInputType = "JSON", graph = graphElement)).id }
        }
        val pipeline = Pipeline(id = id, name = name, acceptedInputType = "JSON", nodes = nodes, edges = edges)
        if (register) testPipelineService.register(pipeline)
        return id to pipeline
    }

    /** The child run ids a durable ForEach started for [parentRunId], in item-index order. */
    private fun childRunIds(parentRunId: UUID): List<UUID> = withResult {
        val ids = mutableListOf<UUID>()
        connection().useStatement(
            "select id from pipelines.pipeline_run where parent_run_id = ?::uuid order by item_index",
        ) { stmt ->
            stmt.setString(1, parentRunId.toString())
            val rs = stmt.executeQuery()
            while (rs.next()) ids.add(UUID.parse(rs.getString("id")))
        }
        ids
    }

    /** Accepts the child-run job a RunPipeline/ForEach enqueues; tests drive the child explicitly
     *  (there is no real runner here) via [PipelineRunService.driveExistingRun]. */
    private class CapturingChildRunEnqueuer : JobConfigurationEnqueuer {
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

        override suspend fun queue(): JobQueue = io.mockk.mockk(relaxed = true)
    }

    private class DummyExecutor : JobExecutor {
        override suspend fun execute() {}
    }

    /** Persist a pipeline, start the run, and drive it to its first suspend/completion. */
    private fun startAndExecute(nodes: List<PipelineNode>, edges: List<PipelineEdge>, name: String): bosca.pipelines.model.PipelineRun {
        val graphElement = graphJson.encodeToJsonElement(PipelineGraph.serializer(), PipelineGraph(nodes, edges))
        val pipelineId = withResult {
            transaction { pipelineRepository.add(PipelineRecord(name = name, acceptedInputType = "JSON", graph = graphElement)).id }
        }
        val pipeline = Pipeline(id = pipelineId, name = name, acceptedInputType = "JSON", nodes = nodes, edges = edges)
        val input = PipelineValue.ofJson(buildJsonObject { put("hello", "world") })
        val run = withResult { service.start(pipeline, input, "test.event", java.time.OffsetDateTime.now()) }!!
        return run
    }

    private fun withDb(block: suspend () -> Unit) = withResult(block)

    private fun <T> withResult(block: suspend () -> T): T = runBlocking {
        val manager = pool.connection()
        try {
            withContext(manager.asCoroutineContext()) { block() }
        } finally {
            withContext(NonCancellable) { manager.release() }
        }
    }

    /** Minimal [PipelineService] for the run service — only the graph (de)serialization is exercised. */
    private class TestPipelineService(private val graphJson: Json) : PipelineService {
        /** Pipelines resolvable by id — populated by tests that need [get] (e.g. ForEach body lookup). */
        private val registry = mutableMapOf<UUID, Pipeline>()

        fun register(pipeline: Pipeline) { registry[pipeline.id] = pipeline }

        override suspend fun graphAsJsonElement(pipeline: Pipeline): JsonElement =
            graphJson.encodeToJsonElement(PipelineGraph.serializer(), PipelineGraph(pipeline.nodes, pipeline.edges))

        override suspend fun decodeGraph(graph: JsonElement): PipelineGraph =
            graphJson.decodeFromJsonElement(PipelineGraph.serializer(), graph)

        override suspend fun descriptorFor(node: PipelineNode): NodeDescriptor? = when (node) {
            is SuspendingNode -> NodeDescriptor(
                key = "e2eSuspend",
                label = "Suspend",
                category = NodeCategory.ACTION,
                outputs = listOf(
                    NodeOutputSlot("out"),
                    NodeOutputSlot("alreadyExists", error = true),
                    NodeOutputSlot("error", error = true),
                ),
            )
            else -> null
        }

        override suspend fun get(id: UUID): Pipeline? = registry[id]
        override suspend fun getAll(): List<Pipeline> = error("unused")
        override suspend fun getBroken(): List<bosca.pipelines.model.BrokenPipeline> = emptyList()
        override suspend fun getByKey(key: String): Pipeline? = error("unused")
        override suspend fun acceptingInput(typeNames: Set<String>): List<Pipeline> = error("unused")
        override suspend fun triggeredFor(eventName: String): List<Pipeline> = error("unused")
        override suspend fun triggeredEventTypes(): Set<String> = error("unused")
        override suspend fun run(pipeline: Pipeline, input: PipelineValue): PipelineValue? = error("unused")
        override suspend fun save(
            id: UUID,
            name: String,
            description: String,
            acceptedInputType: String,
            triggered: Boolean,
            version: Long,
            graph: JsonElement,
            tags: List<String>,
            key: String,
            api: Boolean,
            public: Boolean,
            schedule: String?,
            maxConcurrentRuns: Int?,
            maxRunsPerMinute: Int?,
        ): Pipeline = error("unused")
        override suspend fun delete(id: UUID) = error("unused")
        override suspend fun validateGraph(graph: JsonElement): String? = error("unused")
        override suspend fun getPermissions(entity: Pipeline): List<EntityPermission> = emptyList()
        override suspend fun addPermissionsToBatch(batch: Batch<UUID, List<EntityPermission>>) = error("unused")
        override suspend fun addPermission(pipelineId: UUID, groupId: UUID, action: PermissionAction) = error("unused")
        override suspend fun deletePermission(pipelineId: UUID, groupId: UUID, action: PermissionAction) = error("unused")
    }
}
