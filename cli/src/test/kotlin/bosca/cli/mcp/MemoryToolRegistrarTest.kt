package bosca.cli.mcp

import bosca.cli.api.MemoryApi
import bosca.cli.api.NetworkClient
import com.sun.net.httpserver.HttpServer
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.net.InetSocketAddress
import kotlin.test.*
import kotlin.uuid.Uuid

class MemoryToolRegistrarTest {
    @Test fun `memory MCP operations preserve retry identity revisions and runtime conversation under the scoped transport`() = runBlocking {
        val id = Uuid.random().toString()
        val owner = Uuid.random().toString()
        val conversation = Uuid.random()
        val requestId = Uuid.random().toString()
        val requests = mutableListOf<JsonObject>()
        val tokens = mutableListOf<String>()
        val memory = """{"id":"$id","version":1,"revision":"revision-one","statement":"Prefer short answers","sourceChanged":false,"tags":[],"created":"2026-10-22T00:00:00Z","modified":null,"provenance":{"scope":"PERSONAL","ownerId":"$owner","collectionId":null,"sourceMetadataId":null,"sourceVersion":null,"sourceRevision":null,"conversationId":"$conversation","expires":null}}"""
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/graphql") { exchange ->
            val request = Json.parseToJsonElement(exchange.requestBody.readAllBytes().decodeToString()).jsonObject
            requests.add(request); tokens.add(exchange.requestHeaders.getFirst("Authorization"))
            val result = when (request.getValue("operationName").jsonPrimitive.content) {
                "RememberMemory" -> "\"remember\":$memory"
                "GetMemory" -> "\"get\":$memory"
                "SearchMemories" -> "\"search\":[$memory]"
                "UpdateMemory" -> "\"update\":$memory"
                "ForgetMemory" -> "\"forget\":true"
                else -> error("Unexpected operation")
            }
            val body = """{"data":{"ai":{"memories":{$result}}}}""".encodeToByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        val network = NetworkClient("http://127.0.0.1:${server.address.port}/graphql").apply { tokenProvider = { "scoped-test-token" } }
        try {
            val api = MemoryApi(network, conversation)
            for (action in listOf("remember", "remember", "get", "search", "update", "forget")) {
                val result = MemoryToolRegistrar.handle(api, buildJsonObject {
                    put("action", action); put("requestId", requestId); put("statement", "Prefer short answers")
                    put("conversationId", Uuid.random().toString()); put("id", id); put("expectedRevision", "revision-one")
                })
                assertFalse(result.isError == true, result.toString())
                assertFalse(assertIs<TextContent>(result.content.single()).text.contains("scoped-test-token"))
            }
            assertEquals(6, requests.size)
            assertTrue(tokens.all { it == "Bearer scoped-test-token" })
            val firstInput = requests[0].getValue("variables").jsonObject.getValue("input").jsonObject
            val retry = requests[1].getValue("variables").jsonObject.getValue("input").jsonObject
            assertEquals(firstInput, retry)
            assertEquals(requestId, firstInput.getValue("requestId").jsonPrimitive.content)
            assertEquals(conversation.toString(), firstInput.getValue("conversationId").jsonPrimitive.content)
            for (index in listOf(2, 3, 4)) assertEquals(conversation.toString(), requests[index].getValue("variables").jsonObject.getValue("conversationId").jsonPrimitive.content)
            for (index in listOf(4, 5)) assertEquals("revision-one", requests[index].getValue("variables").jsonObject.getValue("expectedRevision").jsonPrimitive.content)
        } finally {
            server.stop(0); network.http.dispatcher.executorService.shutdown(); network.http.connectionPool.evictAll()
        }
    }

    @Test fun `tool requires explicit save and describes private defaults source freshness and retention`() {
        val description = requireNotNull(MemoryToolRegistrar.tool.description)
        assertContains(description, "explicitly asks")
        assertContains(description, "never extract memories automatically")
        assertContains(description, "sourceChanged")
        assertContains(description, "PERSONAL")
        assertContains(description, "independently of")
    }
}
