package bosca.cli.mcp

import bosca.cli.api.AnalyticsApi
import bosca.cli.api.NetworkClient
import bosca.graphql.client.GraphQLJson
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnalyticsToolRegistrarTest {

    @Test
    fun `analytics MCP tools have compact agent-oriented names and action schemas`() {
        assertEquals("analytics_query", AnalyticsToolRegistrar.queryTool.name)
        assertEquals("analytics_visualization", AnalyticsToolRegistrar.visualizationTool.name)
        assertEquals("analytics_dashboard", AnalyticsToolRegistrar.dashboardTool.name)

        val queryActions = requireNotNull(AnalyticsToolRegistrar.queryTool.inputSchema.properties)
            .getValue("action").jsonObject.getValue("enum").jsonArray
            .map { it.jsonPrimitive.content }
        assertTrue("execute" in queryActions)
        assertTrue("refresh" in queryActions)
        assertTrue("grant_permission" in queryActions)

        val dashboardActions = requireNotNull(AnalyticsToolRegistrar.dashboardTool.inputSchema.properties)
            .getValue("action").jsonObject.getValue("enum").jsonArray
            .map { it.jsonPrimitive.content }
        assertTrue("add_visualization" in dashboardActions)
        assertTrue("remove_visualization" in dashboardActions)

        val visualizationTypes = requireNotNull(AnalyticsToolRegistrar.visualizationTool.inputSchema.properties)
            .getValue("type").jsonObject.getValue("enum").jsonArray
            .map { it.jsonPrimitive.content }
        assertTrue("DATEPICKER" in visualizationTypes)
        assertTrue("GEO_POINT_MAP" in visualizationTypes)
        assertTrue("LIVE_SESSIONS_MAP" in visualizationTypes)
    }

    @Test
    fun `query execute sends named JSON parameters and returns bounded structured rows`() = runBlocking {
        val fixture = Fixture(
            """
            {
              "data": {
                "analytics": {
                  "queries": {
                    "executeByKey": {
                      "records": [
                        {"day":"Mon","sessions":12},
                        {"day":"Tue","sessions":18},
                        {"day":"Wed","sessions":21}
                      ],
                      "cached": true,
                      "refreshedAt": null
                    }
                  }
                }
              }
            }
            """.trimIndent(),
        )
        try {
            val result = AnalyticsToolRegistrar.handleQuery(
                AnalyticsApi(NetworkClient(fixture.url)),
                buildJsonObject {
                    put("action", "execute")
                    put("key", "weekly-traffic")
                    put("limit", 2)
                    put("parameters", buildJsonObject {
                        put("country", "US")
                        put("minimum", 10)
                    })
                },
            )

            val output = GraphQLJson.parseToJsonElement(result).jsonObject
            assertEquals(2, output.getValue("returnedRecords").jsonPrimitive.content.toInt())
            assertTrue(output.getValue("truncated").jsonPrimitive.content.toBoolean())
            assertTrue(output.getValue("cached").jsonPrimitive.content.toBoolean())

            val request = GraphQLJson.parseToJsonElement(fixture.requestBody).jsonObject
            assertEquals("ExecuteAnalyticsQueryByKey", request.getValue("operationName").jsonPrimitive.content)
            val variables = request.getValue("variables").jsonObject
            assertEquals("weekly-traffic", variables.getValue("key").jsonPrimitive.content)
            val sentParameters = variables.getValue("parameters").jsonArray
            assertEquals(2, sentParameters.size)
            assertEquals("country", sentParameters[0].jsonObject.getValue("parameter").jsonPrimitive.content)
            assertEquals(JsonPrimitive("US"), sentParameters[0].jsonObject.getValue("value"))
        } finally {
            fixture.server.stop(0)
        }
    }

    @Test
    fun `dashboard get serializes full dashboard and visualization fragments`() = runBlocking {
        val fixture = Fixture(
            """
            {
              "data": {
                "analytics": {
                  "dashboards": {
                    "byKey": {
                      "id": "d7fde6e8-a924-46f1-bbaf-f081f0dcde75",
                      "key": "default",
                      "name": "Default Dashboard",
                      "description": "Default analytics dashboard",
                      "configuration": null,
                      "parameters": [],
                      "permissions": [],
                      "visualizations": [
                        {
                          "id": "11111111-1111-1111-1111-111111111111",
                          "configuration": {"x": 0, "y": 0, "w": 6, "h": 3},
                          "visualization": {
                            "id": "22222222-2222-2222-2222-222222222222",
                            "key": "daily-sessions",
                            "name": "Daily Sessions",
                            "description": "Sessions per day",
                            "queryId": "33333333-3333-3333-3333-333333333333",
                            "type": "BAR",
                            "configuration": {"x": "date", "y": ["value"]},
                            "permissions": []
                          }
                        }
                      ]
                    }
                  }
                }
              }
            }
            """.trimIndent(),
        )
        try {
            val result = AnalyticsToolRegistrar.handleDashboard(
                AnalyticsApi(NetworkClient(fixture.url)),
                buildJsonObject {
                    put("action", "get")
                    put("key", "default")
                },
            )

            val output = GraphQLJson.parseToJsonElement(result).jsonObject
            assertEquals("default", output.getValue("key").jsonPrimitive.content)
            val visualization = output.getValue("visualizations").jsonArray.single().jsonObject
                .getValue("visualization").jsonObject
            assertEquals("daily-sessions", visualization.getValue("key").jsonPrimitive.content)
            assertEquals("BAR", visualization.getValue("type").jsonPrimitive.content)
        } finally {
            fixture.server.stop(0)
        }
    }

    private class Fixture(response: String) {
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        lateinit var requestBody: String

        init {
            server.createContext("/graphql") { exchange ->
                requestBody = exchange.requestBody.readBytes().decodeToString()
                val bytes = response.encodeToByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.start()
        }

        val url: String get() = "http://127.0.0.1:${server.address.port}/graphql"
    }
}
