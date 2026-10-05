package bosca.community.model

import bosca.communications.model.MessageContent
import bosca.communications.model.MessageContentType
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class ChatMessageTest {

    private val senderId = Uuid.random()
    private val channelId = Uuid.random()
    private val profileId = Uuid.random()
    private val now = java.time.OffsetDateTime.now()

    // --- ChatMessage field preservation ---

    @Test
    fun `ChatMessage stores all explicit properties`() {
        val content = listOf(MessageContent(MessageContentType.TEXT, "hello"))
        val attrs = JsonPrimitive("attr")
        val reactions = listOf(MessageReaction("thumbsup", profileId))
        val msg = ChatMessage(
            sequence = 42L,
            timestamp = now,
            senderId = senderId,
            clientId = channelId,
            content = content,
            attributes = attrs,
            parentSequence = 10L,
            reactions = reactions,
            deleted = true
        )
        assertEquals(42L, msg.sequence)
        assertEquals(now, msg.timestamp)
        assertEquals(senderId, msg.senderId)
        assertEquals(channelId, msg.clientId)
        assertEquals(content, msg.content)
        assertEquals(attrs, msg.attributes)
        assertEquals(10L, msg.parentSequence)
        assertEquals(reactions, msg.reactions)
        assertTrue(msg.deleted)
    }

    // --- ChatMessage defaults ---

    @Test
    fun `ChatMessage clientId defaults to NIL`() {
        val msg = ChatMessage(
            sequence = 1L,
            timestamp = now,
            senderId = senderId,
            content = emptyList()
        )
        assertEquals(Uuid.NIL, msg.clientId)
    }

    @Test
    fun `ChatMessage attributes defaults to null`() {
        val msg = ChatMessage(sequence = 1L, timestamp = now, senderId = senderId, content = emptyList())
        assertNull(msg.attributes)
    }

    @Test
    fun `ChatMessage parentSequence defaults to null`() {
        val msg = ChatMessage(sequence = 1L, timestamp = now, senderId = senderId, content = emptyList())
        assertNull(msg.parentSequence)
    }

    @Test
    fun `ChatMessage reactions defaults to empty list`() {
        val msg = ChatMessage(sequence = 1L, timestamp = now, senderId = senderId, content = emptyList())
        assertEquals(emptyList(), msg.reactions)
    }

    @Test
    fun `ChatMessage deleted defaults to false`() {
        val msg = ChatMessage(sequence = 1L, timestamp = now, senderId = senderId, content = emptyList())
        assertFalse(msg.deleted)
    }

    // --- ChatMessage data class equality ---

    @Test
    fun `ChatMessage equals for identical values`() {
        val content = listOf(MessageContent(MessageContentType.TEXT, "hi"))
        val a = ChatMessage(sequence = 1L, timestamp = now, senderId = senderId, content = content)
        val b = ChatMessage(sequence = 1L, timestamp = now, senderId = senderId, content = content)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    // --- MessageReaction ---

    @Test
    fun `MessageReaction stores properties`() {
        val reaction = MessageReaction("heart", profileId)
        assertEquals("heart", reaction.emoji)
        assertEquals(profileId, reaction.profileId)
    }

    @Test
    fun `MessageReaction equality`() {
        val a = MessageReaction("fire", profileId)
        val b = MessageReaction("fire", profileId)
        assertEquals(a, b)
    }

    // --- ChatMessageEvent ---

    @Test
    fun `ChatMessageEvent stores channelId and message`() {
        val msg = ChatMessage(sequence = 1L, timestamp = now, senderId = senderId, content = emptyList())
        val event = ChatMessageEvent(channelId, msg)
        assertEquals(channelId, event.channelId)
        assertEquals(msg, event.message)
    }

    @Test
    fun `ChatMessageEvent equality`() {
        val msg = ChatMessage(sequence = 5L, timestamp = now, senderId = senderId, content = emptyList())
        val a = ChatMessageEvent(channelId, msg)
        val b = ChatMessageEvent(channelId, msg)
        assertEquals(a, b)
    }
}
