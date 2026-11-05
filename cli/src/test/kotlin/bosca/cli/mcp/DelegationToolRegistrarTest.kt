package bosca.cli.mcp

import bosca.cli.api.DelegationApi
import bosca.cli.api.NetworkClient
import com.sun.net.httpserver.HttpServer
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*
import kotlin.uuid.Uuid

class DelegationToolRegistrarTest {
    private val conversation = Uuid.random()
    private val id = Uuid.random()
    private val requests = CopyOnWriteArrayList<JsonObject>()
    private val tokens = CopyOnWriteArrayList<String>()
    private lateinit var server: HttpServer
    private lateinit var network: NetworkClient
    private var status = "RUNNING"
    private var completeAfterRead = true
    private var httpStatus = 200
    private var missing = false

    @BeforeTest fun setup() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/graphql") { exchange ->
            val request = Json.parseToJsonElement(exchange.requestBody.readAllBytes().decodeToString()).jsonObject
            requests += request; tokens += exchange.requestHeaders.getFirst("Authorization")
            val operation = request.getValue("operationName").jsonPrimitive.content
            if (operation == "GetKitDelegation" && completeAfterRead) status = "COMPLETED"
            val child = """{"id":"$id","parentSessionId":"$conversation","parentTurnId":"$id","childSessionId":"$id","profile":"claude","depth":1,"runtimeSeconds":30,"deadline":"2026-10-28T01:00:00Z","status":"$status","partial":${status != "COMPLETED"},"failure":null,"result":{"summary":"Answer with evidence","artifacts":[{"metadataId":"$id","label":"Plan"}]}}"""
            val field = when (operation) {
                "KitDelegationSettings" -> "\"settings\":{\"profiles\":[\"claude\"],\"maxDepth\":1,\"maxActive\":1,\"maxChildren\":4,\"maxRuntimeSeconds\":60,\"totalRuntimeSeconds\":120}"
                "StartKitDelegation" -> "\"start\":$child"
                "GetKitDelegation" -> "\"get\":${if (missing) "null" else child}"
                "ListKitDelegations" -> "\"tasks\":[$child]"
                "WaitKitDelegation" -> "\"wait\":$child"
                "CancelKitDelegation" -> "\"cancel\":$child"
                "SubmitKitDelegation" -> "\"submit\":$child"
                else -> error(operation)
            }
            val body = """{"data":{"ai":{"delegations":{$field}}}}""".encodeToByteArray()
            exchange.sendResponseHeaders(httpStatus, body.size.toLong()); exchange.responseBody.use { it.write(body) }
        }
        server.start()
        network = NetworkClient("http://127.0.0.1:${server.address.port}/graphql").apply { tokenProvider = { "scoped-child-token" } }
    }
    @AfterTest fun cleanup() { server.stop(0); network.http.dispatcher.executorService.shutdown(); network.http.connectionPool.evictAll() }
    private suspend fun call(action: String, fields: JsonObject = JsonObject(emptyMap())) = DelegationToolRegistrar.handle(DelegationApi(network, conversation),
        JsonObject(fields + ("action" to JsonPrimitive(action)) + ("id" to JsonPrimitive(id.toString()))))

    @Test fun `typed operations pin the conversation token and retry identity`(): Unit = runBlocking {
        val input = buildJsonObject {
            put("requestId", id.toString()); put("brief", "Inspect plan"); put("expectedResult", "Summary"); put("profile", "claude"); put("runtimeSeconds", 30)
            put("artifacts", JsonArray(listOf(JsonPrimitive(id.toString())))); put("scopes", JsonArray(listOf(JsonPrimitive("content:view"))))
            putJsonArray("context") { add(buildJsonObject { put("kind", "GIT_FILE"); put("id", id.toString()); put("ref", "main"); put("path", "docs/plan.md") }) }
        }
        for (action in listOf("settings", "start", "start", "get", "list", "cancel", "submit")) {
            val result = call(action, buildJsonObject { put("input", input); putJsonObject("output") { put("summary", "Answer") }; put("sessionId", Uuid.random().toString()) })
            assertFalse(result.isError == true, result.toString())
            assertFalse(assertIs<TextContent>(result.content.single()).text.contains("scoped-child-token"))
            if (action != "settings") assertContains(result.content.toString(), "Answer with evidence")
        }
        assertEquals(7, requests.size)
        assertTrue(tokens.all { it == "Bearer scoped-child-token" })
        assertTrue(requests.all { it.getValue("variables").jsonObject.getValue("sessionId").jsonPrimitive.content == conversation.toString() })
        assertEquals(requests[1]["variables"], requests[2]["variables"])
        val sent = requests[1].getValue("variables").jsonObject.getValue("input").jsonObject
        input.forEach { (key, value) -> assertEquals(value, sent[key]) }
        assertEquals("Answer", requests.last().getValue("variables").jsonObject.getValue("output").jsonObject.getValue("summary").jsonPrimitive.content)
        assertTrue(call("list", buildJsonObject { put("limit", 101) }).isError == true)
        assertEquals(7, requests.size)
    }

    @Test fun `wait commits once and polls reads until terminal output`(): Unit = runBlocking {
        val result = call("wait")
        assertFalse(result.isError == true)
        assertContains(assertIs<TextContent>(result.content.single()).text, "COMPLETED")
        assertEquals(listOf("WaitKitDelegation", "GetKitDelegation"), requests.map { it.getValue("operationName").jsonPrimitive.content })
    }

    @Test fun `unfinished wait is bounded and never repeats the mutation`(): Unit = runBlocking {
        completeAfterRead = false
        val result = call("wait")
        assertFalse(result.isError == true)
        assertContains(assertIs<TextContent>(result.content.single()).text, "RUNNING")
        assertEquals(1, requests.count { it.getValue("operationName").jsonPrimitive.content == "WaitKitDelegation" })
        assertEquals(20, requests.count { it.getValue("operationName").jsonPrimitive.content == "GetKitDelegation" })
    }

    @Test fun `cancelling a local wait propagates cancellation without issuing child cancel or another start`(): Unit = runBlocking {
        completeAfterRead = false
        val waiting = async { call("wait") }
        withTimeout(5000) { while (requests.isEmpty()) delay(10) }
        waiting.cancel()
        assertFailsWith<CancellationException> { waiting.await() }
        assertEquals(listOf("WaitKitDelegation"), requests.map { it.getValue("operationName").jsonPrimitive.content })
    }

    @Test fun `a rejected scoped request is surfaced without refresh or automatic resubmission`(): Unit = runBlocking {
        httpStatus = 401
        assertTrue(call("cancel").isError == true)
        assertEquals(1, requests.size)
        assertContains(requireNotNull(DelegationToolRegistrar.tool.description), "Partial output")
    }

    @Test fun `missing task stays absent and a lost wait target fails without another mutation`(): Unit = runBlocking {
        missing = true
        completeAfterRead = false
        assertEquals("null", assertIs<TextContent>(call("get").content.single()).text)
        val waiting = call("wait")
        assertTrue(waiting.isError == true)
        assertContains(assertIs<TextContent>(waiting.content.single()).text, "no longer exists")
        assertEquals(listOf("GetKitDelegation", "WaitKitDelegation", "GetKitDelegation"), requests.map { it.getValue("operationName").jsonPrimitive.content })
    }
}
