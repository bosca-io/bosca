@file:OptIn(Internal::class, ExperimentalUuidApi::class)

package bosca.pipelines.graphql

import bosca.core.annotations.Internal
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.pipelines.annotation.NodeCategory
import bosca.pipelines.model.BrokenPipeline
import bosca.pipelines.model.Pipeline
import bosca.pipelines.model.PipelineNamedShape
import bosca.pipelines.model.RunStep
import bosca.pipelines.model.RunStepKind
import bosca.pipelines.model.RunStepStatus
import bosca.pipelines.node.InputNode
import bosca.pipelines.node.NodeDescriptor
import bosca.pipelines.node.OutputNode
import bosca.pipelines.node.PipelineNode
import bosca.pipelines.node.PipelineNodeSerializers
import bosca.pipelines.node.ShapeField
import bosca.pipelines.repository.PipelinePermission
import bosca.pipelines.security.PipelinePermissionEvaluator
import bosca.pipelines.service.PipelineRunService
import bosca.pipelines.service.PipelineSecretService
import bosca.pipelines.service.PipelineShapeService
import bosca.pipelines.service.PipelineService
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.SerializerCache
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.Serializable
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi

/**
 * Field wiring for the `Pipeline` GraphQL type and the `Query.pipelines` namespace. These cover the
 * scalar projections, the permission gate on `permissions`, the derived input/output contract
 * projections (which read the in-memory Input/Output nodes), and the admin-gated namespace reads
 * (offset/limit defaulting + clamping, the node-type registry aggregation).
 */
class PipelineControllersTest {

    private interface CataloguedPipelineValue

    @Serializable
    private data class CataloguedPipelineValueImpl(val id: String) : CataloguedPipelineValue

    @OptIn(InternalDI::class)
    @AfterTest
    fun tearDown() {
        ProviderRegistry.clear()
    }

    // --- helpers ---------------------------------------------------------------------------------

    private fun pipeline(
        schedule: String? = null,
        maxConcurrentRuns: Int? = null,
        maxRunsPerMinute: Int? = null,
        gitRepositoryId: UUID? = null,
        gitPath: String? = null,
        lastSyncError: String? = null,
        nodes: List<PipelineNode> = emptyList(),
        version: Long = 0,
    ) = Pipeline(
        id = UUID.random(),
        name = "Greeter",
        description = "says hi",
        acceptedInputType = "JSON",
        triggered = true,
        key = "greet",
        api = true,
        public = false,
        schedule = schedule,
        maxConcurrentRuns = maxConcurrentRuns,
        maxRunsPerMinute = maxRunsPerMinute,
        nodes = nodes,
        version = version,
        gitRepositoryId = gitRepositoryId,
        gitPath = gitPath,
        lastSyncError = lastSyncError,
    )

    private fun pipelineController(
        service: PipelineService = mockk(relaxed = true),
        evaluator: PipelinePermissionEvaluator = mockk(relaxed = true),
    ) = PipelineController(service, evaluator)

    // === PipelineController: scalar projections ==================================================

    @Test
    fun `scalar identity and endpoint fields project verbatim`() {
        val source = pipeline()
        val controller = pipelineController()

        assertEquals(source.id, controller.id(source))
        assertEquals("greet", controller.key(source))
        assertTrue(controller.api(source))
        assertFalse(controller.public(source))
        assertEquals("Greeter", controller.name(source))
        assertEquals("says hi", controller.description(source))
        assertEquals("JSON", controller.acceptedInputType(source))
        assertEquals(emptyList(), controller.tags(source))
        assertTrue(controller.triggered(source))

        val tagged = source.copy(tags = listOf("release", "email"))
        assertEquals(listOf("release", "email"), controller.tags(tagged))
    }

    @Test
    fun `version projects the Long down to an Int`() {
        val controller = pipelineController()
        assertEquals(7, controller.version(pipeline(version = 7L)))
        assertEquals(0, controller.version(pipeline(version = 0L)))
    }

    @Test
    fun `schedule projects null and a present cron`() {
        val controller = pipelineController()
        assertNull(controller.schedule(pipeline(schedule = null)))
        assertEquals("0 0 * * *", controller.schedule(pipeline(schedule = "0 0 * * *")))
    }

    @Test
    fun `maxConcurrentRuns projects null and a present value`() {
        val controller = pipelineController()
        assertNull(controller.maxConcurrentRuns(pipeline(maxConcurrentRuns = null)))
        assertEquals(5, controller.maxConcurrentRuns(pipeline(maxConcurrentRuns = 5)))
    }

