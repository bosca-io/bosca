@file:OptIn(com.agentclientprotocol.annotations.UnstableApi::class)

package bosca.cli.acp

import bosca.cli.api.KitApi
import bosca.cli.api.NetworkClient
import bosca.graphql.client.GraphQLJson
import com.agentclientprotocol.client.ClientInfo
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.EmbeddedResourceResource
import com.agentclientprotocol.model.LATEST_PROTOCOL_VERSION
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.StopReason
import com.agentclientprotocol.rpc.ACPJson
import com.github.ajalt.clikt.testing.test
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.Base64
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class KitAcpAgentTest {

    @Test
    fun `ACP command advertises connection options and validates timeout`() {
        val help = KitAcpServerCommand().test("--help")
        assertEquals(0, help.statusCode)
        for (option in listOf("--timeout-seconds", "--url", "--token", "--username", "--password")) {
            assertTrue(option in help.stdout, "Expected $option in help: ${help.stdout}")
        }

        val invalid = KitAcpServerCommand().test("--timeout-seconds 0")
        assertTrue(invalid.statusCode != 0)
        assertTrue("--timeout-seconds must be between" in invalid.stderr)
    }

    @Test
    fun `ACP session maps Kit text analytics and images to content blocks`() = runBlocking {
        val imageBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)
        Fixture(imageBytes = imageBytes).use { fixture ->
            val support = KitAcpAgentSupport(fixture.api(), timeoutMillis = 5_000)
            val info = support.initialize(ClientInfo())
            assertEquals(LATEST_PROTOCOL_VERSION, info.protocolVersion)
            assertTrue(info.capabilities.loadSession)
            val implementation = requireNotNull(info.implementation)
            assertEquals("bosca-kit", implementation.name)
            assertEquals("Kit", implementation.title)

            val session = support.createSession(sessionParameters("/workspaces/kit-demo"))
            assertEquals(SESSION_ID, session.sessionId.value)
            val events = session.prompt(listOf(ContentBlock.Text("How many visits?")), null).toList()

            val chunks = events.filterIsInstance<Event.SessionUpdateEvent>()
                .map { assertIs<SessionUpdate.AgentMessageChunk>(it.update).content }
            val text = assertIs<ContentBlock.Text>(chunks[0])
            assertTrue("42 visits" in text.text)
            assertTrue("![Chart](${fixture.assetUrl})" in text.text)
            assertTrue("[Open Chart](${fixture.assetUrl})" in text.text)
            assertTrue("/api/v1/content/metadata/download" !in text.text)

            val resource = assertIs<ContentBlock.Resource>(chunks[1])
            val analytics = assertIs<EmbeddedResourceResource.TextResourceContents>(resource.resource)
            assertEquals("application/json", analytics.mimeType)
            assertEquals("bosca://kit/sessions/$SESSION_ID/analytics", analytics.uri)
            assertEquals(
                "tool-display",
                GraphQLJson.parseToJsonElement(analytics.text).jsonArray.single().jsonObject
                    .getValue("type").jsonPrimitive.content,
            )

            val image = assertIs<ContentBlock.Image>(chunks[2])
            assertEquals("image/png", image.mimeType)
            assertEquals("bosca://content/metadata/$IMAGE_ID", image.uri)
            assertContentEquals(imageBytes, Base64.getDecoder().decode(image.data))

            val response = assertIs<Event.PromptResponseEvent>(events.last()).response
            assertEquals(StopReason.END_TURN, response.stopReason)
            assertEquals(
                listOf(
                    "CreateKitChatSession",
                    "GetKitChatSession",
                    "SendKitChatMessage",
                    "GetKitChatSession",
                    "GetMetadataDownload",
                ),
                fixture.operations(),
            )

            val loaded = support.loadSession(SessionId(SESSION_ID), sessionParameters("/workspaces/kit-demo"))
            assertEquals(session.sessionId, loaded.sessionId)
            assertEquals("GetKitChatSession", fixture.operations().last())
        }
    }

    @Test
    fun `ACP embedded text resources serialize to the protocol wire format`() {
        val content = ContentBlock.Resource(
            EmbeddedResourceResource.TextResourceContents(
                text = "[{\"type\":\"tool-display\"}]",
                uri = "bosca://kit/sessions/$SESSION_ID/analytics",
                mimeType = "application/json",
            )
        )

        val encoded = ACPJson.encodeToString(ContentBlock.serializer(), content)
        val json = GraphQLJson.parseToJsonElement(encoded).jsonObject

        assertEquals("resource", json.getValue("type").jsonPrimitive.content)
        val resource = json.getValue("resource").jsonObject
        assertEquals("[{\"type\":\"tool-display\"}]", resource.getValue("text").jsonPrimitive.content)
        assertEquals("bosca://kit/sessions/$SESSION_ID/analytics", resource.getValue("uri").jsonPrimitive.content)
        assertEquals("application/json", resource.getValue("mimeType").jsonPrimitive.content)
    }

    @Test
    fun `native image retains ACP embedded resource serializers`() {
        val stream = requireNotNull(
            javaClass.classLoader.getResourceAsStream(
                "META-INF/native-image/bosca/cli/reachability-metadata.json"
            )
        )
        val reflection = stream.bufferedReader().use { reader ->
            GraphQLJson.parseToJsonElement(reader.readText()).jsonObject
                .getValue("reflection").jsonArray
                .map { it.jsonObject }
        }

        for (resourceType in listOf("BlobResourceContents", "TextResourceContents")) {
            val className = "com.agentclientprotocol.model.EmbeddedResourceResource${'$'}$resourceType"
            val resourceClass = reflection.single { it.getValue("type").jsonPrimitive.content == className }
            assertEquals(
                listOf("Companion"),
                resourceClass.getValue("fields").jsonArray.map {
                    it.jsonObject.getValue("name").jsonPrimitive.content
                },
            )

            val companion = reflection.single {
                it.getValue("type").jsonPrimitive.content == "$className${'$'}Companion"
            }
            val serializer = companion.getValue("methods").jsonArray.single().jsonObject
            assertEquals("serializer", serializer.getValue("name").jsonPrimitive.content)
            assertTrue(serializer.getValue("parameterTypes").jsonArray.isEmpty())
        }
    }

    @Test
    fun `ACP rejects invalid and empty prompts without creating Kit work`() = runBlocking {
        Fixture().use { fixture ->
            val support = KitAcpAgentSupport(fixture.api(), timeoutMillis = 1_000)
            assertFailsWith<IllegalArgumentException> {
                support.loadSession(SessionId("not-a-uuid"), sessionParameters("/workspace"))
            }
            assertTrue(fixture.operations().isEmpty())

            val session = KitAcpAgentSession(fixture.api(), kotlin.uuid.Uuid.parse(SESSION_ID), 1_000)
            val events = session.prompt(emptyList(), null).toList()
            val message = assertIs<SessionUpdate.AgentMessageChunk>(
                assertIs<Event.SessionUpdateEvent>(events.first()).update,
            )
            assertTrue("non-empty text prompt" in assertIs<ContentBlock.Text>(message.content).text)
            assertEquals(
                StopReason.REFUSAL,
                assertIs<Event.PromptResponseEvent>(events.last()).response.stopReason,
            )
            assertTrue(fixture.operations().isEmpty())
        }
    }

    @Test
    fun `ACP cancellation stops the active Kit turn`() = runBlocking {
        Fixture(processForever = true).use { fixture ->
            val session = KitAcpAgentSession(
                fixture.api(pollIntervalMillis = 25),
                kotlin.uuid.Uuid.parse(SESSION_ID),
                30_000,
            )
            val turn = async { session.prompt(listOf(ContentBlock.Text("Keep working")), null).toList() }

            withTimeout(5_000) {
                while (fixture.operationCount("GetKitChatSession") < 2) delay(10)
            }
            session.cancel()
            try {
                turn.await()
                fail("Expected the active ACP prompt to be cancelled")
            } catch (_: CancellationException) {
                // Cancellation is the ACP contract for an interrupted prompt.
            }
        }
    }

    private fun sessionParameters(cwd: String) = SessionCreationParameters(cwd = cwd, mcpServers = emptyList())

    private class Fixture(
        private val imageBytes: ByteArray = byteArrayOf(1),
        private val processForever: Boolean = false,
    ) : AutoCloseable {
        private val getCount = AtomicInteger()
        private val recordedOperations = Collections.synchronizedList(mutableListOf<String>())
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

        init {
            server.createContext("/") { exchange ->
                if (exchange.requestURI.path == "/asset") {
                    exchange.responseHeaders.add("Content-Type", "image/png")
                    exchange.sendResponseHeaders(200, imageBytes.size.toLong())
                    exchange.responseBody.use { it.write(imageBytes) }
                } else {
                    val request = GraphQLJson.parseToJsonElement(exchange.requestBody.readBytes().decodeToString()).jsonObject
                    val operation = request.getValue("operationName").jsonPrimitive.content
                    recordedOperations += operation
                    val response = when (operation) {
                        "CreateKitChatSession" -> graphQl("""{"chatSessions":{"create":${session("[]")}}}""")
                        "SendKitChatMessage" -> graphQl("""{"chatSessions":{"send":true}}""")
                        "GetKitChatSession" -> {
                            val count = getCount.incrementAndGet()
                            val messages = if (!processForever && count >= 2) responseMessages() else "[]"
                            graphQl("""{"chatSessions":{"session":${session(messages, processForever && count >= 2)}}}""")
                        }
                        "GetMetadataDownload" -> graphQl(
                            """{"content":{"metadata":{"content":{"type":"image/png","length":${imageBytes.size},"urls":{"download":{"url":"$assetUrl","headers":[]}}}}}}""",
                        )
                        else -> error("Unexpected operation: $operation")
                    }.encodeToByteArray()
                    exchange.responseHeaders.add("Content-Type", "application/json")
                    exchange.sendResponseHeaders(200, response.size.toLong())
                    exchange.responseBody.use { it.write(response) }
                }
            }
            server.start()
        }

        fun api(pollIntervalMillis: Long = 1) =
            KitApi(NetworkClient("http://127.0.0.1:${server.address.port}/graphql"), pollIntervalMillis)

        fun operations(): List<String> = synchronized(recordedOperations) { recordedOperations.toList() }

        fun operationCount(operation: String) = synchronized(recordedOperations) {
            recordedOperations.count { it == operation }
        }

        override fun close() = server.stop(0)

        val assetUrl: String get() = "http://127.0.0.1:${server.address.port}/asset"
    }

    private companion object {
        const val SESSION_ID = "11111111-1111-1111-1111-111111111111"
        const val MESSAGE_ID = "22222222-2222-2222-2222-222222222222"
        const val DISPLAY_MESSAGE_ID = "33333333-3333-3333-3333-333333333333"
        const val IMAGE_ID = "44444444-4444-4444-4444-444444444444"
        const val CREATED = "2026-08-09T12:00:00Z"

        fun graphQl(data: String): String = """{"data":$data}"""

        fun session(messages: String, processing: Boolean = false): String = """
            {
              "id":"$SESSION_ID",
              "agentKey":"kit",
              "title":"Kit ACP conversation",
              "processing":$processing,
              "status":"COMPLETED",
              "created":"$CREATED",
              "modified":"$CREATED",
              "messages":$messages
            }
        """.trimIndent()

        fun responseMessages(): String = JsonArray(
            listOf(
                message(
                    MESSAGE_ID,
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

        fun message(id: String, author: String, event: String): JsonObject = buildJsonObject {
            put("id", id)
            put("sessionId", SESSION_ID)
            put("author", author)
            put("created", CREATED)
            put("event", GraphQLJson.parseToJsonElement(event))
        }
    }
}
