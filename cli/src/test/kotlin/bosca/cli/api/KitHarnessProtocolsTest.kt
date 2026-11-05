package bosca.cli.api

import bosca.cli.acp.KitAcpAgentSession
import bosca.cli.mcp.KitToolRegistrar
import bosca.graphql.client.GraphQLJson
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.StopReason
import com.agentclientprotocol.model.ToolCallContent
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.ResourceLink
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import java.nio.file.Files
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*
import kotlin.uuid.Uuid

class KitHarnessProtocolsTest {
    @Test
    fun `ACP preserves correlated tools resources and diffs without fetching assets or stopping at a streaming snapshot`() = runBlocking {
        KitHarnessFixture().use { fixture ->
            val session = KitAcpAgentSession(fixture.api(), Uuid.parse(KitHarnessFixture.SESSION_ID), 5_000)
            val events = session.prompt(listOf(ContentBlock.Text("Report")), null).toList()
            val updates = events.filterIsInstance<Event.SessionUpdateEvent>().map { it.update }
            assertIs<SessionUpdate.PlanUpdate>(updates.first())
            val tool = updates.filterIsInstance<SessionUpdate.ToolCall>().single()
            assertEquals("${KitHarnessFixture.TURN_ID}/result", tool.toolCallId.value)
            assertEquals(4, tool.content.size)
            assertIs<ToolCallContent.Diff>(tool.content.last())
            assertIs<ContentBlock.Resource>((tool.content[0] as ToolCallContent.Content).content)
            assertIs<ContentBlock.Image>((tool.content[1] as ToolCallContent.Content).content)
            assertEquals(StopReason.END_TURN, (events.last() as Event.PromptResponseEvent).response.stopReason)
            assertFalse(updates.joinToString().contains("private-"))
            assertEquals(listOf("GetKitChatSession", "SendKitChatMessage", "GetKitChatSession", "GetKitChatSession"), fixture.operations)
        }
    }

    @Test
    fun `ACP reports cancellation and uncertain outcomes even when useful tool results exist`() = runBlocking {
        for ((status, stop) in listOf("CANCELLED" to StopReason.CANCELLED, "INTERRUPTED" to StopReason.REFUSAL, "FAILED" to StopReason.REFUSAL)) {
            KitHarnessFixture(status).use { fixture ->
                val events = KitAcpAgentSession(fixture.api(), Uuid.parse(KitHarnessFixture.SESSION_ID), 5_000)
                    .prompt(listOf(ContentBlock.Text("Report")), null).toList()
                assertEquals(stop, (events.last() as Event.PromptResponseEvent).response.stopReason)
                val text = events.filterIsInstance<Event.SessionUpdateEvent>().mapNotNull {
                    ((it.update as? SessionUpdate.AgentMessageChunk)?.content as? ContentBlock.Text)?.text
                }.joinToString()
                assertTrue(text.contains(when (status) { "CANCELLED" -> "cancelled"; "INTERRUPTED" -> "could not be confirmed"; else -> "failed" }))
            }
        }
    }

    @Test
    fun `MCP forwards typed results and honors image suppression and explicit local saving`() = runBlocking {
        val directory = Files.createTempDirectory("kit-inline-images-")
        try {
            KitHarnessFixture().use { fixture ->
                val result = KitToolRegistrar.handleChat(fixture.api(), buildJsonObject {
                    put("action", "ask"); put("message", "Report"); put("includeImages", false); put("downloadDirectory", directory.toString())
                })
                assertFalse(result.isError == true)
                assertTrue(result.content.none { it is ImageContent })
                val image = result.content.filterIsInstance<ResourceLink>().single { it.uri.startsWith("file:") }
                assertContentEquals("hello".encodeToByteArray(), Files.readAllBytes(java.nio.file.Path.of(java.net.URI(image.uri))))
                val history = KitToolRegistrar.handleChat(fixture.api(), buildJsonObject {
                    put("action", "get"); put("sessionId", KitHarnessFixture.SESSION_ID)
                })
                val document = GraphQLJson.parseToJsonElement((history.content.first() as TextContent).text).jsonObject
                val saved = document.getValue("session").jsonObject.getValue("messages").jsonArray.single().jsonObject.getValue("event").jsonObject
                assertEquals("harness-turn", saved.getValue("type").jsonPrimitive.content)
                assertTrue(saved.getValue("content").jsonArray.isNotEmpty())
                assertFalse(document.toString().contains("private-"))
                val serialized = GraphQLJson.encodeToString(CallToolResult.serializer(), result)
                assertFalse(serialized.contains("private-"))
                val turns = requireNotNull(result.structuredContent).getValue("harnessTurns").jsonArray
                assertEquals("COMPLETED", turns.single().jsonObject.getValue("status").jsonPrimitive.content)
                assertFalse(turns.toString().contains("aGVsbG8="))
                assertEquals(3, fixture.operations.count { it == "GetKitChatSession" })
                assertTrue(fixture.operations.none { it.contains("Metadata") })
                val again = KitToolRegistrar.handleChat(fixture.api(), buildJsonObject {
                    put("action", "ask"); put("message", "Report"); put("downloadDirectory", directory.toString())
                })
                assertTrue(again.content.any { it is ImageContent })
                assertTrue((again.content.first() as TextContent).text.contains("Image retrieval errors"))
                assertContentEquals("hello".encodeToByteArray(), Files.readAllBytes(java.nio.file.Path.of(java.net.URI(image.uri))))
            }
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test
    fun `unfinished content is not evidence of a successful terminal outcome`() = runBlocking {
        KitHarnessFixture().use { fixture ->
            val turn = fixture.api().ask("Report", timeoutMillis = 5_000)
            val partial = turn.messages.single().copy(event = JsonObject(fixture.event + ("status" to JsonPrimitive("STREAMING"))))
            val response = turn.copy(messages = listOf(partial)).responseContent()
            assertTrue(response.failed)
            assertContains(assertNotNull(response.outcomeText), "may still be running")
        }
    }

    @Test
    fun `repeated snapshots select the newest owner revision and keep canonical text once`() = runBlocking {
        KitHarnessFixture().use { fixture ->
            val turn = fixture.api().ask("Report", timeoutMillis = 5_000)
            val current = turn.messages.single().copy(event = JsonObject(fixture.event + ("text" to JsonPrimitive("partial"))))
            val old = current.copy(event = JsonObject(fixture.event + mapOf("epoch" to JsonPrimitive(3), "revision" to JsonPrimitive(100), "text" to JsonPrimitive("stale"))))
            val canonical = current.copy(id = Uuid.random(), author = "assistant", event = buildJsonObject {
                putJsonArray("parts") { add(buildJsonObject { put("text", "Final answer") }) }
            })
            val response = turn.copy(messages = listOf(current, old, current, canonical)).responseContent()
            assertEquals(1, response.harnessTurns.size)
            assertEquals(4L, response.harnessTurns.single().epoch)
            assertEquals("Final answer", response.text)
        }
    }
}
