package bosca.cli.mcp

import bosca.cli.api.NetworkClient
import bosca.cli.api.PipelineApi
import com.sun.net.httpserver.HttpServer
import io.modelcontextprotocol.kotlin.sdk.types.ResourceLink
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.net.InetSocketAddress
import kotlin.test.*

class PipelineToolRegistrarTest {
    private val id = "12345678-1234-1234-1234-123456789012"
    private val runId = "22345678-1234-1234-1234-123456789012"
    private val graph = """{"nodes":[{"id":"in","type":"input","position":{"x":0,"y":0}}],"edges":[]}"""
    private var pipeline = Json.parseToJsonElement("""{"id":"$id","key":"fixture","name":"Fixture","description":"Keep this","acceptedInputType":"JSON","tags":["release"],"version":3,"graph":$graph,"triggered":false,"api":false,"public":false,"schedule":null,"maxConcurrentRuns":2,"maxRunsPerMinute":4,"gitRepositoryId":null,"gitPath":null,"lastSyncError":null,"inputSchema":null,"outputType":"JSON","outputSchema":null,"hasOutput":true}""").jsonObject
    private val requests = mutableListOf<JsonObject>()
    private val tokens = mutableListOf<String>()
    private lateinit var server: HttpServer
    private lateinit var network: NetworkClient
    private lateinit var api: PipelineApi
    private var valid = true
    private var failure: String? = null

