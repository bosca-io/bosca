package bosca.cli.mcp

import bosca.cli.api.NetworkClient
import bosca.cli.api.ScriptingApi
import com.sun.net.httpserver.HttpServer
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.net.InetSocketAddress
import kotlin.test.*

class ScriptingToolRegistrarTest {
    private val id = "12345678-1234-1234-1234-123456789012"
    private val script = Json.parseToJsonElement("""{"id":"$id","key":"fixture","name":"Fixture","description":"Keep this","source":"main { null }","type":"GENERAL","version":3,"enabled":true,"public":false,"inputSchema":{"type":"object"},"outputSchema":null,"configuration":{"keep":true}}""").jsonObject
    private val requests = mutableListOf<JsonObject>()
    private val tokens = mutableListOf<String>()
    private lateinit var server: HttpServer
    private lateinit var network: NetworkClient
    private lateinit var api: ScriptingApi
    private var failure: String? = null

    @BeforeTest fun setup() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/graphql") { exchange ->
            val request = Json.parseToJsonElement(exchange.requestBody.readAllBytes().decodeToString()).jsonObject
            requests.add(request); tokens.add(exchange.requestHeaders.getFirst("Authorization"))
            val operation = request.getValue("operationName").jsonPrimitive.content
            val data = when (operation) {
                "ListManagedScripts" -> """{"all":[$script]}"""
                "GetManagedScript" -> """{"script":$script}"""
                "GetManagedScriptByKey" -> """{"scriptByKey":$script}"""
                "AddManagedScript" -> """{"addScript":$script}"""
                "EditManagedScript" -> """{"editScript":$script}"""
                "EnableManagedScript" -> """{"enableScript":true}"""
                "DisableManagedScript" -> """{"disableScript":true}"""
                "DeleteManagedScript" -> """{"deleteScript":true}"""
                "ExecuteManagedScript" -> """{"executeScript":[1,"result",null]}"""
                "ValidateManagedScript" -> """{"validateScript":{"valid":true,"compiled":true,"errors":[]}}"""
                else -> error("Unexpected operation $operation")
            }
            val response = (if (failure != null) """{"errors":[{"message":"$failure"}]}""" else """{"data":{"scripts":$data}}""").encodeToByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        network = NetworkClient("http://127.0.0.1:${server.address.port}/graphql").apply { tokenProvider = { "bsk_script_fixture" } }
        api = ScriptingApi(network)
    }
    @AfterTest fun cleanup() {
        server.stop(0); network.http.dispatcher.executorService.shutdown(); network.http.connectionPool.evictAll()
    }
    private suspend fun call(action: String, extra: String = "{}") = ScriptingToolRegistrar.handle(api, buildJsonObject {
        put("action", action)
        Json.parseToJsonElement(extra).jsonObject.forEach { (key, value) -> put(key, value) }
    })
    private fun operations() = requests.map { it.getValue("operationName").jsonPrimitive.content }
    private fun variables() = requests.last().getValue("variables").jsonObject

    @Test fun `discovery and retrieval use scoped identity and offset pagination`() = runBlocking {
        assertFalse(call("list", """{"offset":20,"limit":10,"type":"GENERAL"}""").isError == true)
        assertEquals(20, variables().getValue("offset").jsonPrimitive.int)
        assertEquals(10, variables().getValue("limit").jsonPrimitive.int)
        val found = call("get", """{"key":"fixture"}""")
        assertFalse(found.isError == true)
        assertContains(assertIs<TextContent>(found.content.single()).text, "Keep this")
        assertEquals(listOf("ListManagedScripts", "GetManagedScriptByKey"), operations())
        assertTrue(tokens.all { it == "Bearer bsk_script_fixture" })
    }
    @Test fun `edit preserves omitted fields and null clears JSON with optimistic version`() = runBlocking {
        val result = call("edit", """{"id":"$id","expectedVersion":3,"script":{"name":"New name","inputSchema":null}}""")
        assertFalse(result.isError == true, result.toString())
        val input = variables().getValue("script").jsonObject
        assertEquals(JsonPrimitive("New name"), input["name"])
        assertEquals(script["description"], input["description"])
        assertEquals(script["source"], input["source"])
        assertEquals(script["configuration"], input["configuration"])
        assertNull(Json.decodeFromJsonElement(bosca.graphql.gen.ScriptInput.serializer(), input).inputSchema)
        assertEquals(JsonPrimitive(3), variables()["expectedVersion"])
        assertEquals(listOf("GetManagedScript", "EditManagedScript"), operations())
    }
    @Test fun `dry runs validate without saving or registering tools`() = runBlocking {
        val result = call("create", """{"dryRun":true,"compile":true,"script":{"key":"new","name":"New","source":"main { null }","type":"TOOL"}}""")
        assertFalse(result.isError == true, result.toString())
        assertContains(assertIs<TextContent>(result.content.single()).text, "\"dryRun\":true")
        assertEquals(listOf("ValidateManagedScript"), operations())
        assertEquals(JsonPrimitive(true), variables()["compile"])
        val edit = call("edit", """{"id":"$id","expectedVersion":3,"dryRun":true,"script":{"description":""}}""")
        assertFalse(edit.isError == true, edit.toString())
        assertEquals(listOf("ValidateManagedScript", "GetManagedScript", "ValidateManagedScript"), operations())
        assertEquals(JsonPrimitive(id), variables()["id"])
    }
    @Test fun `create lifecycle and execution preserve arbitrary JSON and result`() = runBlocking {
        assertFalse(call("create", """{"script":{"key":"new","name":"New","source":"main { null }"}}""").isError == true)
        for (action in listOf("enable", "disable", "delete")) assertFalse(call(action, """{"id":"$id"}""").isError == true)
        val result = call("execute", """{"id":"$id","input":[1,2]}""")
        assertFalse(result.isError == true, result.toString())
        assertEquals(Json.parseToJsonElement("[1,2]"), variables()["input"])
        assertContains(assertIs<TextContent>(result.content.single()).text, "[1,\"result\",null]")
        assertEquals(1, operations().count { it == "ExecuteManagedScript" })
    }
    @Test fun `stale edits and invalid fields never mutate`() = runBlocking {
        for (extra in listOf("""{"id":"$id","expectedVersion":2,"script":{"name":"New"}}""", """{"id":"$id","script":{"name":"New"}}""", """{"id":"$id","expectedVersion":3,"script":{"unexpected":true}}""")) {
            assertTrue(call("edit", extra).isError == true)
        }
        assertTrue(operations().all { it == "GetManagedScript" })
        requests.clear()
        assertTrue(call("list", """{"offset":-1}""").isError == true)
        assertTrue(call("get", """{"id":"$id","key":"fixture"}""").isError == true)
        assertTrue(call("validate", """{"source":""}""").isError == true)
        assertTrue(requests.isEmpty())
    }
    @Test fun `backend failures are reported without retry and cancellation propagates`(): Unit = runBlocking {
        failure = "scope denied"
        assertTrue(call("create", """{"script":{"key":"new","name":"New","source":"main { null }"}}""").isError == true)
        assertEquals(listOf("AddManagedScript"), operations())
        network.tokenProvider = { throw CancellationException("cancelled") }
        assertFailsWith<CancellationException> { call("validate", """{"source":"main { null }"}""") }
    }
}
