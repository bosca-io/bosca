package bosca.community.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

class MessageReactionTest {

    @Test
    fun `MessageReaction stores emoji and profileId`() {
        val profileId = Uuid.random()
        val reaction = MessageReaction(emoji = "thumbsup", profileId = profileId)
        assertEquals("thumbsup", reaction.emoji)
        assertEquals(profileId, reaction.profileId)
    }
}
