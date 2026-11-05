package bosca.pipelines.graphql

import bosca.db.ConnectionManager
import bosca.db.ConnectionPool
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.graphql.server.*
import bosca.pipelines.PipelineContext
import bosca.pipelines.model.*
import bosca.pipelines.node.InputNode
import bosca.pipelines.repository.PipelinePermission
import bosca.pipelines.security.PipelinePermissionEvaluator
import bosca.pipelines.service.*
import bosca.pipelines.service.ExecutionResult
import bosca.security.model.*
import bosca.security.service.*
import bosca.serialization.UUID
import bosca.serialization.UUIDSerializer
import io.mockk.*
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.trace.Tracer
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlin.test.*

/** Published pipeline SDL and generated dispatchers with real role, scope and entity permission checks. */
@OptIn(bosca.di.annotation.InternalDI::class)
class PipelineScopedGraphQLTest {
    private var reachedUnattended = false
    private val json = Json { serializersModule = SerializersModule { contextual(UUIDSerializer()) } }
    private val service = mockk<PipelineService>()
    private val runs = mockk<PipelineRunService>()
    private val executor = mockk<PipelineExecutor>()
    private val security = mockk<SecurityService>()
    private val manager = mockk<ConnectionManager>(relaxed = true)
    private val admin = Group(UUID.random(), "administrators", "Administrators", GroupType.SYSTEM)
    private val users = Group(UUID.random(), "pipeline-users", "Users", GroupType.PRINCIPAL)
    private val principal = Principal(id = UUID.random(), anonymous = false)
    private var pipeline = Pipeline(UUID.random(), "Fixture", acceptedInputType = "JSON", key = "fixture", tags = listOf("release"),
        nodes = listOf(InputNode("in", acceptedType = "JSON")), version = 3)
    private val graph = Json.parseToJsonElement("""{"nodes":[{"type":"input","id":"in","acceptedType":"JSON"}],"edges":[]}""")
    private val run = PipelineRun(UUID.random(), pipeline.id, PipelineRunStatus.OK, graphSnapshot = graph, output = JsonPrimitive(7))
    private var grants: List<EntityPermission> = emptyList()
    private lateinit var graphql: GraphQL

