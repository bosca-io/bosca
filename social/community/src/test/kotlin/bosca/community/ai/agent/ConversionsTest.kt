package bosca.community.ai.agent

import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import bosca.chat.model.ChatMessage
import bosca.communications.model.MessageContent
import bosca.communications.model.MessageContentType
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ConversionsTest {

    @Test
    fun `toContentPart converts TEXT MessageContent to Text ContentPart`() {
        val mc = MessageContent(MessageContentType.TEXT, "hello world")
        val part = mc.toContentPart()
        assertIs<MessagePart.Text>(part)
        assertEquals("hello world", part.text)
    }

    @Test
    fun `toContentPart converts HTML MessageContent to Text ContentPart`() {
        val mc = MessageContent(MessageContentType.HTML, "<b>bold</b>")
        val part = mc.toContentPart()
        assertIs<MessagePart.Text>(part)
        assertEquals("<b>bold</b>", part.text)
    }

    @Test
    fun `toMessage returns Assistant message when sender matches agentProfileId`() {
        val agentId = UUID.random()
        val message = ChatMessage(
            sequence = 1,
            timestamp = OffsetDateTime.now(),
            senderId = agentId,
            content = listOf(MessageContent(MessageContentType.TEXT, "I am the agent"))
        )
        val result = message.toMessage(agentId)
        assertIs<Message.Assistant>(result)
        assertEquals(1, result.parts.size)
    }

    @Test
    fun `toMessage returns User message when sender does not match agentProfileId`() {
        val agentId = UUID.random()
        val userId = UUID.random()
        val message = ChatMessage(
            sequence = 2,
            timestamp = OffsetDateTime.now(),
            senderId = userId,
            content = listOf(MessageContent(MessageContentType.TEXT, "I am a user"))
        )
        val result = message.toMessage(agentId)
        assertIs<Message.User>(result)
        assertEquals(1, result.parts.size)
    }

    @Test
    fun `toMessage maps multiple content items`() {
        val agentId = UUID.random()
        val message = ChatMessage(
            sequence = 3,
            timestamp = OffsetDateTime.now(),
            senderId = UUID.random(),
            content = listOf(
                MessageContent(MessageContentType.TEXT, "first"),
                MessageContent(MessageContentType.TEXT, "second"),
                MessageContent(MessageContentType.HTML, "third")
            )
        )
        val result = message.toMessage(agentId)
        assertIs<Message.User>(result)
        assertEquals(3, result.parts.size)
    }

    @Test
    fun `toMessage with empty content list`() {
        val agentId = UUID.random()
        val message = ChatMessage(
            sequence = 4,
            timestamp = OffsetDateTime.now(),
            senderId = agentId,
            content = emptyList()
        )
        val result = message.toMessage(agentId)
        assertIs<Message.Assistant>(result)
        assertEquals(0, result.parts.size)
    }
}
