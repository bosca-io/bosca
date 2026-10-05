package bosca.chat.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class ChatChannelTest {

    private val channelId = Uuid.random()

    @Test
    fun `PUBLIC channel has public flags set to true`() {
        val channel = ChatChannel(id = channelId, name = "General", type = ChatChannelType.PUBLIC)
        assertTrue(channel.public)
        assertTrue(channel.publicContent)
        assertTrue(channel.publicList)
    }

    @Test
    fun `PUBLIC channel supplementary is false`() {
        val channel = ChatChannel(id = channelId, name = "General", type = ChatChannelType.PUBLIC)
        assertFalse(channel.publicSupplementary)
    }

    @Test
    fun `DIRECT channel has public flags set to false`() {
        val channel = ChatChannel(id = channelId, name = "DM", type = ChatChannelType.DIRECT)
        assertFalse(channel.public)
        assertFalse(channel.publicContent)
        assertFalse(channel.publicList)
    }

    @Test
    fun `GROUP channel has public flags set to false`() {
        val channel = ChatChannel(id = channelId, name = "Team", type = ChatChannelType.GROUP)
        assertFalse(channel.public)
        assertFalse(channel.publicContent)
        assertFalse(channel.publicList)
    }

    @Test
    fun `ChatChannel isPublished and isAdvertised are true by default`() {
        val channel = ChatChannel(id = channelId, name = "Ch", type = ChatChannelType.PUBLIC)
        assertTrue(channel.isPublished)
        assertTrue(channel.isAdvertised)
        assertFalse(channel.isDeleted)
    }

    @Test
    fun `ChatChannel groupId defaults to null`() {
        val channel = ChatChannel(id = channelId, name = "Ch", type = ChatChannelType.GROUP)
        assertNull(channel.groupId)
    }

    @Test
    fun `ChatChannel attributes defaults to null`() {
        val channel = ChatChannel(id = channelId, name = "Ch", type = ChatChannelType.DIRECT)
        assertNull(channel.attributes)
    }

    @Test
    fun `ChatChannel stores groupId when provided`() {
        val groupId = Uuid.random()
        val channel = ChatChannel(id = channelId, groupId = groupId, name = "Ch", type = ChatChannelType.GROUP)
        assertEquals(groupId, channel.groupId)
    }

    @Test
    fun `ChatChannel objectType and objectId default to null`() {
        val channel = ChatChannel(id = channelId, name = "Ch", type = ChatChannelType.GROUP)
        assertNull(channel.objectType)
        assertNull(channel.objectId)
    }

    @Test
    fun `ChatChannel stores objectType and objectId when provided`() {
        val objectId = Uuid.random()
        val channel = ChatChannel(
            id = channelId,
            name = "Discussion",
            type = ChatChannelType.GROUP,
            objectType = ChatObjectType.METADATA,
            objectId = objectId
        )
        assertEquals(ChatObjectType.METADATA, channel.objectType)
        assertEquals(objectId, channel.objectId)
    }
}
