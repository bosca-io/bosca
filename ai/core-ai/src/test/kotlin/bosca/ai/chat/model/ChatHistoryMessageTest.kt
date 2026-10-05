package bosca.ai.chat.model

import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class ChatHistoryMessageTest {

    @Test
    fun `ChatHistoryMessage defaults`() {
        val event = JsonPrimitive("test")
        val msg = ChatHistoryMessage(author = "user", event = event)
        assertEquals(Uuid.NIL, msg.id)
        assertEquals(Uuid.NIL, msg.sessionId)
        assertEquals("user", msg.author)
        assertNull(msg.content)
        assertEquals(event, msg.event)
    }

    @Test
    fun `ChatHistoryMessage stores all properties`() {
        val id = Uuid.random()
        val sessionId = Uuid.random()
        val content = JsonPrimitive("hello")
        val event = JsonPrimitive("msg_event")
        val msg = ChatHistoryMessage(
            id = id,
            sessionId = sessionId,
            author = "assistant",
            content = content,
            event = event
        )
        assertEquals(id, msg.id)
        assertEquals(sessionId, msg.sessionId)
        assertEquals("assistant", msg.author)
        assertEquals(content, msg.content)
        assertEquals(event, msg.event)
    }

    @Test
    fun `ChatSessionStatuses stores status`() {
        val statuses = ChatSessionStatuses(status = ChatSessionStatus.COMPLETED)
        assertEquals(ChatSessionStatus.COMPLETED, statuses.status)
    }
}
