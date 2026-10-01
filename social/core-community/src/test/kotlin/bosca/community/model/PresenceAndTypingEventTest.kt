package bosca.community.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.uuid.Uuid

class PresenceAndTypingEventTest {

    // --- PresenceUpdateEvent ---

    @Test
    fun `PresenceUpdateEvent stores profileId and status`() {
        val profileId = Uuid.random()
        val event = PresenceUpdateEvent(profileId = profileId, status = "online")
        assertEquals(profileId, event.profileId)
        assertEquals("online", event.status)
    }

    // --- UserTypingEvent ---

    @Test
    fun `UserTypingEvent stores channelId profileId and isTyping`() {
        val channelId = Uuid.random()
        val profileId = Uuid.random()
        val event = UserTypingEvent(channelId = channelId, profileId = profileId, isTyping = true)
        assertEquals(channelId, event.channelId)
        assertEquals(profileId, event.profileId)
        assertTrue(event.isTyping)
    }

    @Test
    fun `UserTypingEvent isTyping can be false`() {
        val event = UserTypingEvent(channelId = Uuid.random(), profileId = Uuid.random(), isTyping = false)
        assertFalse(event.isTyping)
    }
}
