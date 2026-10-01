package bosca.cli.mcp

import bosca.cli.api.KitApi
import bosca.cli.api.NetworkClient
import bosca.graphql.client.GraphQLJson
import com.sun.net.httpserver.HttpServer
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.ResourceLink
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.Base64
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class KitToolRegistrarTest {

    @Test
    fun `Kit MCP tools advertise chat continuity and asset downloads`() {
        assertEquals("kit_chat", KitToolRegistrar.chatTool.name)
        assertEquals("kit_download", KitToolRegistrar.downloadTool.name)

        val properties = requireNotNull(KitToolRegistrar.chatTool.inputSchema.properties)
        val actions = properties.getValue("action").jsonObject.getValue("enum").jsonArray
            .map { it.jsonPrimitive.content }
        assertEquals(listOf("ask", "list", "get", "delete"), actions)
        assertTrue("sessionId" in properties)
        assertTrue("downloadDirectory" in properties)
        assertTrue("includeImages" in properties)
    }

    @Test
    fun `ask waits for Kit and returns analytics display data with reusable session id`() = runBlocking {
        val fixture = Fixture { operation ->
            when (operation) {
                "CreateKitChatSession" -> graphQl("""{"chatSessions":{"create":${session(messages = "[]")}}}""")
                "SendKitChatMessage" -> graphQl("""{"chatSessions":{"send":true}}""")
                "GetKitChatSession" -> graphQl(
                    """{"chatSessions":{"session":${session(messages = analyticsMessages())}}}""",
                )
                else -> error("Unexpected operation: $operation")
            }
        }
        try {
            val result = KitToolRegistrar.handleChat(
                KitApi(NetworkClient(fixture.graphQlUrl), pollIntervalMillis = 1),
                buildJsonObject {
                    put("action", "ask")
                    put("message", "How many visits did we have?")
                },
            )

            assertFalse(result.isError == true)
            assertTrue(assertIs<TextContent>(result.content.first()).text.contains("42 visits"))
            val structured = requireNotNull(result.structuredContent)
            assertEquals(SESSION_ID, structured.getValue("sessionId").jsonPrimitive.content)
            assertEquals(1, structured.getValue("displayEvents").jsonArray.size)
            assertEquals("tool-display", structured.getValue("displayEvents").jsonArray.single()
                .jsonObject.getValue("type").jsonPrimitive.content)
            assertEquals(
                listOf("CreateKitChatSession", "SendKitChatMessage", "GetKitChatSession"),
                fixture.operations,
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `ask returns image response while Kit is still finalizing session state`() = runBlocking {
        val fixture = Fixture { operation ->
            when (operation) {
                "CreateKitChatSession" -> graphQl("""{"chatSessions":{"create":${session(messages = "[]")}}}""")
                "SendKitChatMessage" -> graphQl("""{"chatSessions":{"send":true}}""")
                "GetKitChatSession" -> graphQl(
                    """{"chatSessions":{"session":${session(
                        messages = imageMessages(),
                        processing = true,
                        status = "STREAMING",
                    )}}}""",
                )
                else -> error("Unexpected operation: $operation")
            }
        }
        try {
            val result = KitToolRegistrar.handleChat(
                KitApi(NetworkClient(fixture.graphQlUrl), pollIntervalMillis = 1),
                buildJsonObject {
                    put("action", "ask")
                    put("message", "Generate a landscape image")
                    put("includeImages", false)
                    put("timeoutSeconds", 1)
                },
            )

            assertFalse(result.isError == true)
            assertTrue(assertIs<TextContent>(result.content.first()).text.contains("Created it"))
            assertEquals(
                listOf("CreateKitChatSession", "SendKitChatMessage", "GetKitChatSession"),
                fixture.operations,
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `ask continues an existing Kit session and returns only the new turn`() = runBlocking {
        lateinit var fixture: Fixture
        fixture = Fixture { operation ->
            when (operation) {
                "GetKitChatSession" -> {
                    val messages = if (fixture.operations.count { it == "GetKitChatSession" } == 1) {
                        textMessages(OLD_MESSAGE_ID to "Earlier answer")
                    } else {
                        textMessages(OLD_MESSAGE_ID to "Earlier answer", ASSISTANT_MESSAGE_ID to "Follow-up answer")
                    }
                    graphQl("""{"chatSessions":{"session":${session(messages)}}}""")
                }
                "SendKitChatMessage" -> graphQl("""{"chatSessions":{"send":true}}""")
                else -> error("Unexpected operation: $operation")
            }
        }
        try {
            val result = KitToolRegistrar.handleChat(
                KitApi(NetworkClient(fixture.graphQlUrl), pollIntervalMillis = 1),
                buildJsonObject {
                    put("action", "ask")
                    put("sessionId", SESSION_ID)
                    put("message", "Tell me more")
                },
            )

            val text = assertIs<TextContent>(result.content.first()).text
            assertTrue(text.contains("Follow-up answer"))
            assertFalse(text.contains("Earlier answer"))
            assertEquals(
                listOf("GetKitChatSession", "SendKitChatMessage", "GetKitChatSession"),
                fixture.operations,
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `Kit chat lists only Kit sessions and supports get and delete`() = runBlocking {
        val fixture = Fixture { operation ->
            when (operation) {
                "ListKitChatSessions" -> graphQl(
                    """{"chatSessions":{"all":[${summary()},${summary(id = OTHER_SESSION_ID, agentKey = "other")} ]}}""",
                )
                "GetKitChatSession" -> graphQl("""{"chatSessions":{"session":${session("[]")}}}""")
                "DeleteKitChatSession" -> graphQl("""{"chatSessions":{"delete":true}}""")
                else -> error("Unexpected operation: $operation")
            }
        }
        try {
            val api = KitApi(NetworkClient(fixture.graphQlUrl), pollIntervalMillis = 1)
            val listed = KitToolRegistrar.handleChat(api, buildJsonObject { put("action", "list") })
            val sessions = requireNotNull(listed.structuredContent).getValue("sessions").jsonArray
            assertEquals(1, sessions.size)
            assertEquals(SESSION_ID, sessions.single().jsonObject.getValue("id").jsonPrimitive.content)

            val getArgs = buildJsonObject {
                put("action", "get")
                put("sessionId", SESSION_ID)
            }
            val fetched = KitToolRegistrar.handleChat(api, getArgs)
            assertEquals(SESSION_ID, requireNotNull(fetched.structuredContent)
                .getValue("session").jsonObject.getValue("id").jsonPrimitive.content)

            val deleteArgs = buildJsonObject {
                put("action", "delete")
                put("sessionId", SESSION_ID)
            }
            val deleted = KitToolRegistrar.handleChat(api, deleteArgs)
            assertTrue(requireNotNull(deleted.structuredContent).getValue("deleted").jsonPrimitive.content.toBoolean())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `generated image is returned as MCP image content and saved to requested directory`() = runBlocking {
        val imageBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)
        lateinit var fixture: Fixture
        fixture = Fixture(imageBytes) { operation ->
            when (operation) {
                "CreateKitChatSession" -> graphQl("""{"chatSessions":{"create":${session(messages = "[]")}}}""")
                "SendKitChatMessage" -> graphQl("""{"chatSessions":{"send":true}}""")
                "GetKitChatSession" -> graphQl(
                    """{"chatSessions":{"session":${session(messages = imageMessages())}}}""",
                )
                "GetMetadataDownload" -> graphQl(
                    """{"content":{"metadata":{"content":{"type":"image/png","length":4,"urls":{"download":{"url":"${fixture.assetUrl}","headers":[]}}}}}}""",
                )
                else -> error("Unexpected operation: $operation")
            }
        }
        val directory = createTempDirectory("kit-mcp-image-test")
        try {
            val result = KitToolRegistrar.handleChat(
                KitApi(NetworkClient(fixture.graphQlUrl), pollIntervalMillis = 1),
                buildJsonObject {
                    put("action", "ask")
                    put("message", "Generate a small image")
                    put("downloadDirectory", directory.toString())
                },
            )

            val image = assertIs<ImageContent>(result.content.first { it is ImageContent })
            assertContentEquals(imageBytes, Base64.getDecoder().decode(image.data))
            val resource = assertIs<ResourceLink>(result.content.first { it is ResourceLink })
            assertTrue(resource.uri.startsWith("file:"))
            val output = directory.resolve("$IMAGE_ID.png")
            assertContentEquals(imageBytes, Files.readAllBytes(output))

            val structuredImage = requireNotNull(result.structuredContent)
                .getValue("images").jsonArray.single().jsonObject
            assertEquals(IMAGE_ID, structuredImage.getValue("metadataId").jsonPrimitive.content)
            assertEquals(output.toAbsolutePath().toString(), structuredImage.getValue("path").jsonPrimitive.content)
        } finally {
            directory.toFile().deleteRecursively()
            fixture.close()
        }
    }

    @Test
    fun `download writes a Kit asset to an explicit file without overwriting by default`() = runBlocking {
        val bytes = "image-data".encodeToByteArray()
        lateinit var fixture: Fixture
        fixture = Fixture(bytes) { operation ->
            require(operation == "GetMetadataDownload")
            graphQl(
                """{"content":{"metadata":{"content":{"type":"image/webp","length":10,"urls":{"download":{"url":"${fixture.assetUrl}","headers":[{"name":"X-Asset-Key","value":"kit-test"}]}}}}}}""",
            )
        }
        val directory = createTempDirectory("kit-mcp-download-test")
        val output = directory.resolve("result.webp")
        try {
            val api = KitApi(NetworkClient(fixture.graphQlUrl), pollIntervalMillis = 1)
            val result = KitToolRegistrar.handleDownload(
                api,
                buildJsonObject {
                    put("metadataId", IMAGE_ID)
                    put("outputPath", output.toString())
                },
            )

            assertContentEquals(bytes, Files.readAllBytes(output))
            assertTrue(result.content.any { it is ResourceLink })
            assertEquals("kit-test", fixture.assetRequestHeaders["X-asset-key"]?.single())
            assertFailsWith<IllegalStateException> {
                KitToolRegistrar.handleDownload(
                    api,
                    buildJsonObject {
                        put("metadataId", IMAGE_ID)
                        put("outputPath", output.toString())
                    },
                )
            }
            Unit
        } finally {
            directory.toFile().deleteRecursively()
            fixture.close()
        }
    }

    private class Fixture(
        private val asset: ByteArray = byteArrayOf(),
        private val response: (String) -> String,
    ) {
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val operations = mutableListOf<String>()
        val assetRequestHeaders = mutableMapOf<String, List<String>>()

        init {
            server.createContext("/") { exchange ->
                if (exchange.requestURI.path == "/asset") {
                    exchange.requestHeaders.forEach { (name, values) -> assetRequestHeaders[name] = values }
                    exchange.sendResponseHeaders(200, asset.size.toLong())
                    exchange.responseBody.use { it.write(asset) }
                } else {
                    val request = GraphQLJson.parseToJsonElement(exchange.requestBody.readBytes().decodeToString()).jsonObject
                    val operation = request.getValue("operationName").jsonPrimitive.content
                    operations += operation
                    val bytes = response(operation).encodeToByteArray()
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
            }
            server.start()
        }

        val graphQlUrl: String get() = "http://127.0.0.1:${server.address.port}/graphql"
        val assetUrl: String get() = "http://127.0.0.1:${server.address.port}/asset"

        fun close() = server.stop(0)
    }

    private companion object {
        const val SESSION_ID = "11111111-1111-1111-1111-111111111111"
        const val ASSISTANT_MESSAGE_ID = "22222222-2222-2222-2222-222222222222"
        const val DISPLAY_MESSAGE_ID = "33333333-3333-3333-3333-333333333333"
        const val IMAGE_ID = "44444444-4444-4444-4444-444444444444"
        const val OLD_MESSAGE_ID = "55555555-5555-5555-5555-555555555555"
        const val OTHER_SESSION_ID = "66666666-6666-6666-6666-666666666666"
        const val CREATED = "2026-08-08T12:00:00Z"

        fun graphQl(data: String): String = """{"data":$data}"""

        fun session(
            messages: String,
            processing: Boolean = false,
            status: String = "COMPLETED",
        ): String = """
            {
              "id":"$SESSION_ID",
              "agentKey":"kit",
              "title":"Analytics",
              "processing":$processing,
              "status":"$status",
              "created":"$CREATED",
              "modified":"$CREATED",
              "messages":$messages
            }
        """.trimIndent()

        fun summary(id: String = SESSION_ID, agentKey: String = "kit"): String = """
            {
              "id":"$id",
              "agentKey":"$agentKey",
              "title":"Analytics",
              "processing":false,
              "status":"COMPLETED",
              "created":"$CREATED",
              "modified":"$CREATED"
            }
        """.trimIndent()

        fun analyticsMessages(): String = JsonArray(
            listOf(
                message(
                    ASSISTANT_MESSAGE_ID,
                    "Assistant",
                    """{"type":"ai.koog.prompt.message.Message.Assistant","parts":[{"text":"There were 42 visits."}]}""",
                ),
                message(
                    DISPLAY_MESSAGE_ID,
                    "kit",
                    """{"type":"tool-display","title":"Visits","visualizationType":"NUMBER","configuration":{"value":"visits"},"data":[{"visits":42}]}""",
                ),
            ),
        ).toString()

        fun imageMessages(): String = JsonArray(
            listOf(
                message(
                    ASSISTANT_MESSAGE_ID,
                    "Assistant",
                    """{"type":"ai.koog.prompt.message.Message.Assistant","parts":[{"text":"Created it. ![Generated Image](/api/v1/content/metadata/download?id=$IMAGE_ID)"}]}""",
                ),
            ),
        ).toString()

        fun textMessages(vararg messages: Pair<String, String>): String = JsonArray(
            messages.map { (id, text) ->
                message(
                    id,
                    "Assistant",
                    """{"type":"ai.koog.prompt.message.Message.Assistant","parts":[{"text":"$text"}]}""",
                )
            },
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