    @Test
    fun `maxRunsPerMinute projects null and a present value`() {
        val controller = pipelineController()
        assertNull(controller.maxRunsPerMinute(pipeline(maxRunsPerMinute = null)))
        assertEquals(60, controller.maxRunsPerMinute(pipeline(maxRunsPerMinute = 60)))
    }

    @Test
    fun `git linkage fields project null and present values`() {
        val controller = pipelineController()
        assertNull(controller.gitRepositoryId(pipeline(gitRepositoryId = null)))
        assertNull(controller.gitPath(pipeline(gitPath = null)))
        assertNull(controller.lastSyncError(pipeline(lastSyncError = null)))

        val repoId = UUID.random()
        val linked = pipeline(gitRepositoryId = repoId, gitPath = "pipelines/greet.yaml", lastSyncError = "conflict")
        assertEquals(repoId, controller.gitRepositoryId(linked))
        assertEquals("pipelines/greet.yaml", controller.gitPath(linked))
        assertEquals("conflict", controller.lastSyncError(linked))
    }

    @Test
    fun `graph delegates to the service`() = runTest {
        val service = mockk<PipelineService>()
        val source = pipeline()
        val graph = JsonObject(mapOf("nodes" to JsonNull))
        coEvery { service.graphAsJsonElement(source) } returns graph
        val controller = pipelineController(service = service)

        assertEquals(graph, controller.graph(source))
        coVerify(exactly = 1) { service.graphAsJsonElement(source) }
    }

    // === PipelineController: permissions gate ====================================================

    @Test
    fun `permissions returns empty when the caller cannot manage`() = runTest {
        val service = mockk<PipelineService>(relaxed = true)
        val evaluator = mockk<PipelinePermissionEvaluator>()
        val source = pipeline()
        coEvery { evaluator.isAllowed(null, source, PermissionAction.MANAGE) } returns false
        val controller = pipelineController(service = service, evaluator = evaluator)

        assertEquals(emptyList(), controller.permissions(null, source))
        // Short-circuits before touching the service.
        coVerify(exactly = 0) { service.getPermissions(any()) }
    }

    @Test
    fun `permissions maps the service grants when the caller can manage`() = runTest {
        val service = mockk<PipelineService>()
        val evaluator = mockk<PipelinePermissionEvaluator>()
        val source = pipeline()
        val groupId = UUID.random()
        coEvery { evaluator.isAllowed(any<AuthenticationContext>(), source, PermissionAction.MANAGE) } returns true
        coEvery { service.getPermissions(source) } returns listOf(
            PipelinePermission(pipelineId = source.id, groupId = groupId, action = PermissionAction.EXECUTE),
        )
        val controller = pipelineController(service = service, evaluator = evaluator)

        val permissions = controller.permissions(AuthenticationContext(null, null), source)
        assertEquals(1, permissions.size)
        assertEquals(groupId, permissions.single().groupId)
        assertEquals(PermissionAction.EXECUTE, permissions.single().action)
    }

    // === PipelineController: derived input/output projections ====================================

    @Test
    fun `inputSchema is null when there is no input node`() {
        val controller = pipelineController()
        assertNull(controller.inputSchema(pipeline(nodes = emptyList())))
    }

    @Test
    fun `inputSchema is null when the input node has no schema`() {
        val controller = pipelineController()
        val source = pipeline(nodes = listOf(InputNode(id = "in", acceptedType = "JSON", schema = null)))
        assertNull(controller.inputSchema(source))
    }

    @Test
    fun `inputSchema projects the input node's schema`() {
        val controller = pipelineController()
        val schema = JsonObject(mapOf("type" to JsonPrimitive("object")))
        val source = pipeline(nodes = listOf(InputNode(id = "in", acceptedType = "JSON", schema = schema)))
        assertEquals(schema, controller.inputSchema(source))
    }

    @Test
    fun `outputType is null when there is no output node`() {
        val controller = pipelineController()
        assertNull(controller.outputType(pipeline(nodes = emptyList())))
    }

    @Test
    fun `outputType projects a declared non-blank output type`() {
        val controller = pipelineController()
        val source = pipeline(nodes = listOf(OutputNode(id = "out", outputType = "com.x.Result")))
        assertEquals("com.x.Result", controller.outputType(source))
    }

