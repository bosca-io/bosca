package bosca.scripting.graphql

import bosca.ai.agents.model.Agent
import bosca.ai.agents.model.AgentTool
import bosca.ai.agents.service.AgentService
import bosca.ai.agents.service.AgentToolService
import bosca.db.ConnectionManager
import bosca.db.ConnectionPool
import bosca.di.ObjectProvider
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.graphql.Batch
import bosca.graphql.server.*
import bosca.scripting.context.BoscaScriptContext
import bosca.scripting.model.*
import bosca.scripting.security.ScriptPermissionEvaluator
import bosca.scripting.service.ScriptExecutionService
import bosca.scripting.service.ScriptService
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

/** Published schema and generated dispatchers with real script permission evaluation. */
@OptIn(bosca.di.annotation.InternalDI::class)
class ScriptingGraphQLTest {
    private val json = Json { serializersModule = SerializersModule { contextual(UUIDSerializer()) } }
    private val scripts = mockk<ScriptService>()
    private val execution = mockk<ScriptExecutionService>()
    private val security = mockk<SecurityService>()
    private val tools = mockk<AgentToolService>()
    private val agents = mockk<AgentService>()
    private val manager = mockk<ConnectionManager>(relaxed = true)
    private val group = Group(UUID.random(), "script-users", "", GroupType.PRINCIPAL)
    private val admin = Group(UUID.random(), "administrators", "", GroupType.SYSTEM)
    private val editors = Group(UUID.random(), "editors", "", GroupType.SYSTEM)
    private val managers = Group(UUID.random(), "managers", "", GroupType.SYSTEM)
    private val principal = Principal(id = UUID.random(), anonymous = false)
    private var item = Script(id = UUID.random(), key = "fixture", name = "Fixture", source = "main { null }", version = 3)
    private var grants: List<EntityPermission> = emptyList()
    private lateinit var graphql: GraphQL
    private fun auth(scopes: List<String>?, groups: List<Group> = listOf(group)) = object : AuthenticationContext(null, null) {
        override fun principal() = ScopedAuthenticatedPrincipal(principal, groups, scopes, groups.map { it.id }.toSet(), 42)
    }
    private fun grant(vararg actions: PermissionAction) { grants = actions.map { ScriptPermission(item.id, group.id, it) } }

    @BeforeTest fun setup() {
        ProviderRegistry.clear()
        provides { json }
        provides<Tracer> { GlobalOpenTelemetry.getTracer("scripting-graphql-test") }
        provides<ConnectionPool> { mockk { every { connection() } returns manager } }
        coEvery { scripts.get(item.id) } answers { item }
        coEvery { scripts.getByKey(item.key) } answers { item }
        coEvery { scripts.getAll() } answers { listOf(item) }
        coEvery { scripts.getPermissions(any()) } answers { grants }
        coEvery { scripts.isParentAllowed(any(), any(), any()) } returns false
        coEvery { scripts.addPermissionsToBatch(any()) } coAnswers {
            firstArg<Batch<UUID, List<EntityPermission>>>().setData(item.id, grants)
        }
        coEvery { execution.executeAsJson(any(), any()) } answers { secondArg<BoscaScriptContext>().input }
        coEvery { execution.validate(any(), any()) } returns ScriptValidationResult(true, false, emptyList())
        val groups = GroupEvaluator(security)
        val permissions = ScriptPermissionEvaluator(scripts, security, groups)
        val mutations = ScriptsMutationController(scripts, execution, mockk(), groups, json, permissions,
            mockk<ObjectProvider<AgentToolService>> { coEvery { get() } returns tools },
            mockk<ObjectProvider<AgentService>> { coEvery { get() } returns agents })
        val wiring = RuntimeWiringBuilder().apply {
            scalar("UUID", ExtendedScalars.Uuid); scalar("JSON", ExtendedScalars.Json); scalar("DateTime", ExtendedScalars.DateTime)
            type("Query") { field("scripts") { Scripts } }
            type("Mutation") { field("scripts") { ScriptsMutation } }
            type(ScriptsControllerDispatcher(ScriptsController(scripts, permissions)).type)
            type(ScriptsMutationControllerDispatcher(mutations).type)
            type(ScriptControllerDispatcher(ScriptController(scripts, permissions)).type)
            type(ScriptValidationResultControllerDispatcher(ScriptValidationResultController()).type)
        }.build()
        val sdl = requireNotNull(javaClass.getResourceAsStream("/graphql/scripts.graphqls")).bufferedReader().use { it.readText() }
        graphql = GraphQL(ExecutableSchema.fromSdl("""
            scalar UUID
            scalar JSON
            scalar DateTime
            enum PermissionAction { VIEW LIST EDIT DELETE EXECUTE MANAGE IMPERSONATE }
            type Permission { groupId: UUID!, action: PermissionAction! }
            input PermissionInput { entityId: UUID!, groupId: UUID!, action: PermissionAction! }
            input SourceRefInput { path: String }
            type Query { placeholder: Boolean }
            type Mutation { placeholder: Boolean }
            $sdl
        """.trimIndent(), wiring))
    }
    @AfterTest fun teardown() { ProviderRegistry.clear() }
    private suspend fun call(query: String, authentication: AuthenticationContext) = graphql.execute(GraphQLRequest(
        query, context = GraphQLContext(mapOf("authenticationContext" to authentication)),
    ))
    private fun mutation(field: String) = "mutation { scripts { $field } }"
    private val input get() = """{key:"fixture",name:"Fixture",source:"main { null }",type:GENERAL}"""

