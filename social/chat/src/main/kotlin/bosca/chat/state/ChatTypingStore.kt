package bosca.chat.state

import bosca.chat.model.UserTypingEvent
import bosca.nats.NatsConnectionPool
import bosca.serialization.UUID
import io.nats.client.api.StorageType
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * KV-backed typing-indicator state. Each "currently typing" record is
 * one key `<channelId>.<profileId>` in a memory-backed bucket with a
 * short TTL so a quietly-disconnected client's stale typing marker
 * auto-expires without us having to track and reap it.
 *
 * The realtime fanout still happens via NATS pub/sub on
 * `bosca.chat.v1.channels.<id>.typing` for low latency; this store is
 * the durable read-side that lets a freshly-connected client list who
 * is currently typing without waiting for the next event.
 */
class ChatTypingStore(
    nats: NatsConnectionPool,
) {
    private val accessor = NatsKeyValueAccessor(nats, BUCKET) {
        storageType(StorageType.Memory)
        ttl(TYPING_TTL)
        description("Chat typing indicators (TTL'd): <channelId>.<profileId>")
    }

    /** Mark [profileId] as typing in [channelId]. The TTL refresh extends life. */
    suspend fun setTyping(channelId: UUID, profileId: UUID) {
        val kv = accessor.get()
        withContext(Dispatchers.IO) { kv.put(key(channelId, profileId), EMPTY_VALUE) }
    }

    /** Explicitly clear a user's typing marker before the TTL fires (e.g. on send). */
    suspend fun clearTyping(channelId: UUID, profileId: UUID) {
        val kv = accessor.get()
        withContext(Dispatchers.IO) { kv.delete(key(channelId, profileId)) }
    }

    /** Drops every typing marker owned by a permanently deleted profile. */
    suspend fun clearProfile(profileId: UUID) {
        val kv = accessor.get()
        withContext(Dispatchers.IO) {
            kv.keys("*.$profileId").forEach(kv::delete)
        }
    }

    /** Drops every typing marker for a deleted channel. */
    suspend fun clearChannel(channelId: UUID) {
        val kv = accessor.get()
        withContext(Dispatchers.IO) {
            kv.keys("$channelId.*").forEach(kv::delete)
        }
    }

    /**
     * Snapshot of who is currently typing in a channel. Convenience for
     * a freshly-connected GraphQL subscriber that wants to render the
     * indicator immediately rather than waiting for the next pub/sub
     * event from a peer.
     */
    suspend fun activeTypers(channelId: UUID): List<UserTypingEvent> {
        val kv = accessor.get()
        return withContext(Dispatchers.IO) {
            kv.keys("$channelId.>").asSequence()
                .mapNotNull { decodeTyper(channelId, it) }
                .toList()
        }
    }

    private fun key(channelId: UUID, profileId: UUID): String = "$channelId.$profileId"

    private fun decodeTyper(channelId: UUID, key: String): UserTypingEvent? {
        val rest = key.removePrefix("$channelId.")
        val profileId = runCatching { UUID.parse(rest) }.getOrNull() ?: return null
        return UserTypingEvent(channelId = channelId, profileId = profileId, isTyping = true)
    }

    companion object {
        const val BUCKET = "chat-typing"
        /**
         * How long a typing marker lives without a refresh. Matches the
         * 5-minute scale of the existing distributed-lock bucket so the
         * NATS server's KV configuration burdens don't surprise an
         * operator inspecting the streams.
         */
        val TYPING_TTL: Duration = EPHEMERAL_TTL
    }
}