    @Test
    fun `outputType falls back to JSON when blank but a schema is declared`() {
        val controller = pipelineController()
        val schema = JsonObject(mapOf("type" to JsonPrimitive("object")))
        val source = pipeline(nodes = listOf(OutputNode(id = "out", outputType = "", schema = schema)))
        assertEquals(InputNode.JSON_TYPE, controller.outputType(source))
    }

    @Test
    fun `outputType is null when blank and no schema is declared`() {
        val controller = pipelineController()
        val source = pipeline(nodes = listOf(OutputNode(id = "out", outputType = "", schema = null)))
        assertNull(controller.outputType(source))
    }

    @Test
    fun `outputSchema is null when there is no output node`() {
        val controller = pipelineController()
        assertNull(controller.outputSchema(pipeline(nodes = emptyList())))
    }

    @Test
    fun `outputSchema is null when the output node has no schema`() {
        val controller = pipelineController()
        val source = pipeline(nodes = listOf(OutputNode(id = "out", schema = null)))
        assertNull(controller.outputSchema(source))
    }

    @Test
    fun `outputSchema projects the output node's schema`() {
        val controller = pipelineController()
        val schema = JsonObject(mapOf("type" to JsonPrimitive("string")))
        val source = pipeline(nodes = listOf(OutputNode(id = "out", schema = schema)))
        assertEquals(schema, controller.outputSchema(source))
    }

    @Test
    fun `hasOutput is false without an output node and true with one`() {
        val controller = pipelineController()
        assertFalse(controller.hasOutput(pipeline(nodes = emptyList())))
        assertTrue(controller.hasOutput(pipeline(nodes = listOf(OutputNode(id = "out")))))
    }

    @Test
    fun `shape field projections expose input output and field metadata`() {
        val controller = pipelineController()
        val inputFields = listOf(ShapeField("requestId", "String"))
        val outputFields = listOf(ShapeField("result", "Boolean"))
        val source = pipeline(
            nodes = listOf(
                InputNode(id = "in", acceptedType = "SHAPE", fields = inputFields),
                OutputNode(id = "out", fields = outputFields),
            ),
        )

        assertEquals(inputFields, controller.inputFields(source))
        assertEquals(outputFields, controller.outputFields(source))
        assertEquals(emptyList(), controller.inputFields(pipeline()))
        assertEquals(emptyList(), controller.outputFields(pipeline()))

        val fieldController = PipelineShapeFieldController()
        assertEquals("requestId", fieldController.name(inputFields.single()))
        assertEquals("String", fieldController.type(inputFields.single()))
    }

    @Test
    fun `broken pipeline controller projects every field`() {
        val source = BrokenPipeline(UUID.random(), "Broken", "broken", "invalid node")
        val controller = BrokenPipelineController()

        assertEquals(source.id, controller.id(source))
        assertEquals("Broken", controller.name(source))
        assertEquals("broken", controller.key(source))
        assertEquals("invalid node", controller.error(source))
    }

    // === PipelinesController: namespace reads ====================================================

    private fun pipelinesController(
        service: PipelineService = mockk(relaxed = true),
        groups: GroupEvaluator = mockk(relaxed = true),
        runService: PipelineRunService = mockk(relaxed = true),
        secretService: PipelineSecretService = mockk(relaxed = true),
        shapeService: PipelineShapeService = mockk(relaxed = true),
    ) = PipelinesController(service, groups, runService, secretService, shapeService)

    @Test
    fun `runs defaults offset and limit and clamps to the page bounds`() = runTest {
        val runService = mockk<PipelineRunService>(relaxed = true)
        val controller = pipelinesController(runService = runService)
        val pipelineId = UUID.random()

        controller.runs(mockk(), pipelineId, null, null)
        coVerify(exactly = 1) { runService.listRunHistory(pipelineId, 0L, 50) }
    }

    @Test
    fun `runs clamps a too-large limit down to 200`() = runTest {
        val runService = mockk<PipelineRunService>(relaxed = true)
        val controller = pipelinesController(runService = runService)
        val pipelineId = UUID.random()

        controller.runs(mockk(), pipelineId, 5, 9999)
        coVerify(exactly = 1) { runService.listRunHistory(pipelineId, 5L, 200) }
    }

    @Test
    fun `runs clamps a too-small limit up to 1`() = runTest {
        val runService = mockk<PipelineRunService>(relaxed = true)
        val controller = pipelinesController(runService = runService)
        val pipelineId = UUID.random()

        controller.runs(mockk(), pipelineId, 0, 0)
        coVerify(exactly = 1) { runService.listRunHistory(pipelineId, 0L, 1) }
    }