    @Test fun `private reads require both view scope and a group grant`() = runTest {
        grant(PermissionAction.VIEW)
        val query = """{ scripts { script(id:"${item.id}") { source } scriptByKey(key:"fixture") { key } all(offset:0,limit:20) { key } } }"""
        val allowed = call(query, auth(listOf("scripts:view")))
        assertTrue(allowed.errors.isEmpty(), allowed.errors.toString()); assertContains(allowed.data.toString(), item.source)
        assertFalse(call(query, auth(listOf("scripts:execute"))).data.toString().contains(item.source))
        grants = emptyList()
        assertFalse(call(query, auth(listOf("scripts:view"))).data.toString().contains(item.source))
    }

    @Test fun `public API invocation does not expose source without a source grant`() = runTest {
        item = item.copy(type = ScriptType.API, public = true)
        val query = """{ scripts { script(id:"${item.id}") { source } all { source } } }"""
        for (caller in listOf(AuthenticationContext(null, null), auth(listOf("scripts:view")))) {
            val result = call(query, caller)
            assertFalse(result.data.toString().contains(item.source))
        }
    }

    @Test fun `execution preserves arbitrary JSON input and requires execute scope plus ACL`() = runTest {
        grant(PermissionAction.EXECUTE)
        for (input in listOf("[1,2]", "7", "null", "{value:9}")) {
            val result = call(mutation("""executeScript(id:"${item.id}",input:$input)"""), auth(listOf("scripts:execute")))
            assertTrue(result.errors.isEmpty(), result.errors.toString())
            val expected = Json.parseToJsonElement(input.replace("{value:9}", """{"value":9}"""))
            assertEquals(expected, assertNotNull(result.data).jsonObject.getValue("scripts").jsonObject["executeScript"])
        }
        assertTrue(call(mutation("""executeScript(id:"${item.id}")"""), auth(listOf("scripts:view"))).errors.isNotEmpty())
        coVerify(exactly = 4) { execution.executeAsJson(any(), any()) }
    }

    @Test fun `disabled scripts and invalid input do not execute`() = runTest {
        grant(PermissionAction.EXECUTE)
        item = item.copy(enabled = false)
        assertTrue(call(mutation("""executeScript(id:"${item.id}")"""), auth(listOf("scripts:execute"))).errors.isNotEmpty())
        item = item.copy(enabled = true, inputSchema = Json.parseToJsonElement("""{"type":"object","required":["name"]}"""))
        assertTrue(call(mutation("""executeScript(id:"${item.id}",input:{})"""), auth(listOf("scripts:execute"))).errors.isNotEmpty())
        coVerify(exactly = 0) { execution.executeAsJson(any(), any()) }
    }

    @Test fun `management scope does not replace entity permissions`() = runTest {
        val caller = auth(listOf("scripts:manage"))
        for (operation in listOf("disableScript", "enableScript", "deleteScript")) {
            assertTrue(call(mutation("""$operation(id:"${item.id}")"""), caller).errors.isNotEmpty())
        }
        grant(PermissionAction.EDIT, PermissionAction.DELETE)
        coEvery { scripts.disable(item.id) } returns item
        coEvery { scripts.enable(item.id) } returns item
        coEvery { scripts.delete(item.id) } returns Unit
        for (operation in listOf("disableScript", "enableScript", "deleteScript")) {
            val result = call(mutation("""$operation(id:"${item.id}")"""), caller)
            assertTrue(result.errors.isEmpty(), result.errors.toString())
        }
    }

    @Test fun `creation requires administrative group with script scope`() = runTest {
        assertTrue(call(mutation("addScript(script:$input) { id }"), auth(listOf("scripts:manage"))).errors.isNotEmpty())
        assertTrue(call(mutation("addScript(script:$input) { id }"), auth(listOf("scripts:view"), listOf(admin))).errors.isNotEmpty())
        coEvery { scripts.add(any()) } returns item
        val result = call(mutation("addScript(script:$input) { id }"), auth(listOf("scripts:manage"), listOf(admin)))
        assertTrue(result.errors.isEmpty(), result.errors.toString())
    }

