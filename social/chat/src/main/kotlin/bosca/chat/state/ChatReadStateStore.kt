package bosca.chat.state

import bosca.nats.NatsConnectionPool
import bosca.serialization.UUID
import io.nats.client.api.StorageType
import java.nio.ByteBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * KV-backed last-read pointer per (channel, profile). One key
 * `<channelId>.<profileId>` whose value is the 8-byte big-endian
 * sequence number of the last message the user read in that channel.
 *
 * File-backed because read state is durable user data — losing it
 * resets unread badges and that's noticeable. There's no TTL: the
 * value lives until the channel or membership is removed, at which
 * point the channel-cleanup path purges this bucket alongside the
 * reactions bucket.
 *
 * Replaces `chat.channel_members.last_read_at` /
 * `last_read_sequence`, which moved off the relational schema with
 * the rest of chat state.
 */
class ChatReadStateStore(
    nats: NatsConnectionPool,
) {
    private val accessor = NatsKeyValueAccessor(nats, BUCKET) {
        storageType(StorageType.File)
        description("Chat last-read sequence: <channelId>.<profileId> -> int64")
    }

    /** Records that [profileId] has read up through [sequence] in [channelId]. */
    suspend fun set(channelId: UUID, profileId: UUID, sequence: Long) {
        val kv = accessor.get()
        val payload = ByteBuffer.allocate(8).putLong(sequence).array()
        withContext(Dispatchers.IO) { kv.put(key(channelId, profileId), payload) }
    }

    /** Last-read sequence for one membership, or null if the user has never read. */
    suspend fun get(channelId: UUID, profileId: UUID): Long? {
        val kv = accessor.get()
        return withContext(Dispatchers.IO) {
            val entry = kv.get(key(channelId, profileId)) ?: return@withContext null
            val bytes = entry.value ?: return@withContext null
            if (bytes.size != 8) return@withContext null
            ByteBuffer.wrap(bytes).long
        }
    }

    /**
     * Bulk lookup of last-read pointers for a list of memberships.
     * Used when rendering the channel members panel so we can fetch
     * one batch instead of N round-trips.
     */
    suspend fun getAll(
        memberships: Collection<Pair<UUID, UUID>>,
    ): Map<Pair<UUID, UUID>, Long> {
        if (memberships.isEmpty()) return emptyMap()
        val kv = accessor.get()
        return withContext(Dispatchers.IO) {
            memberships.mapNotNull { (channelId, profileId) ->
                val entry = kv.get(key(channelId, profileId)) ?: return@mapNotNull null
                val bytes = entry.value ?: return@mapNotNull null
                if (bytes.size != 8) return@mapNotNull null
                (channelId to profileId) to ByteBuffer.wrap(bytes).long
            }.toMap()
        }
    }

    /** Drops the last-read marker — used when a user leaves a channel. */
    suspend fun clear(channelId: UUID, profileId: UUID) {
        val kv = accessor.get()
        withContext(Dispatchers.IO) { kv.delete(key(channelId, profileId)) }
    }

    /** Drops every last-read marker owned by a permanently deleted profile. */
    suspend fun clearProfile(profileId: UUID) {
        val kv = accessor.get()
        withContext(Dispatchers.IO) {
            kv.keys("*.$profileId").forEach(kv::delete)
        }
    }

    /** Drops every last-read marker for a deleted channel. */
    suspend fun clearChannel(channelId: UUID) {
        val kv = accessor.get()
        withContext(Dispatchers.IO) {
            kv.keys("$channelId.*").forEach(kv::delete)
        }
    }

    private fun key(channelId: UUID, profileId: UUID): String = "$channelId.$profileId"

    companion object {
        const val BUCKET = "chat-read-state"
    }
}
