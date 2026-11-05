package bosca.cli.mcp

import bosca.cli.api.AnalyticsApi
import bosca.cli.api.NetworkClient
import com.sun.net.httpserver.HttpServer
import io.modelcontextprotocol.kotlin.sdk.types.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.net.InetSocketAddress
import kotlin.test.*

class AnalyticsWarehouseToolRegistrarTest {
    private lateinit var server: HttpServer
    private lateinit var network: NetworkClient
    private lateinit var api: AnalyticsApi
    private val requests = mutableListOf<JsonObject>()
    private val tokens = mutableListOf<String?>()
    private var failure: String? = null

    @BeforeTest fun setup() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/graphql") { exchange ->
            val request = Json.parseToJsonElement(exchange.requestBody.bufferedReader().readText()).jsonObject
            requests += request
            tokens += exchange.requestHeaders.getFirst("Authorization")
            val variables = request.getValue("variables").jsonObject
            val data = when (request.getValue("operationName").jsonPrimitive.content) {
                "ListAnalyticsCatalogs" -> """{"catalogs":["warehouse"]}"""
                "ListAnalyticsSchemas" -> """{"schemas":["bosca"]}"""
                "ListAnalyticsTables" -> """{"tables":["events"]}"""
                "DescribeAnalyticsTable" -> """{"describe":[{"name":"sessions","typeName":"bigint","nullable":false}]}"""
                "ExecuteAnalyticsSql" -> """{"execute":{"sql":${variables.getValue("sql")},"columns":[{"name":"sessions","typeName":"bigint","nullable":false}],"records":[{"sessions":42}],"truncated":true,"executedAt":"2026-10-24T00:00:00Z","durationMs":7}}"""
                else -> error("Unexpected operation")
            }
            val response = (if (failure != null) """{"errors":[{"message":"$failure"}]}""" else """{"data":{"analytics":{"warehouse":$data}}}""").encodeToByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        network = NetworkClient("http://127.0.0.1:${server.address.port}/graphql").apply { tokenProvider = { "bsk_analytics_fixture" } }
        api = AnalyticsApi(network)
    }
    @AfterTest fun cleanup() { server.stop(0); network.http.dispatcher.executorService.shutdown(); network.http.connectionPool.evictAll() }
    private suspend fun call(action: String, extra: String = "{}") = AnalyticsWarehouseToolRegistrar.handle(api, buildJsonObject {
        put("action", action); Json.parseToJsonElement(extra).jsonObject.forEach { (key, value) -> put(key, value) }
    })

    @Test fun `discovery uses typed operations with scoped authentication and exact identifiers`() = runBlocking {
        assertFalse(call("catalogs", """{"offset":20,"limit":10}""").isError == true)
        assertEquals(JsonPrimitive(20), requests.last().getValue("variables").jsonObject["offset"])
        assertFalse(call("schemas", """{"catalog":"warehouse"}""").isError == true)
        assertFalse(call("tables", """{"catalog":"warehouse","schema":"bosca"}""").isError == true)
        assertFalse(call("describe", """{"catalog":"warehouse","schema":"bosca","table":"events"}""").isError == true)
        assertEquals(listOf("ListAnalyticsCatalogs", "ListAnalyticsSchemas", "ListAnalyticsTables", "DescribeAnalyticsTable"), requests.map { it.getValue("operationName").jsonPrimitive.content })
        assertTrue(tokens.all { it == "Bearer bsk_analytics_fixture" })
    }

    @Test fun `execution evidence comes from the server and keeps structured rows with a chart`() = runBlocking {
        val result = call("execute", """{"sql":"SELECT count(*) AS sessions FROM warehouse.bosca.events","limit":1,"visualizationType":"NUMBER","configuration":{"value":"sessions"}}""")
        assertFalse(result.isError == true, result.toString())
        val text = assertIs<TextContent>(result.content.first()).text
        val value = Json.parseToJsonElement(text).jsonObject
        assertEquals(JsonPrimitive(42), value.getValue("records").jsonArray.single().jsonObject["sessions"])
        assertEquals(JsonPrimitive(true), value["truncated"])
        assertEquals(JsonPrimitive(7), value["durationMs"])
        assertEquals(JsonPrimitive("NUMBER"), value["visualizationType"])
        assertEquals(requests.single().getValue("variables").jsonObject["sql"], value["sql"])
        val resource = assertIs<TextResourceContents>(assertIs<EmbeddedResource>(result.content[1]).resource)
        assertEquals(text, resource.text)
        assertTrue(resource.uri.matches(Regex("bosca://analytics/sql/[0-9a-f-]+/results")))
        assertEquals("application/json", resource.mimeType)
        val defaults = call("execute", """{"sql":"SELECT 42 AS sessions"}""")
        assertContains(assertIs<TextContent>(defaults.content.first()).text, "\"visualizationType\":\"TABLE\"")
        assertEquals(JsonPrimitive(100), requests.last().getValue("variables").jsonObject["limit"])
    }

    @Test fun `invalid inputs never call the API`() = runBlocking {
        for ((action, extra) in listOf("schemas" to "{}", "catalogs" to """{"offset":-1}""", "catalogs" to """{"limit":1001}""", "catalogs" to """{"limit":0}""", "execute" to "{}", "execute" to """{"sql":"SELECT 1","offset":1}""", "execute" to """{"sql":"SELECT 1","visualizationType":"REMOTE"}""", "execute" to """{"sql":"SELECT 1","configuration":{"url":"https://remote"}}""", "unknown" to "{}")) {
            assertTrue(call(action, extra).isError == true)
        }
        assertTrue(requests.isEmpty())
    }

    @Test fun `API failures produce no result resource or retry and cancellation propagates`(): Unit = runBlocking {
        failure = "scope denied"
        val result = call("execute", """{"sql":"SELECT 1"}""")
        assertTrue(result.isError == true)
        assertEquals(1, result.content.size)
        assertEquals(1, requests.size)
        network.tokenProvider = { throw CancellationException("cancelled") }
        assertFailsWith<CancellationException> { call("catalogs") }
    }
}
