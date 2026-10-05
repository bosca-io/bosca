package bosca.ai.chat.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class ChatSessionInputTest {

    @Test
    fun `ChatSessionInput stores all explicit properties`() {
        val state = JsonObject(mapOf("key" to JsonPrimitive("value")))
        val input = ChatSessionInput(
            agentKey = "search-agent",
            title = "My Session",
            state = state
        )
        assertEquals("search-agent", input.agentKey)
        assertEquals("My Session", input.title)
        assertEquals(state, input.state)
    }

    @Test
    fun `ChatSessionInput title defaults to empty string`() {
        val input = ChatSessionInput(agentKey = "agent")
        assertEquals("", input.title)
    }

    @Test
    fun `ChatSessionInput state defaults to empty JsonObject`() {
        val input = ChatSessionInput(agentKey = "agent")
        assertEquals(JsonObject(emptyMap()), input.state)
    }

    @Test
    fun `ChatSessionInput equality`() {
        val a = ChatSessionInput("agent", "title")
        val b = ChatSessionInput("agent", "title")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `ChatSessionInput copy changes agentKey`() {
        val original = ChatSessionInput("old-agent", "title")
        val copied = original.copy(agentKey = "new-agent")
        assertEquals("new-agent", copied.agentKey)
        assertEquals("title", copied.title)
    }
}
