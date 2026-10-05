package bosca.community.events

import bosca.chat.model.ChatChannelType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class ChatChannelCreatedEventTest {

    private val channelId = Uuid.random()
    private val groupId = Uuid.random()

    @Test
    fun `ChatChannelCreatedEvent stores all properties`() {
        val event = ChatChannelCreatedEvent(
            channelId = channelId,
            groupId = groupId,
            name = "General",
            type = ChatChannelType.GROUP
        )
        assertEquals(channelId, event.channelId)
        assertEquals(groupId, event.groupId)
        assertEquals("General", event.name)
        assertEquals(ChatChannelType.GROUP, event.type)
    }

    @Test
    fun `ChatChannelCreatedEvent with null groupId`() {
        val event = ChatChannelCreatedEvent(
            channelId = channelId,
            groupId = null,
            name = "DM",
            type = ChatChannelType.DIRECT
        )
        assertNull(event.groupId)
    }

    @Test
    fun `ChatChannelCreatedEvent equality`() {
        val a = ChatChannelCreatedEvent(channelId, groupId, "Ch", ChatChannelType.PUBLIC)
        val b = ChatChannelCreatedEvent(channelId, groupId, "Ch", ChatChannelType.PUBLIC)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `ChatChannelCreatedEvent with each channel type`() {
        for (type in ChatChannelType.entries) {
            val event = ChatChannelCreatedEvent(channelId, null, "test", type)
            assertEquals(type, event.type)
        }
    }

    @Test
    fun `CHAT_CHANNEL_CREATED_TOPIC constant has expected value`() {
        assertEquals("bosca.community.v1.channel.created", CHAT_CHANNEL_CREATED_TOPIC)
    }
}
