package bosca.chat.state

import bosca.chat.model.MessageReaction
import bosca.chat.model.UserTypingEvent
import bosca.nats.NatsConnectionPool
import bosca.serialization.UUID
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.nats.client.Connection
import io.nats.client.KeyValue
import io.nats.client.api.KeyValueEntry
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatFilteredStateStoreTest {

    @Test
    fun `reaction add reports whether it stored a new emoji`() = runTest {
        val channelId = UUID.random()
        val sequence = 42L
        val profileId = UUID.random()
        val reactionId = UUID.random()
        val key = "$channelId.$sequence.$profileId"
        val kv = mockk<KeyValue>()
        every { kv.get(key) } returns null
        every { kv.put(key, any<ByteArray>()) } returns 1L
        val store = ChatReactionStore(pool(ChatReactionStore.BUCKET, kv))

        assertTrue(store.add(channelId, sequence, profileId, reactionId, "👍"))

        val entry = mockk<KeyValueEntry>()
        every { entry.value } returns """[{"id":"$reactionId","emoji":"👍"}]""".toByteArray()
        every { kv.get(key) } returns entry
        assertFalse(store.add(channelId, sequence, profileId, UUID.random(), "👍"))
        verify(exactly = 1) { kv.put(key, any<ByteArray>()) }
    }

    @Test
    fun `reaction lookup filters by requested message subjects`() = runTest {
        val channelId = UUID.random()
        val sequence = 42L
        val profileId = UUID.random()
        val reactionId = UUID.random()
        val key = "$channelId.$sequence.$profileId"
        val kv = mockk<KeyValue>()
        val entry = mockk<KeyValueEntry>()
        every { entry.value } returns """[{"id":"$reactionId","emoji":"👍"}]""".toByteArray()
        every { kv.keys(listOf("$channelId.$sequence.>")) } returns listOf(key)
        every { kv.get(key) } returns entry
        val store = ChatReactionStore(pool(ChatReactionStore.BUCKET, kv))

        val result = store.getForMessages(channelId, listOf(sequence))

        assertEquals(listOf(MessageReaction("👍", profileId, reactionId)), result[sequence])
        verify(exactly = 0) { kv.keys() }
    }

    @Test
    fun `reaction lookup assigns stable ids to legacy emoji values`() = runTest {
        val channelId = UUID.random()
        val sequence = 42L
        val profileId = UUID.random()
        val key = "$channelId.$sequence.$profileId"
        val kv = mockk<KeyValue>()
        val entry = mockk<KeyValueEntry>()
        every { entry.value } returns "[\"👍\"]".toByteArray()
        every { kv.keys(listOf("$channelId.$sequence.>")) } returns listOf(key)
        every { kv.get(key) } returns entry
        val store = ChatReactionStore(pool(ChatReactionStore.BUCKET, kv))

        val first = store.getForMessage(channelId, sequence).single()
        val second = store.getForMessage(channelId, sequence).single()

        assertEquals("👍", first.emoji)
        assertEquals(profileId, first.profileId)
        assertEquals(first.id, second.id)
        assertFalse(first.id == UUID.NIL)
    }

    @Test
    fun `typing lookup filters by channel subject`() = runTest {
        val channelId = UUID.random()
        val profileId = UUID.random()
        val kv = mockk<KeyValue>()
        every { kv.keys("$channelId.>") } returns listOf("$channelId.$profileId")
        val store = ChatTypingStore(pool(ChatTypingStore.BUCKET, kv))

        assertEquals(
            listOf(UserTypingEvent(channelId, profileId, true)),
            store.activeTypers(channelId),
        )
        verify(exactly = 0) { kv.keys() }
    }

    @Test
    fun `read state cleanup filters every channel by profile`() = runTest {
        val profileId = UUID.random()
        val keys = listOf("${UUID.random()}.$profileId", "${UUID.random()}.$profileId")
        val kv = mockk<KeyValue>()
        every { kv.keys("*.$profileId") } returns keys
        every { kv.delete(any()) } returns Unit
        val store = ChatReadStateStore(pool(ChatReadStateStore.BUCKET, kv))

        store.clearProfile(profileId)

        keys.forEach { verify(exactly = 1) { kv.delete(it) } }
        verify(exactly = 0) { kv.keys() }
    }

    @Test
    fun `typing cleanup filters every channel by profile`() = runTest {
        val profileId = UUID.random()
        val keys = listOf("${UUID.random()}.$profileId", "${UUID.random()}.$profileId")
        val kv = mockk<KeyValue>()
        every { kv.keys("*.$profileId") } returns keys
        every { kv.delete(any()) } returns Unit
        val store = ChatTypingStore(pool(ChatTypingStore.BUCKET, kv))

        store.clearProfile(profileId)

        keys.forEach { verify(exactly = 1) { kv.delete(it) } }
        verify(exactly = 0) { kv.keys() }
    }

    private fun pool(bucket: String, kv: KeyValue): NatsConnectionPool {
        val connection = mockk<Connection>()
        every { connection.keyValue(bucket) } returns kv
        return NatsConnectionPool(connection)
    }
}
