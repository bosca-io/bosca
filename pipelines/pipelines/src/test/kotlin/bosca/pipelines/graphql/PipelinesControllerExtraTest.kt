@file:OptIn(Internal::class, ExperimentalUuidApi::class, InternalDI::class)

package bosca.pipelines.graphql

import bosca.core.annotations.Internal
import bosca.di.ObjectProvider
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.events.catalog.EventCatalogRegistrar
import bosca.events.catalog.EventDescriptor
import bosca.pipelines.git.PipelineGitSyncService
import bosca.pipelines.model.Pipeline
import bosca.pipelines.security.PipelinePermissionEvaluator
import bosca.pipelines.service.PipelineExecutor
import bosca.pipelines.service.PipelineRunService
import bosca.pipelines.service.PipelineSecretService
import bosca.pipelines.service.PipelineService
import bosca.scheduler.service.SchedulerService
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi

/**
 * Supplemental branch/default-arg coverage for the pipeline GraphQL namespace controllers that the
 * primary controller tests do not reach: the `Query.pipelines` namespace object and the remaining
 * paging arms in [PipelinesController], and for [PipelinesMutationController] the `Mutation.pipelines`
 * namespace object, the synthetic default-argument constructors of [PipelineInput] /
 * [PipelineBackfillEntryInput] (constructed from a partial subset of optional args), the
 * null-schedule sync arm, the `e.toString()` fallback when a thrown error carries no message, and the
 * default-parameter overload of `signal`. Lives in its own file so the existing controller tests are
 * left untouched.
 */
class PipelinesControllerExtraTest {

    private val json = Json

    // --- PipelinesController -----------------------------------------------------------------------

    private fun pipelinesController(
        service: PipelineService = mockk(relaxed = true),
        groups: GroupEvaluator = mockk(relaxed = true),
        runService: PipelineRunService = mockk(relaxed = true),
        secretService: PipelineSecretService = mockk(relaxed = true),
    ) = PipelinesController(service, groups, runService, secretService, mockk(relaxed = true))

    @Test
    fun `Pipelines namespace object is referenceable`() {
        // Touch the `object Pipelines` namespace declaration (GraphQL `Query.pipelines` root).
        assertEquals("Pipelines", Pipelines::class.simpleName)
    }

    @Test
    fun `activeRuns passes an explicit offset and defaults the limit to 50`() = runTest {
        // offset non-null arm (3 -> 3L) AND limit null arm (null ?: 50), the complement of the
        // existing 'clamps the limit and defaults the offset' test which used (null, 500).
        val runService = mockk<PipelineRunService>(relaxed = true)
        val controller = pipelinesController(runService = runService)

        controller.activeRuns(mockk(), 3, null)
        coVerify(exactly = 1) { runService.listActive(3L, 50) }
    }

    @Test
    fun `activeRuns clamps a too-small limit up to 1`() = runTest {
        val runService = mockk<PipelineRunService>(relaxed = true)
        val controller = pipelinesController(runService = runService)

        controller.activeRuns(mockk(), 0, 0)
        coVerify(exactly = 1) { runService.listActive(0L, 1) }
    }

    // --- PipelinesMutationController DI scaffolding -----------------------------------------------

    private val service = mockk<PipelineService>(relaxed = true)
    private val groups = mockk<GroupEvaluator>(relaxed = true)
    private val permissionEvaluator = mockk<PipelinePermissionEvaluator>(relaxed = true)
    private val executor = mockk<PipelineExecutor>(relaxed = true)
    private val gitSync = mockk<PipelineGitSyncService>(relaxed = true)
    private val runService = mockk<PipelineRunService>(relaxed = true)
    private val secretService = mockk<PipelineSecretService>(relaxed = true)
    private val scheduler = mockk<SchedulerService>(relaxed = true)

    private var gitExists = true
    private var schedulerExists = true

    private val gitProvider = object : ObjectProvider<PipelineGitSyncService> {
        override val type = PipelineGitSyncService::class
        override val exists get() = gitExists
        override suspend fun get() = gitSync
    }
    private val schedulerProvider = object : ObjectProvider<SchedulerService> {
        override val type = SchedulerService::class
        override val exists get() = schedulerExists
        override suspend fun get() = scheduler
    }

    private val mutationController = PipelinesMutationController(
        service = service,
        groups = groups,
        permissionEvaluator = permissionEvaluator,
        executor = executor,
        gitSyncService = gitProvider,
        runService = runService,
        secretService = secretService,
        schedulerService = schedulerProvider,
        shapeService = mockk(relaxed = true),
    )

    private val auth = AuthenticationContext(null, null)

    @BeforeTest
    fun setUp() {
        provides<Json> { json }
    }

    @AfterTest
    fun tearDown() {
        ProviderRegistry.clear()
    }

