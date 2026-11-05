package bosca.cli.mcp

import bosca.cli.api.ContentScripture
import bosca.cli.api.NetworkClient
import com.sun.net.httpserver.HttpServer
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.net.InetSocketAddress
import kotlin.test.*

class ScriptureToolRegistrarTest {
    private val id = "12345678-1234-1234-1234-123456789012"
    private val identity = """"metadataId":"$id","version":2,"variant":"reader","defaultVariant":true,"name":"Fixture Bible","nameLocal":"Fixture Bible","abbreviation":"FB","abbreviationLocal":"FB""""
    private val reference = """{"usfm":"JHN.3.16","human":"John 3:16","humanShort":"Jn 3:16"}"""
    private val passage = """{"reference":$reference,"component":[{"_":"t","text":"Exact fixture text — selected verse.","style":null}],"attribution":{"copyright":"Fixture copyright","citation":"Fixture citation","informationUrl":"https://example.com/license"}}"""
    private val requests = mutableListOf<JsonObject>()
    private val tokens = mutableListOf<String>()
    private var mode = "ok"
    private lateinit var server: HttpServer
    private lateinit var network: NetworkClient
    private lateinit var api: ContentScripture

    @BeforeTest fun setup() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/graphql") { exchange ->
            val request = Json.parseToJsonElement(exchange.requestBody.readAllBytes().decodeToString()).jsonObject
            requests.add(request)
            tokens.add(exchange.requestHeaders.getFirst("Authorization"))
            val operation = request.getValue("operationName").jsonPrimitive.content
            val data = when (operation) {
                "ListScriptureBibles" -> """{"findMetadata":[{"id":"$id","bibles":[{$identity}]}]}"""
                "GetScriptureBooks" -> """{"metadata":{"bible":{$identity,"books":[{"nameLong":"John","nameShort":"John","abbreviation":"Jn","reference":{"usfm":"JHN"},"chapters":[{"reference":{"usfm":"JHN.3","human":"John 3"}}]}]}}}"""
                "ResolveScriptureReferences" -> """{"metadata":{"bible":{$identity,"references":${if (mode == "empty") "[]" else "[$reference]"}}}}"""
                "GetScriptureVerses" -> """{"metadata":{"bible":{$identity,"aiContext":[$passage]}}}"""
                else -> error("Unexpected operation $operation")
            }
            val response = when (mode) {
                "denied" -> """{"errors":[{"message":"AI_CONTEXT denied"}]}"""
                "missing" -> """{"data":{"content":{"metadata":null}}}"""
                "unauthorized" -> "{}"
                else -> """{"data":{"content":$data}}"""
            }.encodeToByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(if (mode == "unauthorized") 401 else 200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        network = NetworkClient("http://127.0.0.1:${server.address.port}/graphql").apply { tokenProvider = { "bsk_scripture_fixture" } }
        api = ContentScripture(network)
    }

    @AfterTest fun teardown() {
        server.stop(0)
        network.http.dispatcher.executorService.shutdown()
        network.http.connectionPool.evictAll()
    }

    private suspend fun call(action: String, extra: JsonObject = JsonObject(emptyMap())) = ScriptureToolRegistrar.handle(api, buildJsonObject {
        put("action", action); put("id", id); put("version", 2); put("variant", "reader"); put("reference", "John 3:16")
        extra.forEach { (key, value) -> put(key, value) }
    })

    private fun text(result: CallToolResult): String {
        assertFalse(result.isError == true, result.toString())
        return assertIs<TextContent>(result.content.single()).text
    }

    @Test fun `discovery and reference reads preserve edition identity without requesting text`() = runBlocking {
        val listed = text(call("list", buildJsonObject { put("offset", 20); put("limit", 10) }))
        assertContains(listed, "Fixture Bible")
        assertContains(text(call("books")), "John 3")
        assertContains(text(call("references")), "JHN.3.16")
        mode = "empty"
        assertContains(text(call("references")), "\"references\":[]")
        assertEquals(listOf("ListScriptureBibles", "GetScriptureBooks", "ResolveScriptureReferences", "ResolveScriptureReferences"), requests.map { it.getValue("operationName").jsonPrimitive.content })
        assertEquals(20, requests.first().getValue("variables").jsonObject.getValue("offset").jsonPrimitive.int)
        assertEquals(10, requests.first().getValue("variables").jsonObject.getValue("limit").jsonPrimitive.int)
        requests.drop(1).forEach {
            val variables = it.getValue("variables").jsonObject
            assertEquals(id, variables.getValue("id").jsonPrimitive.content)
            assertEquals(2, variables.getValue("version").jsonPrimitive.int)
            assertEquals("reader", variables.getValue("variant").jsonPrimitive.content)
            assertFalse(it.getValue("query").jsonPrimitive.content.contains("aiContext("))
        }
        assertTrue(tokens.all { it == "Bearer bsk_scripture_fixture" })
    }

    @Test fun `passage reads use AI context and preserve exact returned text and attribution`() = runBlocking {
        val result = Json.parseToJsonElement(text(call("verses"))).jsonObject.getValue("content").jsonObject
            .getValue("metadata").jsonObject.getValue("bible").jsonObject.getValue("aiContext").jsonArray.single()
        assertEquals(Json.parseToJsonElement(passage), result)
        val query = requests.single().getValue("query").jsonPrimitive.content
        assertContains(query, "aiContext(human:")
        assertFalse(query.contains("find(human:"))
        assertEquals("Bearer bsk_scripture_fixture", tokens.single())
    }

    @Test fun `denial missing edition and unauthorized tokens report errors without fallback or retry`() = runBlocking {
        for (failure in listOf("denied", "missing", "unauthorized")) {
            mode = failure
            val result = call("verses")
            assertTrue(result.isError == true, result.toString())
            assertFalse(result.toString().contains("Exact fixture text"))
        }
        assertEquals(3, requests.size)
        assertTrue(requests.all { it.getValue("operationName").jsonPrimitive.content == "GetScriptureVerses" })
    }

    @Test fun `invalid input makes no request and cancellation propagates`() = runBlocking {
        for (extra in listOf(buildJsonObject { put("version", "invalid") }, buildJsonObject { put("version", 0) }, buildJsonObject { put("id", "invalid") }, buildJsonObject { put("reference", "") })) {
            assertTrue(call("verses", extra).isError == true)
        }
        assertTrue(call("list", buildJsonObject { put("limit", 101) }).isError == true)
        assertTrue(call("list", buildJsonObject { put("offset", -1) }).isError == true)
        assertTrue(requests.isEmpty())
        network.tokenProvider = { throw CancellationException("cancelled") }
        assertFailsWith<CancellationException> { call("verses") }
        Unit
    }
}
