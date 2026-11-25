package bosca.cli.a2a

import bosca.cli.api.KitHarnessFixture
import bosca.graphql.client.GraphQLJson
import java.net.ServerSocket
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.test.*

class KitHarnessA2ATest {
    @Test
    fun `HTTP A2A carries typed harness data files and tool correlation with truthful terminal states`() = runBlocking {
        for ((outcome, expectedState) in listOf("COMPLETED" to "completed", "CANCELLED" to "canceled", "INTERRUPTED" to "failed")) {
            KitHarnessFixture(outcome).use { fixture ->
                val port = ServerSocket(0).use { it.localPort }
                val endpoint = "http://127.0.0.1:$port/a2a/kit"
                val server = createKitA2AHttpServer(KitA2AAgentExecutor(fixture.api(), 5_000), kitAgentCard(endpoint, false),
                    "127.0.0.1", port, "/a2a/kit", null)
                val http = OkHttpClient()
                server.start(wait = false)
                try {
                    fun request(method: String, params: JsonObject): JsonObject {
                        val body = buildJsonObject { put("jsonrpc", "2.0"); put("id", "test"); put("method", method); put("params", params) }
                        return http.newCall(Request.Builder().url(endpoint).post(body.toString().toRequestBody("application/json".toMediaType())).build())
                            .execute().use { response ->
                                assertEquals(200, response.code)
                                val value = GraphQLJson.parseToJsonElement(response.body.string()).jsonObject
                                assertFalse("error" in value, value.toString())
                                value.getValue("result").jsonObject
                            }
                    }
                    var task = request("message/send", buildJsonObject {
                        putJsonObject("configuration") { put("blocking", true) }
                        putJsonObject("message") {
                            put("kind", "message"); put("messageId", "report"); put("role", "user")
                            putJsonArray("parts") { add(buildJsonObject { put("kind", "text"); put("text", "Report") }) }
                        }
                    })
                    withTimeout(5_000) {
                        while (task.getValue("status").jsonObject.getValue("state").jsonPrimitive.content !in setOf("completed", "failed", "canceled")) {
                            delay(10)
                            task = request("tasks/get", buildJsonObject { put("id", task.getValue("id")) })
                        }
                    }
                    assertEquals(expectedState, task.getValue("status").jsonObject.getValue("state").jsonPrimitive.content)
                    val artifact = task.getValue("artifacts").jsonArray.single().jsonObject
                    assertEquals(KitHarnessFixture.TURN_ID, artifact.getValue("artifactId").jsonPrimitive.content)
                    val parts = artifact.getValue("parts").jsonArray.map { it.jsonObject }
                    assertEquals(3, parts.count { it["kind"]?.jsonPrimitive?.content == "file" })
                    assertEquals(outcome, parts.first().getValue("data").jsonObject.getValue("status").jsonPrimitive.content)
                    assertTrue(parts.any { it["data"]?.jsonObject?.get("type")?.jsonPrimitive?.content == "diff" })
                    assertTrue(parts.any { it["data"]?.jsonObject?.get("type")?.jsonPrimitive?.content == "resource_link" })
                    val image = parts.single { it["file"]?.jsonObject?.get("mimeType")?.jsonPrimitive?.content == "image/png" }
                    assertEquals("${KitHarnessFixture.TURN_ID}/result", image.getValue("metadata").jsonObject.getValue("boscaToolCallId").jsonPrimitive.content)
                    assertFalse(task.toString().contains("private-"))
                    assertTrue(fixture.operations.none { it.contains("Metadata") })
                } finally {
                    server.stop(0, 0)
                    http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll()
                }
            }
        }
    }
}
