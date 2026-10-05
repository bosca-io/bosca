package bosca.community.events

import bosca.communications.model.MessageContent
import bosca.communications.model.MessageContentType
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class ChatMessageSentEventTest {

    private val channelId = Uuid.random()
    private val senderId = Uuid.random()

    @Test
    fun `ChatMessageSentEvent stores all explicit properties`() {
        val content = listOf(
            MessageContent(MessageContentType.TEXT, "Hello"),
            MessageContent(MessageContentType.HTML, "<b>Bold</b>")
        )
        val attrs = JsonPrimitive("extra")
        val event = ChatMessageSentEvent(
            channelId = channelId,
            senderId = senderId,
            sequence = 7L,
            content = content,
            attributes = attrs
        )
        assertEquals(channelId, event.channelId)
        assertEquals(senderId, event.senderId)
        assertEquals(7L, event.sequence)
        assertEquals(content, event.content)
        assertEquals(attrs, event.attributes)
    }

    @Test
    fun `ChatMessageSentEvent attributes defaults to null`() {
        val event = ChatMessageSentEvent(
            channelId = channelId,
            senderId = senderId,
            sequence = 1L,
            content = emptyList()
        )
        assertNull(event.attributes)
    }

    @Test
    fun `ChatMessageSentEvent equality`() {
        val content = listOf(MessageContent(MessageContentType.TEXT, "hi"))
        val a = ChatMessageSentEvent(channelId, senderId, 1L, content)
        val b = ChatMessageSentEvent(channelId, senderId, 1L, content)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `ChatMessageSentEvent with empty content list`() {
        val event = ChatMessageSentEvent(channelId, senderId, 0L, emptyList())
        assertEquals(emptyList(), event.content)
        assertEquals(0L, event.sequence)
    }
}
