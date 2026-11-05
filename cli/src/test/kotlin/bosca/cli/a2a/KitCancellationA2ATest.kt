package bosca.cli.a2a

import bosca.cli.api.KitHarnessFixture
import bosca.graphql.client.GraphQLJson
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.test.*

class KitCancellationA2ATest {
    @Test
    fun `HTTP task cancellation retains the remote outcome including completion and interruption races`() = runBlocking {
        for ((outcome, state, delayed) in listOf(Triple("CANCELLED", "canceled", false), Triple("COMPLETED", "completed", false),
            Triple("INTERRUPTED", "failed", false), Triple("CANCELLED", "canceled", true))) {
            KitHarnessFixture(outcome, waitForCancellation = true).use { fixture ->
                fixture.confirmCancellation = !delayed
                val server = createKitA2AHttpServer(KitA2AAgentExecutor(fixture.api(), 15_000), kitAgentCard("http://localhost/a2a/kit", false),
                    "127.0.0.1", 0, "/a2a/kit", null)
                val http = OkHttpClient()
                server.start(wait = false)
                try {
                    val endpoint = "http://127.0.0.1:${server.engine.resolvedConnectors().single().port}/a2a/kit"
                    fun request(method: String, params: JsonObject): JsonObject {
                        val body = buildJsonObject { put("jsonrpc", "2.0"); put("id", "test"); put("method", method); put("params", params) }
                        return http.newCall(Request.Builder().url(endpoint).post(body.toString().toRequestBody("application/json".toMediaType())).build())
                            .execute().use { response ->
                                assertEquals(200, response.code)
                                GraphQLJson.parseToJsonElement(response.body.string()).jsonObject
                            }
                    }
                    val task = request("message/send", buildJsonObject {
                        putJsonObject("configuration") { put("blocking", false) }
                        putJsonObject("message") {
                            put("kind", "message"); put("messageId", "work"); put("role", "user")
                            putJsonArray("parts") { add(buildJsonObject { put("kind", "text"); put("text", "Keep working") }) }
                        }
                    }).getValue("result").jsonObject
                    withTimeout(5_000) { while ("SendKitChatMessage" !in fixture.operations) delay(5) }
                    val id = buildJsonObject { put("id", task.getValue("id")) }
                    val cancelled = request("tasks/cancel", id)
                    if (outcome == "CANCELLED" && !delayed) assertFalse("error" in cancelled, cancelled.toString())
                    else assertTrue("error" in cancelled, "Unconfirmed cancellation must not succeed: $cancelled")
                    var current = request("tasks/get", id).getValue("result").jsonObject
                    if (delayed) {
                        assertContains(cancelled.toString(), "has not confirmed")
                        assertEquals("working", current.getValue("status").jsonObject.getValue("state").jsonPrimitive.content)
                        fixture.confirmCancellation = true
                        withTimeout(5_000) {
                            while (current.getValue("status").jsonObject.getValue("state").jsonPrimitive.content == "working") {
                                delay(5)
                                current = request("tasks/get", id).getValue("result").jsonObject
                            }
                        }
                    }
                    assertEquals(state, current.getValue("status").jsonObject.getValue("state").jsonPrimitive.content)
                    if (outcome == "CANCELLED" && !delayed) {
                        assertEquals(outcome, cancelled.getValue("result").jsonObject.getValue("artifacts").jsonArray.single()
                            .jsonObject.getValue("parts").jsonArray.first().jsonObject.getValue("data").jsonObject
                            .getValue("status").jsonPrimitive.content)
                    }
                    request("tasks/cancel", id)
                    assertEquals(1, fixture.operations.count { it == "CancelKitChatSession" })
                    assertEquals(1, fixture.operations.count { it == "SendKitChatMessage" })
                } finally {
                    server.stop(0, 0)
                    http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll()
                }
            }
        }
    }
}
