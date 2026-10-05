package bosca.chat.model

import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class ChatChannelMemberTest {

    private val channelId = Uuid.random()
    private val profileId = Uuid.random()

    @Test
    fun `ChatChannelMember stores all explicit properties`() {
        // The model intentionally drops lastReadAt / lastReadSequence —
        // those moved to the chat-read-state NATS KV bucket. The row is
        // now strictly identity + role + attributes.
        val attrs = JsonPrimitive("custom")
        val member = ChatChannelMember(
            channelId = channelId,
            profileId = profileId,
            role = "admin",
            attributes = attrs,
        )
        assertEquals(channelId, member.channelId)
        assertEquals(profileId, member.profileId)
        assertEquals("admin", member.role)
        assertEquals(attrs, member.attributes)
    }

    @Test
    fun `ChatChannelMember attributes defaults to null`() {
        val member = ChatChannelMember(channelId = channelId, profileId = profileId, role = "member")
        assertNull(member.attributes)
    }

    @Test
    fun `ChatChannelMember equality`() {
        val a = ChatChannelMember(channelId, profileId, "member")
        val b = ChatChannelMember(channelId, profileId, "member")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }
}