    private fun auth(scopes: List<String>, groups: List<Group> = listOf(admin)) = object : AuthenticationContext(null, null) {
        override fun principal() = ScopedAuthenticatedPrincipal(principal, groups, scopes, groups.map { it.id }.toSet(), 42)
    }
    @BeforeTest fun setup() {
        ProviderRegistry.clear()
        provides { json }
        provides<Tracer> { GlobalOpenTelemetry.getTracer("pipeline-scoped-graphql") }
        provides<ConnectionPool> { mockk { every { connection() } returns manager } }
        coEvery { service.get(pipeline.id) } answers { pipeline }
        coEvery { service.runsUnattended(pipeline.id) } answers { reachedUnattended }
        coEvery { service.getByKey("fixture") } answers { pipeline }
        coEvery { service.getAll() } answers { listOf(pipeline) }
        coEvery { service.graphAsJsonElement(any()) } returns graph
        coEvery { service.validateGraph(any()) } returns null
        coEvery { service.getPermissions(any()) } answers { grants }
        coEvery { service.isParentAllowed(any(), any(), any()) } returns false
        coEvery { service.save(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } answers { pipeline }
        coEvery { runs.get(run.id) } returns run
        coEvery { runs.start(any(), any(), any(), any(), any()) } returns run
        coEvery { executor.execute(any(), any(), any(), any()) } returns ExecutionResult.Completed(null)
        val groups = GroupEvaluator(security)
        val permissions = PipelinePermissionEvaluator(service, security, groups)
        val queries = PipelinesController(service, groups, runs, mockk(), mockk())
        val mutations = PipelinesMutationController(service, groups, permissions, executor, mockk { every { exists } returns false }, runs, mockk(), mockk())
        val wiring = RuntimeWiringBuilder().apply {
            scalar("UUID", ExtendedScalars.Uuid); scalar("JSON", ExtendedScalars.Json); scalar("DateTime", ExtendedScalars.DateTime)
            scalar("Long", ExtendedScalars.Long)
            type("Query") { field("pipelines") { Pipelines } }
            type("Mutation") { field("pipelines") { PipelinesMutation } }
            type(PipelinesControllerDispatcher(queries).type)
            type(PipelinesMutationControllerDispatcher(mutations).type)
            type(PipelineControllerDispatcher(PipelineController(service, permissions)).type)
            type(PipelineGraphValidationControllerDispatcher(PipelineGraphValidationController()).type)
            type(PipelineRunResultControllerDispatcher(PipelineRunResultController()).type)
            type(PipelineDryRunControllerDispatcher(PipelineDryRunController()).type)
            type(PipelineRunStateControllerDispatcher(PipelineRunStateController(json, runs)).type)
        }.build()
        val sdl = requireNotNull(javaClass.getResourceAsStream("/graphql/pipelines.graphqls")).bufferedReader().use { it.readText() }
        graphql = GraphQL(ExecutableSchema.fromSdl("""
            scalar UUID
            scalar JSON
            scalar DateTime
            scalar Long
            enum PermissionAction { VIEW LIST EDIT DELETE EXECUTE MANAGE IMPERSONATE }
            type Permission { groupId: UUID!, action: PermissionAction! }
            input PermissionInput { entityId: UUID!, groupId: UUID!, action: PermissionAction! }
            type Query { placeholder: Boolean }
            type Mutation { placeholder: Boolean }
            type Subscription { placeholder: Boolean }
            $sdl
        """.trimIndent(), wiring))
    }
    @AfterTest fun teardown() { ProviderRegistry.clear() }
    private suspend fun call(query: String, authentication: AuthenticationContext, variables: JsonObject = JsonObject(emptyMap())) = graphql.execute(GraphQLRequest(
        query, variables = variables, context = GraphQLContext(mapOf("authenticationContext" to authentication)),
    ))

    @Test fun `discovery requires administrator membership and view scope with optional pagination`() = runTest {
        val query = """{ pipelines { all(offset:0,limit:10,search:"RELEASE") { id } pipelineByKey(key:"fixture") { graph } } }"""
        val allowed = call(query, auth(listOf("pipelines:view")))
        assertTrue(allowed.errors.isEmpty(), allowed.errors.toString()); assertContains(allowed.data.toString(), pipeline.id.toString())
        for (caller in listOf(auth(listOf("pipelines:edit")), auth(listOf("pipelines:view"), listOf(users)), AuthenticationContext(null, null))) {
            assertTrue(call(query, caller).errors.isNotEmpty())
        }
        for (args in listOf("offset:-1", "limit:101", "limit:0")) assertTrue(call("{ pipelines { all($args) { id } } }", auth(listOf("pipelines:view"))).errors.isNotEmpty())
        for (args in listOf("search:\"missing\"", "offset:1", "search:\"\"", "")) {
            assertTrue(call("{ pipelines { all${if (args.isEmpty()) "" else "($args)"} { id } } }", auth(listOf("pipelines:view"))).errors.isEmpty())
        }
    }

    @Test fun `validation returns engine violations and layout without executing or saving`() = runTest {
        val query = "query Check(\$graph:JSON!) { pipelines { validateGraph(graph:\$graph) { valid errors graph } } }"
        val variables = buildJsonObject { put("graph", graph) }
        val valid = call(query, auth(listOf("pipelines:view")), variables)
        assertTrue(valid.errors.isEmpty(), valid.errors.toString())
        assertContains(valid.data.toString(), "\"position\"")
        coEvery { service.validateGraph(any()) } returns "Incompatible slot"
        val invalid = call(query, auth(listOf("pipelines:view")), variables)
        assertContains(invalid.data.toString(), "Incompatible slot")
        assertContains(invalid.data.toString(), "\"valid\":false")
        coVerify(exactly = 0) { executor.execute(any(), any(), any(), any()); runs.start(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { service.save(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test fun `saving requires edit scope and admin role and keeps optimistic version`() = runTest {
        val query = "mutation Save(\$input:PipelineInput!) { pipelines { save(input:\$input) { id version } } }"
        val input = buildJsonObject { put("input", buildJsonObject {
            put("id", pipeline.id.toString()); put("name", "Fixture"); put("acceptedInputType", "JSON"); put("version", 3); put("graph", graph)
        }) }
        for (caller in listOf(auth(listOf("pipelines:view")), auth(listOf("pipelines:edit"), listOf(users)))) {
            assertTrue(call(query, caller, input).errors.isNotEmpty())
        }
        for (caller in listOf(auth(listOf("pipelines:edit")), pipelineAdministrator())) {
            val result = call(query, caller, input)
            assertTrue(result.errors.isEmpty(), result.errors.toString())
        }
        coVerify(exactly = 2) { service.save(pipeline.id, "Fixture", "", "JSON", false, 3L, graph, any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test fun `unattended runs require full administrator authority to enable or change`() = runTest {
        val query = "mutation Save(\$input:PipelineInput!) { pipelines { save(input:\$input) { id version } } }"
        fun input(extra: JsonObjectBuilder.() -> Unit = {}) = buildJsonObject { put("input", buildJsonObject {
            put("id", pipeline.id.toString()); put("name", "Fixture"); put("acceptedInputType", "JSON"); put("version", 3); put("graph", graph)
            extra()
        }) }
        val editOnly = auth(listOf("pipelines:edit"))
        assertTrue(call(query, editOnly, input { put("triggered", true) }).errors.isNotEmpty())
        assertTrue(call(query, editOnly, input { put("schedule", "0 * * * *") }).errors.isNotEmpty())
        val blankSchedule = call(query, editOnly, input { put("schedule", " ") })
        assertTrue(blankSchedule.errors.isEmpty(), blankSchedule.errors.toString())

        reachedUnattended = true
        assertTrue(call(query, editOnly, input()).errors.isNotEmpty(), "A child of an unattended pipeline runs with its authority")
        reachedUnattended = false

        pipeline = pipeline.copy(triggered = true)
        assertTrue(call(query, editOnly, input()).errors.isNotEmpty())
        pipeline = pipeline.copy(triggered = false, schedule = "0 * * * *")
        assertTrue(call(query, editOnly, input()).errors.isNotEmpty())

        for (caller in listOf(pipelineAdministrator(), auth(listOf("pipelines:edit", "security:manage")))) {
            val result = call(query, caller, input { put("triggered", true); put("schedule", "0 * * * *") })
            assertTrue(result.errors.isEmpty(), result.errors.toString())
        }
        coVerify(exactly = 3) { service.save(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test fun `private execution intersects scope and group grant and retains exact caller`() = runTest {
        grants = listOf(PipelinePermission(pipeline.id, users.id, PermissionAction.EXECUTE))
        val query = """mutation { pipelines { run(id:"${pipeline.id}",payload:[1,2]) { ok runId status output } } }"""
        val caller = auth(listOf("pipelines:execute"), listOf(users))
        val result = call(query, caller)
        assertTrue(result.errors.isEmpty(), result.errors.toString()); assertContains(result.data.toString(), "\"output\":7")
        coVerify(exactly = 1) { runs.start(pipeline, any(), "manual", any(), caller) }
        assertTrue(call(query, auth(listOf("jobs:execute"), listOf(users))).errors.isNotEmpty())
        grants = emptyList()
        assertTrue(call(query, caller).errors.isNotEmpty())
        val adminRun = call(query, auth(listOf("pipelines:execute")))
        assertTrue(adminRun.errors.isEmpty(), adminRun.errors.toString())
        coVerify(exactly = 2) { runs.start(any(), any(), any(), any(), any()) }
    }

    @Test fun `public execution remains open and dry run stays on the preview path`() = runTest {
        pipeline = pipeline.copy(public = true)
        val anonymous = AuthenticationContext(null, null)
        val preview = call("""mutation { pipelines { dryRun(id:"${pipeline.id}",payload:{}) { actions error } } }""", anonymous)
        assertTrue(preview.errors.isEmpty(), preview.errors.toString())
        coVerify(exactly = 1) { executor.execute(pipeline, any(), match<PipelineContext> { it.dryRun && it.authentication === anonymous }, any()) }
        coVerify(exactly = 0) { runs.start(any(), any(), any(), any(), any()) }
        assertTrue(call("""mutation { pipelines { run(id:"${pipeline.id}",payload:{}) { runId } } }""", anonymous).errors.isEmpty())
        // Public runs carry no implicit authority: anonymous stays anonymous, and a caller with credentials runs as itself.
        coVerify(exactly = 1) { runs.start(pipeline, any(), any(), any(), anonymous) }
        val caller = auth(listOf("pipelines:execute"), listOf(users))
        assertTrue(call("""mutation { pipelines { run(id:"${pipeline.id}",payload:{}) { runId } } }""", caller).errors.isEmpty())
        coVerify(exactly = 1) { runs.start(pipeline, any(), any(), any(), caller) }
    }

    @Test fun `pipeline permissions retain ordinary domain read and manage grants`() = runTest {
        val groups = GroupEvaluator(security)
        val permissions = PipelinePermissionEvaluator(service, security, groups)
        grants = listOf(PipelinePermission(pipeline.id, users.id, PermissionAction.VIEW))
        assertTrue(permissions.isAllowed(auth(listOf("content:view"), listOf(users)), pipeline, PermissionAction.VIEW))
        assertFalse(permissions.isAllowed(auth(listOf("pipelines:execute"), listOf(users)), pipeline, PermissionAction.VIEW))
        assertTrue(permissions.isAllowed(auth(listOf("content:manage")), pipeline, PermissionAction.MANAGE))
    }

    @Test fun `completed output can be polled under scoped admin read access`() = runTest {
        val query = """{ pipelines { run(runId:"${run.id}") { status output } } }"""
        val result = call(query, auth(listOf("pipelines:view")))
        assertTrue(result.errors.isEmpty(), result.errors.toString()); assertContains(result.data.toString(), "\"output\":7")
        assertTrue(call(query, auth(listOf("pipelines:execute"))).errors.isNotEmpty())
    }
}
