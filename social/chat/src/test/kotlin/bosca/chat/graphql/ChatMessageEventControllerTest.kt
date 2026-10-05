package bosca.chat.graphql

import bosca.chat.model.ChatMessage
import bosca.chat.model.ChatMessageEvent
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class ChatMessageEventControllerTest {

    private val controller = ChatMessageEventController()

    @Test
    fun `channelId is exposed`() {
        val channelId = UUID.random()
        val message = ChatMessage(sequence = 1L, timestamp = OffsetDateTime.now(), senderId = UUID.random(), content = emptyList())
        val event = ChatMessageEvent(channelId, message)
        assertEquals(channelId, controller.channelId(event))
    }

    @Test
    fun `message is exposed`() {
        val message = ChatMessage(sequence = 99L, timestamp = OffsetDateTime.now(), senderId = UUID.random(), content = emptyList())
        val event = ChatMessageEvent(UUID.random(), message)
        assertEquals(message, controller.message(event))
    }
}
