package bosca.ai.chat.model

import ai.koog.prompt.message.Message
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class ChatMessageTest {

    @Test
    fun `toMessage converts user role to User message`() {
        val chatMessage = ChatMessage(
            id = "msg-1",
            role = "user",
            parts = listOf(ChatMessagePart("text", "Hello"))
        )
        val result = chatMessage.toMessage()
        assertIs<Message.User>(result)
    }

    @Test
    fun `toMessage converts assistant role to Assistant message`() {
        val chatMessage = ChatMessage(
            id = "msg-2",
            role = "assistant",
            parts = listOf(ChatMessagePart("text", "Hi there"))
        )
        val result = chatMessage.toMessage()
        assertIs<Message.Assistant>(result)
    }

    @Test
    fun `toMessage preserves text content from parts`() {
        val chatMessage = ChatMessage(
            id = "msg-3",
            role = "user",
            parts = listOf(
                ChatMessagePart("text", "First part"),
                ChatMessagePart("text", "Second part")
            )
        )
        val result = chatMessage.toMessage()
        assertIs<Message.User>(result)
        assertEquals(2, result.parts.size)
    }

    @Test
    fun `toMessage throws for unknown role`() {
        val chatMessage = ChatMessage(
            id = "msg-4",
            role = "system",
            parts = listOf(ChatMessagePart("text", "System prompt"))
        )
        val error = assertFailsWith<IllegalStateException> {
            chatMessage.toMessage()
        }
        assertEquals("Unknown role: system", error.message)
    }

    @Test
    fun `toMessage handles empty parts list`() {
        val chatMessage = ChatMessage(
            id = "msg-5",
            role = "user",
            parts = emptyList()
        )
        val result = chatMessage.toMessage()
        assertIs<Message.User>(result)
        assertEquals(0, result.parts.size)
    }
}