    @Test
    fun `runs rejects a non-admin`() = runTest {
        val groups = mockk<GroupEvaluator>()
        every { groups.verifyHasAdminGroup(any()) } throws IllegalStateException("not an admin")
        val runService = mockk<PipelineRunService>(relaxed = true)
        val controller = pipelinesController(groups = groups, runService = runService)

        val ex = assertFailsWith<IllegalStateException> { controller.runs(mockk(), UUID.random(), null, null) }
        assertTrue(ex.message!!.contains("not an admin"))
        coVerify(exactly = 0) { runService.listRunHistory(any(), any(), any()) }
    }

    @Test
    fun `allRuns defaults and delegates`() = runTest {
        val runService = mockk<PipelineRunService>(relaxed = true)
        val controller = pipelinesController(runService = runService)

        controller.allRuns(mockk(), null, null)
        coVerify(exactly = 1) { runService.listAllRunHistory(0L, 50) }
    }

    @Test
    fun `allRuns passes explicit paging through`() = runTest {
        val runService = mockk<PipelineRunService>(relaxed = true)
        val controller = pipelinesController(runService = runService)

        controller.allRuns(mockk(), 10, 25)
        coVerify(exactly = 1) { runService.listAllRunHistory(10L, 25) }
    }

    @Test
    fun `allRuns rejects a non-admin`() = runTest {
        val groups = mockk<GroupEvaluator>()
        every { groups.verifyHasAdminGroup(any()) } throws IllegalStateException("not an admin")
        val runService = mockk<PipelineRunService>(relaxed = true)
        val controller = pipelinesController(groups = groups, runService = runService)

        assertFailsWith<IllegalStateException> { controller.allRuns(mockk(), null, null) }
        coVerify(exactly = 0) { runService.listAllRunHistory(any(), any()) }
    }

    @Test
    fun `all requires admin and lists the pipelines`() = runTest {
        val service = mockk<PipelineService>()
        coEvery { service.getAll() } returns listOf(pipeline())
        val controller = pipelinesController(service = service)

        assertEquals(1, controller.all(mockk()).size)
        coVerify(exactly = 1) { service.getAll() }
    }

    @Test
    fun `all rejects a non-admin`() = runTest {
        val groups = mockk<GroupEvaluator>()
        every { groups.verifyHasAdminGroup(any()) } throws IllegalStateException("not an admin")
        val service = mockk<PipelineService>(relaxed = true)
        val controller = pipelinesController(service = service, groups = groups)

        assertFailsWith<IllegalStateException> { controller.all(mockk()) }
        coVerify(exactly = 0) { service.getAll() }
    }

    @Test
    fun `pipeline fetches by id and returns null when absent`() = runTest {
        val service = mockk<PipelineService>()
        val id = UUID.random()
        coEvery { service.get(id) } returns null
        val controller = pipelinesController(service = service)

        assertNull(controller.pipeline(mockk(), id))
        coVerify(exactly = 1) { service.get(id) }
    }

    @Test
    fun `pipeline returns the found pipeline`() = runTest {
        val service = mockk<PipelineService>()
        val found = pipeline()
        coEvery { service.get(found.id) } returns found
        val controller = pipelinesController(service = service)

        assertEquals(found, controller.pipeline(mockk(), found.id))
    }

    @Test
    fun `pipeline rejects a non-admin`() = runTest {
        val groups = mockk<GroupEvaluator>()
        every { groups.verifyHasAdminGroup(any()) } throws IllegalStateException("not an admin")
        val service = mockk<PipelineService>(relaxed = true)
        val controller = pipelinesController(service = service, groups = groups)

        assertFailsWith<IllegalStateException> { controller.pipeline(mockk(), UUID.random()) }
        coVerify(exactly = 0) { service.get(any()) }
    }

    @Test
    fun `broken pipelines delegate after the admin check`() = runTest {
        val service = mockk<PipelineService>()
        val broken = BrokenPipeline(UUID.random(), "Broken", "broken", "invalid graph")
        coEvery { service.getBroken() } returns listOf(broken)

        assertEquals(listOf(broken), pipelinesController(service = service).broken(mockk()))
        coVerify(exactly = 1) { service.getBroken() }
    }