    @BeforeTest fun setup() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/graphql") { exchange ->
            val request = Json.parseToJsonElement(exchange.requestBody.readAllBytes().decodeToString()).jsonObject
            requests.add(request); tokens.add(exchange.requestHeaders.getFirst("Authorization"))
            val data = when (request.getValue("operationName").jsonPrimitive.content) {
                "ListManagedPipelines" -> """{"all":[$pipeline]}"""
                "GetManagedPipeline" -> """{"pipeline":$pipeline}"""
                "GetManagedPipelineByKey" -> """{"pipelineByKey":$pipeline}"""
                "GetPipelineCatalog" -> """{"types":["JSON"],"shapes":[],"nodeTypes":[]}"""
                "ValidateManagedPipeline" -> """{"validateGraph":{"valid":$valid,"errors":["fixture violation"],"graph":$graph}}"""
                "SaveManagedPipeline" -> """{"save":$pipeline}"""
                "DryRunManagedPipeline" -> """{"dryRun":{"outputs":{},"actions":{"send":{"message":"preview"}},"errors":{},"skipped":{},"error":null}}"""
                "RunManagedPipeline" -> """{"run":{"ok":true,"runId":"$runId","status":"SUSPENDED","output":null,"error":null}}"""
                "GetManagedPipelineRun" -> """{"run":{"id":"$runId","pipelineId":"$id","status":"OK","eventName":"manual","input":[1,2],"output":{"result":7},"error":null,"createdAt":"2026-10-24T00:00:00Z","modifiedAt":"2026-10-24T00:01:00Z","awaitingNodes":[],"completedNodeIds":["in"],"nodes":[],"steps":[]}}"""
                else -> error("Unexpected operation")
            }
            val response = (if (failure != null) """{"errors":[{"message":"$failure"}]}""" else """{"data":{"pipelines":$data}}""").encodeToByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        network = NetworkClient("http://127.0.0.1:${server.address.port}/graphql").apply { tokenProvider = { "bsk_pipeline_fixture" } }
        api = PipelineApi(network)
    }
    @AfterTest fun cleanup() { server.stop(0); network.http.dispatcher.executorService.shutdown(); network.http.connectionPool.evictAll() }
    private suspend fun call(action: String, extra: String = "{}") = PipelineToolRegistrar.handle(api, buildJsonObject {
        put("action", action); Json.parseToJsonElement(extra).jsonObject.forEach { (key, value) -> put(key, value) }
    })
    private fun operations() = requests.map { it.getValue("operationName").jsonPrimitive.content }
    private fun variables() = requests.last().getValue("variables").jsonObject

    @Test fun `discovery uses typed operations and direct scoped authentication`() = runBlocking {
        assertFalse(call("list", """{"offset":20,"limit":10,"search":"release"}""").isError == true)
        assertEquals(JsonPrimitive(20), variables()["offset"])
        assertEquals(JsonPrimitive("release"), variables()["search"])
        val get = call("get", """{"key":"fixture"}""")
        assertFalse(get.isError == true, get.toString())
        assertEquals("bosca://pipelines/$id", assertIs<ResourceLink>(get.content[1]).uri)
        assertFalse(call("catalog").isError == true)
        assertEquals(listOf("ListManagedPipelines", "GetManagedPipelineByKey", "GetPipelineCatalog"), operations())
        assertTrue(tokens.all { it == "Bearer bsk_pipeline_fixture" })
    }

    @Test fun `save preserves omitted fields and enforces optimistic version and engine layout`() = runBlocking {
        val result = call("save", """{"id":"$id","expectedVersion":3,"pipeline":{"name":"Updated","maxConcurrentRuns":null}}""")
        assertFalse(result.isError == true, result.toString())
        val input = variables().getValue("input").jsonObject
        assertEquals(JsonPrimitive(3), input["version"])
        assertEquals(JsonPrimitive("Updated"), input["name"])
        assertEquals(pipeline["description"], input["description"])
        assertEquals(pipeline["tags"], input["tags"])
        assertEquals(Json.parseToJsonElement(graph), input["graph"])
        assertNull(Json.decodeFromJsonElement(bosca.graphql.gen.PipelineInput.serializer(), input).maxConcurrentRuns)
        assertEquals(listOf("GetManagedPipeline", "ValidateManagedPipeline", "SaveManagedPipeline"), operations())
    }

    @Test fun `the first edit accepts version zero`() = runBlocking {
        pipeline = JsonObject(pipeline + ("version" to JsonPrimitive(0)))
        assertFalse(call("save", """{"id":"$id","expectedVersion":0,"pipeline":{"name":"First edit"}}""").isError == true)
        assertEquals(JsonPrimitive(0), variables().getValue("input").jsonObject["version"])
        assertEquals(JsonPrimitive(0), requireNotNull(PipelineToolRegistrar.tool.inputSchema.properties).getValue("expectedVersion").jsonObject["minimum"])
    }

    @Test fun `creation is inactive and preview never saves`() = runBlocking {
        val definition = """{"name":"New","acceptedInputType":"JSON","graph":$graph}"""
        val preview = call("save", """{"preview":true,"pipeline":$definition}""")
        assertFalse(preview.isError == true, preview.toString())
        assertContains(assertIs<TextContent>(preview.content.single()).text, "\"preview\":true")
        assertEquals(listOf("ValidateManagedPipeline"), operations())
        assertFalse(call("save", """{"pipeline":$definition}""").isError == true)
        assertEquals(JsonPrimitive(false), variables().getValue("input").jsonObject["triggered"])
        assertEquals(1, operations().count { it == "SaveManagedPipeline" })
    }

    @Test fun `invalid graphs and stale versions never save`() = runBlocking {
        for (extra in listOf("""{"id":"$id","expectedVersion":2,"pipeline":{"name":"New"}}""", """{"id":"$id","pipeline":{"name":"New"}}""")) {
            assertTrue(call("save", extra).isError == true)
        }
        valid = false
        assertTrue(call("save", """{"id":"$id","expectedVersion":3,"pipeline":{"name":"New"}}""").isError == true)
        assertTrue(call("save", """{"pipeline":{"id":"$id"}}""").isError == true)
        assertTrue(operations().none { it == "SaveManagedPipeline" })
    }

    @Test fun `new activation is rejected and later activation needs explicit authorization`() = runBlocking {
        assertFalse(call("save", """{"id":"$id","expectedVersion":3,"preview":true,"pipeline":{"triggered":true}}""").isError == true)
        assertEquals(listOf("GetManagedPipeline", "ValidateManagedPipeline"), operations())
        for (field in listOf("\"triggered\":true", "\"api\":true", "\"public\":true", "\"schedule\":\"0 * * * *\"")) {
            assertTrue(call("save", """{"confirmedActivation":true,"pipeline":{"name":"New","acceptedInputType":"JSON","graph":$graph,$field}}""").isError == true)
            assertTrue(call("save", """{"id":"$id","expectedVersion":3,"pipeline":{$field}}""").isError == true)
            assertFalse(call("save", """{"id":"$id","expectedVersion":3,"confirmedActivation":true,"pipeline":{$field}}""").isError == true)
        }
        assertEquals(4, operations().count { it == "SaveManagedPipeline" })
    }

    @Test fun `dry runs preserve input and real runs require confirmation without automatic retry`() = runBlocking {
        assertTrue(call("run", """{"id":"$id","payload":{}}""").isError == true)
        assertTrue(requests.isEmpty())
        assertFalse(call("dry_run", """{"id":"$id","payload":[1,2]}""").isError == true)
        assertEquals(Json.parseToJsonElement("[1,2]"), variables()["payload"])
        val started = call("run", """{"id":"$id","payload":null,"confirmed":true}""")
        assertFalse(started.isError == true, started.toString())
        assertContains(assertIs<TextContent>(started.content.first()).text, "SUSPENDED")
        assertEquals("bosca://pipelines/runs/$runId", assertIs<ResourceLink>(started.content[1]).uri)
        val done = call("run_status", """{"runId":"$runId"}""")
        assertContains(assertIs<TextContent>(done.content.single()).text, "\"result\":7")
        assertEquals(listOf("DryRunManagedPipeline", "RunManagedPipeline", "GetManagedPipelineRun"), operations())
    }

    @Test fun `invalid selectors and pagination never call the API`() = runBlocking {
        for ((action, extra) in listOf("get" to "{}", "get" to """{"id":"$id","key":"fixture"}""", "list" to """{"limit":101}""", "list" to """{"offset":-1}""", "validate" to "{}")) {
            assertTrue(call(action, extra).isError == true)
        }
        assertTrue(requests.isEmpty())
    }

    @Test fun `API errors are visible and cancellation propagates`(): Unit = runBlocking {
        failure = "scope denied"
        assertTrue(call("run", """{"id":"$id","payload":{},"confirmed":true}""").isError == true)
        assertEquals(listOf("RunManagedPipeline"), operations())
        network.tokenProvider = { throw CancellationException("cancelled") }
        assertFailsWith<CancellationException> { call("catalog") }
    }
}
