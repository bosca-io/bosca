package bosca.ai.kit.harness.runtime

import bosca.ai.kit.harness.*
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import com.agentclientprotocol.model.*
import kotlinx.serialization.json.*
import kotlin.test.*

class KitRuntimeTranscriptTest {
    private val json = Json
    private val session = KitRuntimeSession(UUID.random(), UUID.random(), JsonObject(emptyMap()), JsonObject(emptyMap()),
        generation = 3, epoch = 8, idleAt = OffsetDateTime.now())
    private val turn = KitRuntimeTurn(UUID.random(), session.sessionId, message = JsonObject(emptyMap()))

    @Test
    fun `Codex MCP results retain typed resources without opaque data or duplicate text on replay`() {
        val transcript = KitRuntimeTranscript(session, turn, json)
        val update = json.decodeFromString(SessionUpdate.serializer(), """{
          "sessionUpdate":"tool_call_update","toolCallId":"mcp","status":"completed",
          "rawInput":{"server":"bosca","tool":"analytics_query","arguments":{"token":"private-input"}},
          "rawOutput":{"result":{"content":[
            {"type":"text","text":"{\"records\":[]}"},
            {"type":"resource","resource":{"uri":"bosca://analytics/queries/by-key/traffic/results","mimeType":"application/json","text":"{\"records\":[]}","_meta":{"secret":"private-resource"}}},
            {"type":"image","data":"aGVsbG8=","mimeType":"image/png","_meta":{"secret":"private-image"}},
            {"type":"resource_link","name":"Plan","uri":"bosca://content/metadata/11111111-1111-1111-1111-111111111111","icons":[{"src":"private-icon"}]},
            {"type":"text","text":"private-assistant","annotations":{"audience":["assistant"]}},
            {"type":"future-type","data":"private-unsupported"}
          ],"structuredContent":{"secret":"private-structured"}},"error":{"secret":"private-error"}}
        }""")
        transcript.accept(SessionUpdate.ToolCall(ToolCallId("mcp"), "mcp.bosca.analytics_query"))
        repeat(2) { transcript.accept(update) }
        val snapshot = assertNotNull(transcript.snapshot("COMPLETED", true)).event
        val event = json.decodeFromJsonElement(KitHarnessTurnEvent.serializer(), snapshot)
        val tool = event.tools.single()
        assertEquals("mcp.bosca.analytics_query", tool["title"]?.jsonPrimitive?.content)
        val blocks = tool.getValue("content").jsonArray.map { it.jsonObject.getValue("content").jsonObject }
        assertEquals(listOf("resource", "image", "resource_link", "text"), blocks.map { it.getValue("type").jsonPrimitive.content })
        assertEquals("The tool returned an unsupported content block.", blocks.last()["text"]?.jsonPrimitive?.content)
        assertFalse(snapshot.toString().contains("private-"))
        assertFalse(snapshot.toString().contains("rawOutput"))
        assertFalse(snapshot.toString().contains("rawInput"))
    }

    @Test
    fun `unrelated opaque tool output is never promoted to display content`() {
        val transcript = KitRuntimeTranscript(session, turn, json)
        transcript.accept(SessionUpdate.ToolCall(ToolCallId("shell"), "Shell", rawOutput =
            json.parseToJsonElement("""{"result":{"content":[{"type":"text","text":"private-output"}]}}""")))
        assertFalse(assertNotNull(transcript.snapshot(force = true)).event.toString().contains("private-output"))
    }

    @Test
    fun `text is coalesced and final snapshot retains typed content and merged tools without raw arguments`() {
        val transcript = KitRuntimeTranscript(session, turn, json)
        transcript.accept(SessionUpdate.AgentMessageChunk(ContentBlock.Text("A")))
        val first = assertNotNull(transcript.snapshot())
        repeat(100) { transcript.accept(SessionUpdate.AgentMessageChunk(ContentBlock.Text("b"))) }
        assertNull(transcript.snapshot())
        transcript.accept(SessionUpdate.ToolCall(ToolCallId("tool"), "Read source", rawInput = JsonPrimitive("credential")))
        transcript.accept(SessionUpdate.ToolCallUpdate(ToolCallId("tool"), status = ToolCallStatus.COMPLETED,
            content = listOf(ToolCallContent.Content(ContentBlock.Text("Found evidence")))))
        transcript.accept(SessionUpdate.AgentMessageChunk(ContentBlock.Image("aW1hZ2U=", "image/png")))
        transcript.accept(SessionUpdate.AgentThoughtChunk(ContentBlock.Text("private reasoning")))
        val last = assertNotNull(transcript.snapshot("COMPLETED", true))
        assertEquals(first.id, last.id)
        val event = json.decodeFromJsonElement(KitHarnessTurnEvent.serializer(), last.event)
        assertEquals("", event.text)
        assertEquals("A" + "b".repeat(100), transcript.responseText)
        assertEquals("Read source", event.tools.single()["title"]?.jsonPrimitive?.content)
        assertEquals("completed", event.tools.single()["status"]?.jsonPrimitive?.content)
        assertEquals("image", event.content.single().jsonObject["type"]?.jsonPrimitive?.content)
        assertFalse(last.event.toString().contains("credential"))
        assertFalse(last.event.toString().contains("private reasoning"))
        assertTrue(event.revision > json.decodeFromJsonElement(KitHarnessTurnEvent.serializer(), first.event).revision)
    }

    @Test
    fun `stored events carry their type discriminator under platform Json that omits defaults`() {
        val platform = Json { encodeDefaults = false; explicitNulls = false }
        val transcript = KitRuntimeTranscript(session, turn, platform)
        transcript.accept(SessionUpdate.AgentMessageChunk(ContentBlock.Text("A")))
        val stored = assertNotNull(transcript.snapshot("COMPLETED", true)).event.jsonObject
        assertEquals("harness-turn", stored["type"]?.jsonPrimitive?.content)
    }
}