    @Test fun `validation requires create rights or edit access to the named script`() = runTest {
        val validate = """validateScript(source:"main { null }",compile:true) { valid compiled errors }"""
        val validateExisting = """validateScript(source:"main { null }",compile:true,id:"${item.id}") { valid }"""
        assertTrue(call(mutation(validate), auth(listOf("scripts:manage"))).errors.isNotEmpty())
        assertTrue(call(mutation(validate), auth(null, listOf(editors))).errors.isNotEmpty())
        assertTrue(call(mutation(validateExisting), auth(listOf("scripts:manage"))).errors.isNotEmpty())
        coVerify(exactly = 0) { execution.validate(any(), any()) }
        assertTrue(call(mutation(validate), auth(listOf("scripts:manage"), listOf(admin))).errors.isEmpty())
        grant(PermissionAction.EDIT)
        assertTrue(call(mutation(validateExisting), auth(listOf("scripts:manage"))).errors.isEmpty())
        assertTrue(call(mutation(validateExisting), auth(listOf("scripts:view"))).errors.isNotEmpty())
        coVerify(exactly = 2) { execution.validate("main { null }", true) }
    }

    @Test fun `editors and managers hold no implicit script access`() = runTest {
        for (role in listOf(editors, managers)) {
            val caller = auth(null, listOf(role))
            for (operation in listOf("disableScript", "enableScript", "deleteScript")) {
                assertTrue(call(mutation("""$operation(id:"${item.id}")"""), caller).errors.isNotEmpty(), "${role.name} $operation")
            }
            assertTrue(call(mutation("""editScript(id:"${item.id}",script:$input,expectedVersion:3) { version }"""), caller).errors.isNotEmpty())
            val read = call("""{ scripts { script(id:"${item.id}") { source } all { source } } }""", caller)
            assertFalse(read.data.toString().contains(item.source), read.data.toString())
        }
        coVerify(exactly = 0) { scripts.edit(any(), any(), any()) }
        coVerify(exactly = 0) { scripts.delete(any()) }
    }

    @Test fun `TOOL re-registration does not attach an already attached tool`() = runTest {
        val tool = AgentTool(UUID.random(), item.key, item.name, "", scriptId = item.id)
        val agent = Agent(UUID.random(), "kit.script", "Kit Script", "", UUID.random(), UUID.random())
        coEvery { scripts.add(any()) } returns item.copy(type = ScriptType.TOOL)
        coEvery { tools.getByScriptId(item.id) } returns listOf(tool)
        coEvery { agents.getByKey("kit.script") } returns agent
        coEvery { agents.getTools(agent.id) } returns listOf(tool)
        val result = call(mutation("addScript(script:${input.replace("GENERAL", "TOOL")}) { id }"), auth(listOf("scripts:manage"), listOf(admin)))
        assertTrue(result.errors.isEmpty(), result.errors.toString())
        coVerify(exactly = 0) { tools.add(any()) }
        coVerify(exactly = 0) { agents.addTool(any(), any()) }
    }

    @Test fun `edit forwards expected version and TOOL registration failure rolls back`() = runTest {
        item = item.copy(inputSchema = Json.parseToJsonElement("""{"type":"object"}"""))
        grant(PermissionAction.EDIT)
        coEvery { scripts.edit(item.id, any(), 3) } returns item
        val result = call(mutation("""editScript(id:"${item.id}",script:$input,expectedVersion:3) { version }"""), auth(listOf("scripts:manage")))
        assertTrue(result.errors.isEmpty(), result.errors.toString())
        coVerify(exactly = 1) { scripts.edit(item.id, match { it.inputSchema == null }, 3) }
        val toolInput = input.replace("GENERAL", "TOOL")
        coEvery { scripts.add(any()) } returns item.copy(type = ScriptType.TOOL)
        coEvery { tools.getByScriptId(item.id) } returns emptyList()
        coEvery { tools.add(any()) } throws IllegalStateException("registration unavailable")
        assertTrue(call(mutation("addScript(script:$toolInput) { id }"), auth(listOf("scripts:manage"), listOf(admin))).errors.isNotEmpty())
        coVerify { manager.rollbackTransaction() }
    }

    @Test fun `TOOL creation registers a script backed tool without requiring security manage`() = runTest {
        coEvery { scripts.add(any()) } returns item.copy(type = ScriptType.TOOL)
        coEvery { tools.getByScriptId(item.id) } returns emptyList()
        coEvery { tools.add(any()) } returns AgentTool(UUID.random(), item.key, item.name, "", scriptId = item.id)
        coEvery { agents.getByKey("kit.script") } returns null
        val result = call(mutation("addScript(script:${input.replace("GENERAL", "TOOL")}) { id }"), auth(listOf("scripts:manage"), listOf(admin)))
        assertTrue(result.errors.isEmpty(), result.errors.toString())
        coVerify { tools.add(match { it.scriptId == item.id && it.key == item.key }) }
        coVerify { manager.commitTransaction() }
    }