    @Test
    fun `steps for trigger and reusable shapes delegate to their services`() = runTest {
        val runService = mockk<PipelineRunService>()
        val shapeService = mockk<PipelineShapeService>()
        val step = RunStep("gate", "Approve", RunStepKind.HUMAN, RunStepStatus.PENDING)
        val shape = PipelineNamedShape("request", listOf(ShapeField("id", "String")))
        coEvery { runService.stepsForTrigger("release.created") } returns listOf(step)
        coEvery { shapeService.list() } returns listOf(shape)
        val controller = pipelinesController(runService = runService, shapeService = shapeService)

        assertEquals(listOf(step), controller.stepsForTrigger(mockk(), "release.created"))
        assertEquals(listOf(shape), controller.shapes())

        val namedShapeController = PipelineNamedShapeController()
        assertEquals("request", namedShapeController.name(shape))
        assertEquals(shape.fields, namedShapeController.fields(shape))
    }

    @Test
    fun `activeRuns clamps the limit and defaults the offset`() = runTest {
        val runService = mockk<PipelineRunService>(relaxed = true)
        val controller = pipelinesController(runService = runService)

        controller.activeRuns(mockk(), null, 500)
        coVerify(exactly = 1) { runService.listActive(0L, 200) }
    }

    @Test
    fun `activeRuns rejects a non-admin`() = runTest {
        val groups = mockk<GroupEvaluator>()
        every { groups.verifyHasAdminGroup(any()) } throws IllegalStateException("not an admin")
        val runService = mockk<PipelineRunService>(relaxed = true)
        val controller = pipelinesController(groups = groups, runService = runService)

        assertFailsWith<IllegalStateException> { controller.activeRuns(mockk(), null, null) }
        coVerify(exactly = 0) { runService.listActive(any(), any()) }
    }

    @Test
    fun `deadLetter defaults paging and delegates`() = runTest {
        val runService = mockk<PipelineRunService>(relaxed = true)
        val controller = pipelinesController(runService = runService)

        controller.deadLetter(mockk(), null, null)
        coVerify(exactly = 1) { runService.listDeadLetter(0L, 50) }
    }

    @Test
    fun `deadLetter passes explicit paging`() = runTest {
        val runService = mockk<PipelineRunService>(relaxed = true)
        val controller = pipelinesController(runService = runService)

        controller.deadLetter(mockk(), 3, 7)
        coVerify(exactly = 1) { runService.listDeadLetter(3L, 7) }
    }

    @Test
    fun `deadLetter rejects a non-admin`() = runTest {
        val groups = mockk<GroupEvaluator>()
        every { groups.verifyHasAdminGroup(any()) } throws IllegalStateException("not an admin")
        val runService = mockk<PipelineRunService>(relaxed = true)
        val controller = pipelinesController(groups = groups, runService = runService)

        assertFailsWith<IllegalStateException> { controller.deadLetter(mockk(), null, null) }
        coVerify(exactly = 0) { runService.listDeadLetter(any(), any()) }
    }

    @Test
    fun `run fetches a run by id`() = runTest {
        val runService = mockk<PipelineRunService>()
        val runId = UUID.random()
        coEvery { runService.get(runId) } returns null
        val controller = pipelinesController(runService = runService)

        assertNull(controller.run(mockk(), runId))
        coVerify(exactly = 1) { runService.get(runId) }
    }

    @Test
    fun `run rejects a non-admin`() = runTest {
        val groups = mockk<GroupEvaluator>()
        every { groups.verifyHasAdminGroup(any()) } throws IllegalStateException("not an admin")
        val runService = mockk<PipelineRunService>(relaxed = true)
        val controller = pipelinesController(groups = groups, runService = runService)

        assertFailsWith<IllegalStateException> { controller.run(mockk(), UUID.random()) }
        coVerify(exactly = 0) { runService.get(any()) }
    }

    @Test
    fun `nodeMetrics aggregates per node`() = runTest {
        val runService = mockk<PipelineRunService>()
        val pipelineId = UUID.random()
        coEvery { runService.nodeMetrics(pipelineId) } returns emptyList()
        val controller = pipelinesController(runService = runService)

        assertEquals(emptyList(), controller.nodeMetrics(mockk(), pipelineId))
        coVerify(exactly = 1) { runService.nodeMetrics(pipelineId) }
    }

