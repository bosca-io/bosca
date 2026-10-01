package bosca.ai.chat.model

import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.uuid.Uuid

class ChatSessionTest {

    private val principalId = Uuid.random()

    // --- ChatSession ---

    @Test
    fun `ChatSession id defaults to NIL`() {
        val session = ChatSession(principalId = principalId, agentKey = "test-agent")
        assertEquals(Uuid.NIL, session.id)
    }

    @Test
    fun `ChatSession status defaults to COMPLETED`() {
        val session = ChatSession(principalId = principalId, agentKey = "test-agent")
        assertEquals(ChatSessionStatus.COMPLETED, session.status)
    }

    @Test
    fun `ChatSession processing defaults to false`() {
        val session = ChatSession(principalId = principalId, agentKey = "test-agent")
        assertFalse(session.processing)
    }

    @Test
    fun `ChatSession title defaults to empty string`() {
        val session = ChatSession(principalId = principalId, agentKey = "test-agent")
        assertEquals("", session.title)
    }

    @Test
    fun `ChatSession state defaults to empty JsonObject`() {
        val session = ChatSession(principalId = principalId, agentKey = "test-agent")
        assertEquals(JsonObject(emptyMap()), session.state)
    }

    @Test
    fun `ChatSession stores principalId and agentKey`() {
        val session = ChatSession(principalId = principalId, agentKey = "my-agent")
        assertEquals(principalId, session.principalId)
        assertEquals("my-agent", session.agentKey)
    }

    // --- ChatSessionInput ---

    @Test
    fun `ChatSessionInput title defaults to empty string`() {
        val input = ChatSessionInput(agentKey = "test")
        assertEquals("", input.title)
    }

    @Test
    fun `ChatSessionInput state defaults to empty JsonObject`() {
        val input = ChatSessionInput(agentKey = "test")
        assertEquals(JsonObject(emptyMap()), input.state)
    }

    @Test
    fun `ChatSessionInput stores agentKey`() {
        val input = ChatSessionInput(agentKey = "my-agent", title = "My Session")
        assertEquals("my-agent", input.agentKey)
        assertEquals("My Session", input.title)
    }

    // --- ChatSessionProcessing ---

    @Test
    fun `ChatSessionProcessing stores processing flag`() {
        val processing = ChatSessionProcessing(processing = true)
        assertEquals(true, processing.processing)
    }
}