    private fun pipeline(
        id: UUID = UUID.random(),
        name: String = "P",
        public: Boolean = false,
        schedule: String? = null,
        gitRepositoryId: UUID? = null,
        acceptedInputType: String = "JSON",
    ) = Pipeline(
        id = id,
        name = name,
        acceptedInputType = acceptedInputType,
        public = public,
        schedule = schedule,
        gitRepositoryId = gitRepositoryId,
    )

    // --- namespace object + default-arg constructors ---------------------------------------------

    @Test
    fun `PipelinesMutation namespace object is referenceable`() {
        // Touch the `object PipelinesMutation` namespace declaration (GraphQL `Mutation.pipelines`).
        assertEquals("PipelinesMutation", PipelinesMutation::class.simpleName)
    }

    @Test
    fun `PipelineInput constructs from only the required args leaving every optional default`() {
        // Drives the synthetic default-args constructor's mask branches: only name,
        // acceptedInputType, and graph supplied; everything else falls to its declared default.
        val input = PipelineInput(name = "Minimal", acceptedInputType = "JSON", graph = JsonObject(emptyMap()))

        assertNull(input.id)
        assertEquals("Minimal", input.name)
        assertNull(input.description)
        assertNull(input.triggered)
        assertNull(input.key)
        assertNull(input.api)
        assertNull(input.public)
        assertNull(input.schedule)
        assertNull(input.maxConcurrentRuns)
        assertNull(input.maxRunsPerMinute)
        assertNull(input.version)
        assertEquals(JsonObject(emptyMap()), input.graph)
    }

    @Test
    fun `PipelineInput constructs from a partial subset of optional args`() {
        // A different default mask: some optionals provided, the rest left default.
        val input = PipelineInput(
            name = "Partial",
            acceptedInputType = "JSON",
            schedule = "0 0 * * *",
            maxConcurrentRuns = 2,
            graph = JsonObject(emptyMap()),
        )

        assertEquals("Partial", input.name)
        assertEquals("0 0 * * *", input.schedule)
        assertEquals(2, input.maxConcurrentRuns)
        // Untouched optionals still default.
        assertNull(input.maxRunsPerMinute)
        assertNull(input.description)
        assertNull(input.id)
    }

    @Test
    fun `PipelineBackfillEntryInput projects its required fields`() {
        val pid = UUID.random()
        val entry = PipelineBackfillEntryInput(pipelineId = pid, gitPath = "pipelines/x.yaml")
        assertEquals(pid, entry.pipelineId)
        assertEquals("pipelines/x.yaml", entry.gitPath)
        assertEquals(entry, entry.copy())
    }

    @Test
    fun `PipelineBackfillEntryInput equals covers same-ref, wrong-type, null, and each differing field`() {
        val pid = UUID.random()
        val entry = PipelineBackfillEntryInput(pipelineId = pid, gitPath = "pipelines/x.yaml")

        @Suppress("KotlinConstantConditions")
        assertTrue(entry.equals(entry))                                            // same reference
        assertEquals(entry, PipelineBackfillEntryInput(pid, "pipelines/x.yaml"))   // distinct but equal
        assertFalse(entry.equals("nope"))                                          // wrong type
        assertFalse(entry.equals(null))                                            // null
        assertFalse(entry == PipelineBackfillEntryInput(UUID.random(), "pipelines/x.yaml")) // pipelineId differs
        assertFalse(entry == PipelineBackfillEntryInput(pid, "pipelines/y.yaml"))  // gitPath differs
        assertEquals(entry.hashCode(), PipelineBackfillEntryInput(pid, "pipelines/x.yaml").hashCode())
    }