    @Test fun `leaving TOOL removes the registered tool and detaches script tools from Kit`() = runTest {
        item = item.copy(type = ScriptType.TOOL)
        grant(PermissionAction.EDIT)
        val registered = AgentTool(UUID.random(), item.key, item.name, "", scriptId = item.id)
        val separate = AgentTool(UUID.random(), "administrator-tool", "Administrator", "", scriptId = item.id)
        val agent = Agent(UUID.random(), "kit.script", "Kit Script", "", UUID.random(), UUID.random())
        coEvery { scripts.edit(item.id, any(), 3) } returns item.copy(type = ScriptType.GENERAL)
        coEvery { tools.getByScriptId(item.id) } returns listOf(registered, separate)
        coEvery { tools.delete(registered.id) } returns true
        coEvery { agents.getByKey("kit.script") } returns agent
        coEvery { agents.getTools(agent.id) } returns listOf(registered, separate)
        coJustRun { agents.removeTool(agent.id, any()) }
        val result = call(mutation("""editScript(id:"${item.id}",script:$input,expectedVersion:3) { version }"""), auth(listOf("scripts:manage")))
        assertTrue(result.errors.isEmpty(), result.errors.toString())
        coVerify(exactly = 1) { agents.removeTool(agent.id, registered.id) }
        coVerify(exactly = 1) { agents.removeTool(agent.id, separate.id) }
        coVerify(exactly = 1) { tools.delete(registered.id) }
        coVerify(exactly = 0) { tools.delete(separate.id) }
    }

    @Test fun `renaming a TOOL script updates only its registered tool`() = runTest {
        item = item.copy(type = ScriptType.TOOL)
        grant(PermissionAction.EDIT)
        val configuration = JsonObject(mapOf("timeout" to JsonPrimitive(5)))
        val registered = AgentTool(UUID.random(), item.key, item.name, "", configuration = configuration, scriptId = item.id)
        val separate = AgentTool(UUID.random(), "administrator-tool", "Administrator", "", scriptId = item.id)
        coEvery { scripts.edit(item.id, any(), 3) } returns item.copy(key = "renamed", name = "Renamed", description = "New")
        coEvery { tools.getByScriptId(item.id) } returns listOf(separate, registered)
        coEvery { tools.edit(registered.id, any()) } returns registered
        val result = call(mutation("""editScript(id:"${item.id}",script:${input.replace("GENERAL", "TOOL")},expectedVersion:3) { version }"""), auth(listOf("scripts:manage")))
        assertTrue(result.errors.isEmpty(), result.errors.toString())
        coVerify(exactly = 1) { tools.edit(registered.id, match { it.key == "renamed" && it.name == "Renamed" && it.description == "New" && it.scriptId == item.id && it.configuration == configuration }) }
        coVerify(exactly = 0) { tools.edit(separate.id, any()) }
        coVerify(exactly = 0) { tools.add(any()) }
    }

    @Test fun `editing a TOOL script without changing its identity leaves the tool alone`() = runTest {
        item = item.copy(type = ScriptType.TOOL)
        grant(PermissionAction.EDIT)
        coEvery { scripts.edit(item.id, any(), 3) } returns item.copy(source = "main { 1 }")
        val result = call(mutation("""editScript(id:"${item.id}",script:${input.replace("GENERAL", "TOOL")},expectedVersion:3) { version }"""), auth(listOf("scripts:manage")))
        assertTrue(result.errors.isEmpty(), result.errors.toString())
        coVerify(exactly = 0) { tools.getByScriptId(any()) }
    }

    @Test fun `deleting a TOOL script removes its registered tool`() = runTest {
        item = item.copy(type = ScriptType.TOOL)
        grant(PermissionAction.DELETE)
        val registered = AgentTool(UUID.random(), item.key, item.name, "", scriptId = item.id)
        coEvery { tools.getByScriptId(item.id) } returns listOf(registered)
        coEvery { tools.delete(registered.id) } returns true
        coEvery { agents.getByKey("kit.script") } returns null
        coJustRun { scripts.delete(item.id) }
        val result = call(mutation("""deleteScript(id:"${item.id}")"""), auth(listOf("scripts:manage")))
        assertTrue(result.errors.isEmpty(), result.errors.toString())
        coVerify(exactly = 1) { tools.delete(registered.id) }
        coVerify(exactly = 1) { scripts.delete(item.id) }
    }
}