    @Test
    fun `nodeMetrics rejects a non-admin`() = runTest {
        val groups = mockk<GroupEvaluator>()
        every { groups.verifyHasAdminGroup(any()) } throws IllegalStateException("not an admin")
        val runService = mockk<PipelineRunService>(relaxed = true)
        val controller = pipelinesController(groups = groups, runService = runService)

        assertFailsWith<IllegalStateException> { controller.nodeMetrics(mockk(), UUID.random()) }
        coVerify(exactly = 0) { runService.nodeMetrics(any()) }
    }

    @Test
    fun `secrets lists secret metadata`() = runTest {
        val secretService = mockk<PipelineSecretService>()
        coEvery { secretService.listSecrets() } returns emptyList()
        val controller = pipelinesController(secretService = secretService)

        assertEquals(emptyList(), controller.secrets(mockk()))
        coVerify(exactly = 1) { secretService.listSecrets() }
    }

    @Test
    fun `secrets rejects a non-admin`() = runTest {
        val groups = mockk<GroupEvaluator>()
        every { groups.verifyHasAdminGroup(any()) } throws IllegalStateException("not an admin")
        val secretService = mockk<PipelineSecretService>(relaxed = true)
        val controller = pipelinesController(groups = groups, secretService = secretService)

        assertFailsWith<IllegalStateException> { controller.secrets(mockk()) }
        coVerify(exactly = 0) { secretService.listSecrets() }
    }

    // === PipelinesController.nodeTypes (registry aggregation) =====================================

    private class FakeNodeSerializers(override val descriptors: List<NodeDescriptor>) : PipelineNodeSerializers {
        override val module: SerializersModule = SerializersModule { }
    }

    @Test
    fun `nodeTypes returns empty when no serializers are registered`() = runTest {
        val controller = pipelinesController()
        assertEquals(emptyList(), controller.nodeTypes(mockk()))
    }

    @Test
    fun `nodeTypes aggregates, dedups by key, and sorts by category then label`() = runTest {
        provides<PipelineNodeSerializers>(name = "moduleA") {
            FakeNodeSerializers(
                listOf(
                    NodeDescriptor(key = "zeta", label = "Zeta", category = NodeCategory.TRANSFORM),
                    NodeDescriptor(key = "alpha", label = "Alpha", category = NodeCategory.ACTION),
                    // Duplicate key — must be dropped by distinctBy { key }.
                    NodeDescriptor(key = "alpha", label = "Alpha Dup", category = NodeCategory.ACTION),
                ),
            )
        }
        provides<PipelineNodeSerializers>(name = "moduleB") {
            FakeNodeSerializers(
                listOf(
                    NodeDescriptor(key = "beta", label = "Beta", category = NodeCategory.ACTION),
                ),
            )
        }

        val controller = pipelinesController()
        val descriptors = controller.nodeTypes(mockk())

        // Sort is compareBy(category, label) over the NodeCategory enum order
        // (INPUT, OUTPUT, TRANSFORM, FETCH, COMBINE, ROUTE, ACTION): TRANSFORM precedes ACTION, so
        // zeta (TRANSFORM) comes first, then the ACTION pair alpha, beta sorted by label.
        assertEquals(listOf("zeta", "alpha", "beta"), descriptors.map { it.key })
        // The duplicate "alpha" was dropped by distinctBy { key }: the first one ("Alpha") wins.
        assertEquals("Alpha", descriptors.single { it.key == "alpha" }.label)
    }

    @Test
    fun `nodeTypes rejects a non-admin`() = runTest {
        val groups = mockk<GroupEvaluator>()
        every { groups.verifyHasAdminGroup(any()) } throws IllegalStateException("not an admin")
        val controller = pipelinesController(groups = groups)

        assertFailsWith<IllegalStateException> { controller.nodeTypes(mockk()) }
    }

    @Test
    fun `types includes concrete values and their registered domain interfaces`() = runTest {
        SerializerCache.register(
            CataloguedPipelineValueImpl::class.java,
            CataloguedPipelineValueImpl.serializer(),
            CataloguedPipelineValue::class.java,
        )

        val types = pipelinesController().types(mockk())

        assertTrue(CataloguedPipelineValueImpl.serializer().descriptor.serialName in types)
        assertTrue(CataloguedPipelineValue::class.java.name in types)
        assertEquals(types.sorted(), types)
    }

    @Test
    fun `types rejects a non-admin`() = runTest {
        val groups = mockk<GroupEvaluator>()
        every { groups.verifyHasAdminGroup(any()) } throws IllegalStateException("not an admin")

        assertFailsWith<IllegalStateException> { pipelinesController(groups = groups).types(mockk()) }
    }
}
