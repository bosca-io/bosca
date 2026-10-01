package bosca.ai.kit.tools.pipeline

import bosca.ai.kit.agents.pipeline.PipelineServices
import bosca.graphql.GraphQLRequest
import bosca.graphql.GraphQLService
import bosca.pipelines.model.Pipeline
import bosca.pipelines.service.PipelineService
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PipelineToolsTest {
    private val authentication = mockk<AuthenticationContext>(relaxed = true)
    private val pipelineService = mockk<PipelineService>()
    private val graphQLService = mockk<GraphQLService>()
    private val groups = mockk<GroupEvaluator>()
    private val json = Json { ignoreUnknownKeys = true }
    private val services = PipelineServices(pipelineService, graphQLService, groups, json)

    init {
        every { groups.verifyHasAdminGroup(authentication) } just runs
    }

    @Test
    fun `validation returns the engine violation verbatim`() = runTest {
        coEvery { pipelineService.validateGraph(any()) } returns "edge e1: UUID cannot connect to STRING"

        val output = ValidatePipelineGraphTool(services).execute(
            authentication,
            ValidatePipelineGraphTool.Input("""{"nodes":[],"edges":[]}"""),
        )

        assertFalse(output.valid)
        assertEquals(listOf("edge e1: UUID cannot connect to STRING"), output.violations)
    }

    @Test
    fun `get by key returns the editable graph and safety fields`() = runTest {
        val existing = pipeline(version = 7).copy(tags = listOf("welcome"), maxConcurrentRuns = 2)
        coEvery { pipelineService.getByKey("welcome") } returns existing
        coEvery { pipelineService.graphAsJsonElement(existing) } returns json.parseToJsonElement(
            """{"nodes":[{"type":"input","id":"input"}],"edges":[]}""",
        )

        val output = GetPipelineTool(services).execute(
            authentication,
            GetPipelineTool.Input(key = "welcome"),
        )

        assertTrue(output.found, output.error)
        assertEquals(7L, output.version)
        assertEquals(listOf("welcome"), output.tags)
        assertEquals(2, output.maxConcurrentRuns)
        assertTrue(output.graphJson.contains("\"input\""))
        assertEquals("/pipelines/${existing.id}", output.editorPath)
    }

    @Test
    fun `create refuses activation before validation or mutation`() = runTest {
        val output = SavePipelineTool(services).execute(
            authentication,
            saveInput(triggered = true),
        )

        assertFalse(output.success)
        assertTrue(output.error.orEmpty().contains("created inactive"))
        coVerify(exactly = 0) { pipelineService.validateGraph(any()) }
        coVerify(exactly = 0) { graphQLService.execute(any(), any()) }
    }

    @Test
    fun `inactive create validates lays out and saves through GraphQL`() = runTest {
        coEvery { pipelineService.validateGraph(any()) } returns null
        val request = slot<GraphQLRequest>()
        coEvery { graphQLService.execute(authentication, capture(request)) } returns json.parseToJsonElement(
            """{"data":{"pipelines":{"save":{"id":"550e8400-e29b-41d4-a716-446655440000","key":"welcome","name":"Welcome","version":1,"triggered":false,"api":false,"public":false,"schedule":null}}}}""",
        )

        val output = SavePipelineTool(services).execute(authentication, saveInput())

        assertTrue(output.success, output.error)
        assertEquals("/pipelines/550e8400-e29b-41d4-a716-446655440000", output.editorPath)
        val input = request.captured.variables!!.getValue("input").jsonObject
        assertFalse(input.getValue("triggered").jsonPrimitive.content.toBoolean())
        assertFalse(input.getValue("api").jsonPrimitive.content.toBoolean())
        assertFalse(input.getValue("public").jsonPrimitive.content.toBoolean())
        val nodes = input.getValue("graph").jsonObject.getValue("nodes").jsonArray
        assertNotNull(nodes.first().jsonObject["position"])
    }

    @Test
    fun `optimistic lock conflict tells the agent to refetch`() = runTest {
        val id = UUID.parse("550e8400-e29b-41d4-a716-446655440000")
        coEvery { pipelineService.get(id) } returns pipeline(id = id, version = 4)
        coEvery { pipelineService.validateGraph(any()) } returns null
        coEvery { graphQLService.execute(authentication, any()) } returns json.parseToJsonElement(
            """{"errors":[{"message":"optimistic version conflict"}],"data":{"pipelines":null}}""",
        )

        val output = SavePipelineTool(services).execute(
            authentication,
            saveInput(id = id.toString(), version = 3),
        )

        assertFalse(output.success)
        assertTrue(output.error.orEmpty().contains("Re-fetch with get_pipeline"))
    }

    @Test
    fun `update refuses a newly enabled trigger without confirmation`() = runTest {
        val id = UUID.parse("550e8400-e29b-41d4-a716-446655440000")
        coEvery { pipelineService.get(id) } returns pipeline(id = id)

        val output = SavePipelineTool(services).execute(
            authentication,
            saveInput(id = id.toString(), version = 1, triggered = true),
        )

        assertFalse(output.success)
        assertTrue(output.error.orEmpty().contains("explicit user confirmation"))
        coVerify(exactly = 0) { pipelineService.validateGraph(any()) }
        coVerify(exactly = 0) { graphQLService.execute(any(), any()) }
    }

    @Test
    fun `node catalog maps discriminator slots and settings from GraphQL`() = runTest {
        coEvery { graphQLService.execute(authentication, any()) } returns json.parseToJsonElement(
            """{
              "data":{"pipelines":{"nodeTypes":[{
                "key":"executeScript","label":"Execute Script","category":"ACTION","group":"Scripting","subgroup":null,
                "description":"Run a script","inputs":[{"name":"in","kind":"OBJECT","typeLabel":"Input","description":null,"type":"example.Input","schema":null,"required":true,"structure":[{"name":"id","type":"UUID"}]}],
                "outputs":[{"name":"out","kind":"OBJECT","error":false,"type":"example.Output","typeLabel":"Output","description":null,"structure":null}],
                "settings":[{"name":"scriptId","control":"REFERENCE","label":"Script","description":null,"placeholder":null,"default":null,"required":true,"secret":false,"mono":false,"language":null,"reference":"SCRIPT","options":[],"fields":[],"itemLabel":null,"group":null,"visibleWhenSetting":null,"visibleWhenEquals":null}]
              }]}}
            }""",
        )

        val output = ListPipelineNodeTypesTool(services).execute(authentication, ListPipelineNodeTypesTool.Input())

        assertTrue(output.success, output.error)
        assertEquals("executeScript", output.nodeTypes.single().key)
        assertEquals("OBJECT", output.nodeTypes.single().inputs.single().kind)
        assertEquals("SCRIPT", output.nodeTypes.single().settings.single().reference)
    }

    @Test
    fun `real execution refuses an unconfirmed run`() = runTest {
        val output = RunPipelineTool(services).execute(
            authentication,
            RunPipelineTool.Input("550e8400-e29b-41d4-a716-446655440000", "{}"),
        )

        assertFalse(output.success)
        assertTrue(output.error.orEmpty().contains("explicit user confirmation"))
        coVerify(exactly = 0) { graphQLService.execute(any(), any()) }
    }

    @Test
    fun `confirmed real execution returns its durable run handle`() = runTest {
        coEvery { graphQLService.execute(authentication, any()) } returns json.parseToJsonElement(
            """{"data":{"pipelines":{"run":{"ok":true,"runId":"1aa9f98e-41a4-452f-a5fb-b07879175053","status":"SUSPENDED","output":null,"error":null}}}}""",
        )

        val output = RunPipelineTool(services).execute(
            authentication,
            RunPipelineTool.Input(
                id = "550e8400-e29b-41d4-a716-446655440000",
                payloadJson = "{\"id\":1}",
                confirmed = true,
            ),
        )

        assertTrue(output.success, output.error)
        assertEquals("1aa9f98e-41a4-452f-a5fb-b07879175053", output.runId)
        assertEquals("SUSPENDED", output.status)
    }

    @Test
    fun `dry run returns the complete per-node trace`() = runTest {
        coEvery { graphQLService.execute(authentication, any()) } returns json.parseToJsonElement(
            """{"data":{"pipelines":{"dryRun":{"outputs":{"input":{"id":1}},"actions":{"send":{"to":"test"}},"errors":{},"skipped":{"branch":"condition false"},"error":null}}}}""",
        )

        val output = DryRunPipelineTool(services).execute(
            authentication,
            DryRunPipelineTool.Input("550e8400-e29b-41d4-a716-446655440000", payloadJson = "{}"),
        )

        assertTrue(output.success, output.error)
        assertTrue((output.outputs as JsonObject).containsKey("input"))
        assertTrue((output.actions as JsonObject).containsKey("send"))
        assertTrue((output.skipped as JsonObject).containsKey("branch"))
    }

    @Test
    fun `execution tools defer authorization to the caller-aware GraphQL boundary`() = runTest {
        every { groups.verifyHasAdminGroup(authentication) } throws SecurityException("not an administrator")
        coEvery { graphQLService.execute(authentication, any()) } returnsMany listOf(
            json.parseToJsonElement(
                """{"data":{"pipelines":{"dryRun":{"outputs":{},"actions":{},"errors":{},"skipped":{},"error":null}}}}""",
            ),
            json.parseToJsonElement(
                """{"data":{"pipelines":{"run":{"ok":true,"runId":"1aa9f98e-41a4-452f-a5fb-b07879175053","status":"OK","output":null,"error":null}}}}""",
            ),
        )

        val dryRun = DryRunPipelineTool(services).execute(
            authentication,
            DryRunPipelineTool.Input("550e8400-e29b-41d4-a716-446655440000", payloadJson = "{}"),
        )
        val run = RunPipelineTool(services).execute(
            authentication,
            RunPipelineTool.Input(
                id = "550e8400-e29b-41d4-a716-446655440000",
                payloadJson = "{}",
                confirmed = true,
            ),
        )

        assertTrue(dryRun.success, dryRun.error)
        assertTrue(run.success, run.error)
        coVerify(exactly = 0) { groups.verifyHasAdminGroup(authentication) }
        coVerify(exactly = 2) { graphQLService.execute(authentication, any()) }
    }

    private fun saveInput(
        id: String = "",
        version: Long = 0,
        triggered: Boolean = false,
    ) = SavePipelineTool.Input(
        id = id,
        name = "Welcome",
        acceptedInputType = "JSON",
        graphJson = """{"nodes":[{"type":"input","id":"input"}],"edges":[]}""",
        version = version,
        key = "welcome",
        triggered = triggered,
    )

    private fun pipeline(
        id: UUID = UUID.parse("550e8400-e29b-41d4-a716-446655440000"),
        version: Long = 1,
    ) = Pipeline(
        id = id,
        name = "Welcome",
        acceptedInputType = "JSON",
        key = "welcome",
        version = version,
    )
}
