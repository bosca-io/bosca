package bosca.pubsub

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Validates the [Message] data class used to wrap pub/sub payloads with their
 * originating channel name.
 */
class MessageTest {

    @Test
    fun `message stores channel and payload`() {
        val msg = Message(channel = "events.user", message = "hello")
        assertEquals("events.user", msg.channel)
        assertEquals("hello", msg.message)
    }

    @Test
    fun `message supports generic types`() {
        val msg = Message(channel = "events.count", message = 42)
        assertEquals(42, msg.message)
    }

    @Test
    fun `message supports complex payload types`() {
        data class Payload(val id: Int, val name: String)
        val payload = Payload(1, "test")
        val msg = Message(channel = "events.complex", message = payload)
        assertEquals(payload, msg.message)
    }

    @Test
    fun `equality is based on channel and message`() {
        val msg1 = Message(channel = "ch", message = "data")
        val msg2 = Message(channel = "ch", message = "data")
        assertEquals(msg1, msg2)
        assertEquals(msg1.hashCode(), msg2.hashCode())
    }

    @Test
    fun `different channels produce non-equal messages`() {
        val msg1 = Message(channel = "ch1", message = "data")
        val msg2 = Message(channel = "ch2", message = "data")
        assertNotEquals(msg1, msg2)
    }

    @Test
    fun `different payloads produce non-equal messages`() {
        val msg1 = Message(channel = "ch", message = "data1")
        val msg2 = Message(channel = "ch", message = "data2")
        assertNotEquals(msg1, msg2)
    }

    @Test
    fun `copy with modified channel`() {
        val original = Message(channel = "old", message = "payload")
        val copied = original.copy(channel = "new")
        assertEquals("new", copied.channel)
        assertEquals("payload", copied.message)
    }

    @Test
    fun `message with null payload`() {
        val msg = Message<String?>(channel = "ch", message = null)
        assertEquals(null, msg.message)
    }
}