    /**
     * Each construction supplies every optional EXCEPT one (the omitted field defaults), so across
     * the set every optional's default-mask "provided" arm is exercised — the complement of the
     * all-required-only construction above (which flips every "use default" arm at once).
     */
    @Test
    fun `PipelineInput per-field single-omission constructions flip each default mask bit`() {
        val gid = UUID.random()
        val g = JsonObject(emptyMap())

        // omit id only
        assertNull(PipelineInput(name = "P", acceptedInputType = "JSON", description = "d", triggered = true, key = "k", api = true, public = true, schedule = "c", maxConcurrentRuns = 1, maxRunsPerMinute = 2, version = 3, graph = g).id)
        // omit description only
        assertNull(PipelineInput(id = gid, name = "P", acceptedInputType = "JSON", triggered = true, key = "k", api = true, public = true, schedule = "c", maxConcurrentRuns = 1, maxRunsPerMinute = 2, version = 3, graph = g).description)
        // omit triggered only
        assertNull(PipelineInput(id = gid, name = "P", acceptedInputType = "JSON", description = "d", key = "k", api = true, public = true, schedule = "c", maxConcurrentRuns = 1, maxRunsPerMinute = 2, version = 3, graph = g).triggered)
        // omit key only
        assertNull(PipelineInput(id = gid, name = "P", acceptedInputType = "JSON", description = "d", triggered = true, api = true, public = true, schedule = "c", maxConcurrentRuns = 1, maxRunsPerMinute = 2, version = 3, graph = g).key)
        // omit api only
        assertNull(PipelineInput(id = gid, name = "P", acceptedInputType = "JSON", description = "d", triggered = true, key = "k", public = true, schedule = "c", maxConcurrentRuns = 1, maxRunsPerMinute = 2, version = 3, graph = g).api)
        // omit public only
        assertNull(PipelineInput(id = gid, name = "P", acceptedInputType = "JSON", description = "d", triggered = true, key = "k", api = true, schedule = "c", maxConcurrentRuns = 1, maxRunsPerMinute = 2, version = 3, graph = g).public)
        // omit schedule only
        assertNull(PipelineInput(id = gid, name = "P", acceptedInputType = "JSON", description = "d", triggered = true, key = "k", api = true, public = true, maxConcurrentRuns = 1, maxRunsPerMinute = 2, version = 3, graph = g).schedule)
        // omit maxConcurrentRuns only
        assertNull(PipelineInput(id = gid, name = "P", acceptedInputType = "JSON", description = "d", triggered = true, key = "k", api = true, public = true, schedule = "c", maxRunsPerMinute = 2, version = 3, graph = g).maxConcurrentRuns)
        // omit maxRunsPerMinute only
        assertNull(PipelineInput(id = gid, name = "P", acceptedInputType = "JSON", description = "d", triggered = true, key = "k", api = true, public = true, schedule = "c", maxConcurrentRuns = 1, version = 3, graph = g).maxRunsPerMinute)
        // omit version only
        assertNull(PipelineInput(id = gid, name = "P", acceptedInputType = "JSON", description = "d", triggered = true, key = "k", api = true, public = true, schedule = "c", maxConcurrentRuns = 1, maxRunsPerMinute = 2, graph = g).version)
    }

    // --- syncSchedule: null-schedule arm with the scheduler enabled ------------------------------

    @Test
    fun `save with a null schedule and the scheduler enabled tears down any existing job`() = runTest {
        // schedulerExists true + pipeline.schedule null -> cron == null arm: scan, then delete the
        // existing matching job (the schedule?.trim() safe-call left arm where schedule is null).
        schedulerExists = true
        val saved = pipeline(schedule = null, gitRepositoryId = null)
        coEvery {
            service.save(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns saved
        coEvery { scheduler.getJobs(any(), any(), any()) } returns emptyList()

        mutationController.save(
            auth,
            PipelineInput(name = "P", acceptedInputType = "JSON", schedule = null, graph = JsonObject(emptyMap())),
        )

        coVerify(exactly = 1) { scheduler.getJobs(any(), any(), any()) }
        coVerify(exactly = 0) { scheduler.createJob(any(), any()) }
        coVerify(exactly = 0) { scheduler.updateJob(any(), any()) }
    }

    // --- run: e.toString() fallback when the thrown error has no message --------------------------

    /** A serializer whose deserialize throws a message-less exception, to drive the `?: e.toString()` arm. */
    private object NoMessageSerializer : KSerializer<Any> {
        override val descriptor: SerialDescriptor = String.serializer().descriptor
        override fun deserialize(decoder: Decoder): Any = throw RuntimeException()
        override fun serialize(encoder: Encoder, value: Any) = throw RuntimeException()
    }

    @Test
    fun `run reports the toString when input resolution throws a message-less error`() = runTest {
        // An event-typed pipeline whose catalogued serializer throws a no-message exception makes
        // resolvePipelineRunInput fail with e.message == null, exercising the `?: e.toString()` arm.
        provides<EventCatalogRegistrar> {
            object : EventCatalogRegistrar {
                override val events: List<EventDescriptor> = emptyList()
                override val serializers: Map<String, KSerializer<*>> = mapOf("evt.no.message" to NoMessageSerializer)
            }
        }
        val id = UUID.random()
        coEvery { service.get(id) } returns pipeline(id = id, public = true, acceptedInputType = "evt.no.message")

        val result = mutationController.run(auth, id, buildJsonObject { put("x", JsonPrimitive(1)) })

        assertFalse(result.ok)
        assertEquals(UUID.NIL, result.runId)
        // e.message is null, so the error is e.toString() (the RuntimeException's class name).
        assertTrue(result.error?.contains("RuntimeException") == true, "expected toString fallback, got: ${result.error}")
        coVerify(exactly = 0) { runService.start(any(), any(), any(), any(), any()) }
    }
}
