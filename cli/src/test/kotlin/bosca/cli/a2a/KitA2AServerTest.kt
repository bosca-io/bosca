package bosca.cli.a2a

import bosca.cli.api.KitApi
import bosca.cli.api.NetworkClient
import bosca.graphql.client.GraphQLJson
import com.github.ajalt.clikt.testing.test
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class KitA2AServerTest {

    @Test
    fun `agent card advertises Kit capabilities and bearer security`() {
        val card = kitAgentCard("https://agents.example.com/a2a/kit", requireBearerToken = true)

        assertEquals("Kit", card.name)
        assertEquals("0.3.0", card.protocolVersion)
        assertTrue(card.capabilities.streaming == true)
        assertEquals(listOf("conversation", "analytics", "image-generation"), card.skills.map { it.id })
        assertTrue("bearer" in requireNotNull(card.securitySchemes))
        assertEquals(listOf(mapOf("bearer" to emptyList())), card.security)

        val localCard = kitAgentCard("http://127.0.0.1:8091/a2a/kit", requireBearerToken = false)
        assertTrue(localCard.securitySchemes.isNullOrEmpty())
        assertTrue(localCard.security.isNullOrEmpty())
    }

    @Test
    fun `server normalizes paths and compares bearer credentials`() {
        assertEquals("/a2a/kit", normalizePath(" a2a/kit/ "))
        assertFailsWith<IllegalArgumentException> { normalizePath(" / ") }
        assertEquals("[::1]", urlHost("::1"))
        assertEquals("[::1]", urlHost("[::1]"))
        assertEquals("localhost", urlHost("localhost"))
        assertTrue(isLoopbackHost("LOCALHOST"))
        assertFalse(isLoopbackHost("agents.example.com"))
        assertTrue(authorized("Bearer local-secret", "local-secret"))
        assertTrue(authorized("bearer local-secret", "local-secret"))
        assertFalse(authorized("Bearer wrong", "local-secret"))
        assertFalse(authorized(null, "local-secret"))
    }

    @Test
    fun `A2A server command advertises options and rejects unsafe configuration`() {
        val help = KitA2AServerCommand().test("--help")
        assertEquals(0, help.statusCode)
        for (option in listOf("--host", "--port", "--path", "--public-url", "--access-token")) {
            assertTrue(option in help.stdout, "Expected $option in help: ${help.stdout}")
        }

        val badPort = KitA2AServerCommand().test("--port 0")
        assertTrue(badPort.statusCode != 0)
        assertTrue("--port must be between" in badPort.stderr)

        val badTimeout = KitA2AServerCommand().test("--timeout-seconds 0")
        assertTrue(badTimeout.statusCode != 0)
        assertTrue("--timeout-seconds must be between" in badTimeout.stderr)

        val unprotectedRemote = KitA2AServerCommand().test(
            listOf("--host", "agents.example.com", "--access-token", " "),
        )
        assertTrue(unprotectedRemote.statusCode != 0)
        assertTrue("--access-token is required" in unprotectedRemote.stderr)

        val wildcardWithoutPublicUrl = KitA2AServerCommand().test("--host 0.0.0.0 --access-token secret")
        assertTrue(wildcardWithoutPublicUrl.statusCode != 0)
        assertTrue("--public-url is required" in wildcardWithoutPublicUrl.stderr)
    }

    @Test
    fun `loopback A2A endpoint works without an inbound access token`() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        val endpoint = "http://127.0.0.1:$port/a2a/kit"
        val server = createKitA2AHttpServer(
            executor = KitA2AAgentExecutor(
                KitApi(NetworkClient("http://127.0.0.1:1/graphql"), pollIntervalMillis = 1),
                timeoutMillis = 100,
            ),
            agentCard = kitAgentCard(endpoint, requireBearerToken = false),
            host = "127.0.0.1",
            port = port,
            path = "/a2a/kit",
            accessToken = null,
        )
        val http = OkHttpClient()
        server.start(wait = false)
        try {
            val response = http.newCall(a2aRequest(endpoint, " ")).execute().use { httpResponse ->
                assertEquals(200, httpResponse.code)
                GraphQLJson.parseToJsonElement(httpResponse.body.string()).jsonObject
            }
            assertEquals(
                "rejected",
                response.getValue("result").jsonObject.getValue("status").jsonObject
                    .getValue("state").jsonPrimitive.content,
            )

            http.newCall(a2aRequest(endpoint, " ", method = "message/stream")).execute().use { streamResponse ->
                assertEquals(200, streamResponse.code)
                assertTrue(streamResponse.header("Content-Type").orEmpty().startsWith("text/event-stream"))
                assertTrue("rejected" in streamResponse.body.string())
            }
        } finally {
            server.stop(0, 0)
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
    }

    @Test
    fun `A2A message send talks to Kit preserves context and returns data and image artifacts`() = runBlocking {
        val imageBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)
        val fixture = Fixture(imageBytes)
        val port = ServerSocket(0).use { it.localPort }
        val endpoint = "http://127.0.0.1:$port/a2a/kit"
        val card = kitAgentCard(endpoint, requireBearerToken = true)
        val server = createKitA2AHttpServer(
            executor = KitA2AAgentExecutor(
                KitApi(NetworkClient(fixture.graphQlUrl), pollIntervalMillis = 1),
                timeoutMillis = 5_000,
            ),
            agentCard = card,
            host = "127.0.0.1",
            port = port,
            path = "/a2a/kit",
            accessToken = "a2a-secret",
        )
        val http = OkHttpClient()
        server.start(wait = false)
        try {
            val agentCardResponse = http.newCall(Request.Builder().url("http://127.0.0.1:$port$AGENT_CARD_PATH").build())
                .execute()
            agentCardResponse.use { response ->
                assertEquals(200, response.code)
                assertEquals("Kit", GraphQLJson.parseToJsonElement(response.body.string())
                    .jsonObject.getValue("name").jsonPrimitive.content)
            }

            val unauthorized = http.newCall(a2aRequest(endpoint, "First question")).execute()
            unauthorized.use { response -> assertEquals(401, response.code) }

            val blank = send(http, endpoint, " ").getValue("result").jsonObject
            assertEquals("rejected", blank.getValue("status").jsonObject.getValue("state").jsonPrimitive.content)
            assertTrue(fixture.operations.isEmpty())

            val malformedSession = send(http, endpoint, "Use a bad session", kitSessionId = "not-a-uuid")
                .getValue("result").jsonObject
            assertEquals(
                "failed",
                malformedSession.getValue("status").jsonObject.getValue("state").jsonPrimitive.content,
            )
            assertTrue(malformedSession.getValue("status").jsonObject.getValue("message").jsonObject
                .getValue("parts").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content
                .contains("Kit request failed"))
            assertTrue(fixture.operations.isEmpty())

            val first = send(http, endpoint, "First question")
            val firstTask = first["result"]?.jsonObject ?: error("A2A response did not contain a result: $first")
            val firstResult = awaitCompletedTask(http, endpoint, firstTask)
            assertEquals("completed", firstResult.getValue("status").jsonObject.getValue("state").jsonPrimitive.content)
            val contextId = firstResult.getValue("contextId").jsonPrimitive.content
            val finalMessage = firstResult.getValue("status").jsonObject.getValue("message").jsonObject
            assertTrue(finalMessage.getValue("parts").jsonArray.single().jsonObject
                .getValue("text").jsonPrimitive.content.contains("42 visits"))
            assertEquals(
                SESSION_ID,
                finalMessage.getValue("metadata").jsonObject
                    .getValue(KitA2AAgentExecutor.KIT_SESSION_METADATA).jsonPrimitive.content,
            )

            val artifacts = firstResult["artifacts"]?.jsonArray?.map { it.jsonObject }
                ?: error("Completed A2A task did not contain artifacts: $firstResult")
            val analytics = artifacts.single { it.getValue("name").jsonPrimitive.content == "Kit analytics result" }
            assertEquals(
                "tool-display",
                analytics.getValue("parts").jsonArray.single().jsonObject
                    .getValue("data").jsonObject.getValue("events").jsonArray.single().jsonObject
                    .getValue("type").jsonPrimitive.content,
            )
            val image = artifacts.single { it.getValue("artifactId").jsonPrimitive.content == IMAGE_ID }
            val encodedImage = image.getValue("parts").jsonArray.single().jsonObject
                .getValue("file").jsonObject.getValue("bytes").jsonPrimitive.content
            assertContentEquals(imageBytes, Base64.getDecoder().decode(encodedImage))

            val second = send(http, endpoint, "Follow up", contextId, SESSION_ID)
            val secondResult = awaitCompletedTask(http, endpoint, second.getValue("result").jsonObject)
            assertEquals(contextId, secondResult.getValue("contextId").jsonPrimitive.content)
            assertTrue(secondResult.getValue("status").jsonObject.getValue("message").jsonObject
                .getValue("parts").jsonArray.single().jsonObject
                .getValue("text").jsonPrimitive.content.contains("Follow-up answer"))
            assertEquals(1, fixture.operations.count { it == "CreateKitChatSession" })
            assertEquals(2, fixture.operations.count { it == "SendKitChatMessage" })
        } finally {
            server.stop(0, 0)
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
            fixture.close()
        }
        Unit
    }

    private fun send(
        client: OkHttpClient,
        endpoint: String,
        message: String,
        contextId: String? = null,
        kitSessionId: String? = null,
    ): JsonObject {
        client.newCall(a2aRequest(endpoint, message, contextId, kitSessionId, authorized = true)).execute()
            .use { response ->
            val body = response.body.string()
            assertEquals(200, response.code, body)
            return GraphQLJson.parseToJsonElement(body).jsonObject
        }
    }

    private suspend fun awaitCompletedTask(client: OkHttpClient, endpoint: String, initial: JsonObject): JsonObject {
        if (initial.getValue("status").jsonObject.getValue("state").jsonPrimitive.content in TERMINAL_STATES) {
            return initial
        }
        val taskId = initial.getValue("id").jsonPrimitive.content
        repeat(100) {
            val response = client.newCall(taskRequest(endpoint, taskId)).execute().use { httpResponse ->
                val body = httpResponse.body.string()
                assertEquals(200, httpResponse.code, body)
                GraphQLJson.parseToJsonElement(body).jsonObject
            }
            val task = response["result"]?.jsonObject ?: error("A2A task response did not contain a result: $response")
            if (task.getValue("status").jsonObject.getValue("state").jsonPrimitive.content in TERMINAL_STATES) {
                return task
            }
            delay(10)
        }
        error("A2A task $taskId did not complete")
    }

    private fun taskRequest(endpoint: String, taskId: String): Request {
        val payload = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", "task-$taskId")
            put("method", "tasks/get")
            put("params", buildJsonObject { put("id", taskId) })
        }
        return Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer a2a-secret")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
    }

    private fun a2aRequest(
        endpoint: String,
        message: String,
        contextId: String? = null,
        kitSessionId: String? = null,
        authorized: Boolean = false,
        method: String = "message/send",
    ): Request {
        val payload = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", "request-${message.hashCode()}")
            put("method", method)
            put("params", buildJsonObject {
                put("configuration", buildJsonObject { put("blocking", true) })
                kitSessionId?.let { sessionId ->
                    put("metadata", buildJsonObject {
                        put(KitA2AAgentExecutor.KIT_SESSION_METADATA, sessionId)
                    })
                }
                put("message", buildJsonObject {
                    put("kind", "message")
                    put("messageId", "message-${message.hashCode()}")
                    put("role", "user")
                    put("parts", JsonArray(listOf(buildJsonObject {
                        put("kind", "text")
                        put("text", message)
                    })))
                    contextId?.let { put("contextId", it) }
                })
            })
        }
        return Request.Builder()
            .url(endpoint)
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .apply { if (authorized) header("Authorization", "Bearer a2a-secret") }
            .build()
    }

    private class Fixture(private val imageBytes: ByteArray) {
        private var getCount = 0
        val operations = mutableListOf<String>()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

        init {
            server.createContext("/") { exchange ->
                if (exchange.requestURI.path == "/asset") {
                    exchange.sendResponseHeaders(200, imageBytes.size.toLong())
                    exchange.responseBody.use { it.write(imageBytes) }
                } else {
                    val request = GraphQLJson.parseToJsonElement(exchange.requestBody.readBytes().decodeToString()).jsonObject
                    val operation = request.getValue("operationName").jsonPrimitive.content
                    operations += operation
                    val response = when (operation) {
                        "CreateKitChatSession" -> graphQl("""{"chatSessions":{"create":${session("[]")}}}""")
                        "SendKitChatMessage" -> graphQl("""{"chatSessions":{"send":true}}""")
                        "GetKitChatSession" -> {
                            getCount++
                            val messages = when (getCount) {
                                1 -> "[]"
                                2, 3 -> firstMessages()
                                else -> secondMessages()
                            }
                            graphQl("""{"chatSessions":{"session":${session(messages)}}}""")
                        }
                        "GetMetadataDownload" -> graphQl(
                            """{"content":{"metadata":{"content":{"type":"image/png","length":4,"urls":{"download":{"url":"$assetUrl","headers":[]}}}}}}""",
                        )
                        else -> error("Unexpected operation: $operation")
                    }.encodeToByteArray()
                    exchange.sendResponseHeaders(200, response.size.toLong())
                    exchange.responseBody.use { it.write(response) }
                }
            }
            server.start()
        }

        val graphQlUrl: String get() = "http://127.0.0.1:${server.address.port}/graphql"
        private val assetUrl: String get() = "http://127.0.0.1:${server.address.port}/asset"

        fun close() = server.stop(0)
    }

    private companion object {
        const val SESSION_ID = "11111111-1111-1111-1111-111111111111"
        const val FIRST_MESSAGE_ID = "22222222-2222-2222-2222-222222222222"
        const val DISPLAY_MESSAGE_ID = "33333333-3333-3333-3333-333333333333"
        const val IMAGE_ID = "44444444-4444-4444-4444-444444444444"
        const val SECOND_MESSAGE_ID = "55555555-5555-5555-5555-555555555555"
        const val CREATED = "2026-08-09T12:00:00Z"
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
        val TERMINAL_STATES = setOf("completed", "failed", "canceled", "rejected")

        fun graphQl(data: String): String = """{"data":$data}"""

        fun session(messages: String): String = """
            {
              "id":"$SESSION_ID",
              "agentKey":"kit",
              "title":"A2A conversation",
              "processing":false,
              "status":"COMPLETED",
              "created":"$CREATED",
              "modified":"$CREATED",
              "messages":$messages
            }
        """.trimIndent()

        fun firstMessages(): String = JsonArray(
            listOf(
                message(
                    FIRST_MESSAGE_ID,
                    "Assistant",
                    """{"type":"ai.koog.prompt.message.Message.Assistant","parts":[{"text":"There were 42 visits. ![Chart](/api/v1/content/metadata/download?id=$IMAGE_ID)"}]}""",
                ),
                message(
                    DISPLAY_MESSAGE_ID,
                    "kit",
                    """{"type":"tool-display","title":"Visits","data":[{"visits":42}]}""",
                ),
            ),
        ).toString()

        fun secondMessages(): String = JsonArray(
            listOf(
                message(
                    FIRST_MESSAGE_ID,
                    "Assistant",
                    """{"type":"ai.koog.prompt.message.Message.Assistant","parts":[{"text":"There were 42 visits."}]}""",
                ),
                message(
                    SECOND_MESSAGE_ID,
                    "Assistant",
                    """{"type":"ai.koog.prompt.message.Message.Assistant","parts":[{"text":"Follow-up answer"}]}""",
                ),
            ),
        ).toString()

        fun message(id: String, author: String, event: String): JsonObject = buildJsonObject {
            put("id", id)
            put("sessionId", SESSION_ID)
            put("author", author)
            put("created", CREATED)
            put("event", GraphQLJson.parseToJsonElement(event))
        }
    }
}
