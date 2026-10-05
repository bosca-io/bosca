package bosca.chat.state

import bosca.chat.model.PresenceUpdateEvent
import bosca.nats.NatsConnectionPool
import bosca.serialization.UUID
import io.nats.client.api.StorageType
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * KV-backed presence store. One key per profile: `<profileId>` →
 * status string ("online"/"away"/"offline"/etc.). Memory-backed with
 * a TTL so a client that crashes without sending an offline event
 * naturally falls out after the heartbeat window expires.
 *
 * Presence is a per-user (not per-channel) concept here — a user is
 * "online" globally and the channel-level subscription fans out the
 * change. That matches the existing [PresenceUpdateEvent] shape,
 * which already carries only `profileId` + `status` and not a
 * channel id.
 */
class ChatPresenceStore(
    nats: NatsConnectionPool,
) {
    private val accessor = NatsKeyValueAccessor(nats, BUCKET) {
        storageType(StorageType.Memory)
        ttl(PRESENCE_TTL)
        description("Chat presence status by profile (TTL'd)")
    }

    /** Updates [profileId]'s presence to [status]. TTL extends on every write. */
    suspend fun setPresence(profileId: UUID, status: String) {
        val kv = accessor.get()
        withContext(Dispatchers.IO) { kv.put(key(profileId), status.toByteArray(Charsets.UTF_8)) }
    }

    /** Removes the presence marker — equivalent to "offline now, don't wait for TTL". */
    suspend fun clearPresence(profileId: UUID) {
        val kv = accessor.get()
        withContext(Dispatchers.IO) { kv.delete(key(profileId)) }
    }

    /** Returns the cached status for [profileId], or null if no recent heartbeat. */
    suspend fun getPresence(profileId: UUID): PresenceUpdateEvent? {
        val kv = accessor.get()
        return withContext(Dispatchers.IO) {
            val entry = kv.get(key(profileId)) ?: return@withContext null
            val bytes = entry.value ?: return@withContext null
            PresenceUpdateEvent(profileId = profileId, status = String(bytes, Charsets.UTF_8))
        }
    }

    /** Bulk lookup. Used when rendering a member list on first connect. */
    suspend fun getPresences(profileIds: Collection<UUID>): Map<UUID, PresenceUpdateEvent> {
        if (profileIds.isEmpty()) return emptyMap()
        val kv = accessor.get()
        return withContext(Dispatchers.IO) {
            profileIds.mapNotNull { id ->
                val entry = kv.get(key(id)) ?: return@mapNotNull null
                val bytes = entry.value ?: return@mapNotNull null
                id to PresenceUpdateEvent(profileId = id, status = String(bytes, Charsets.UTF_8))
            }.toMap()
        }
    }

    private fun key(profileId: UUID): String = profileId.toString()

    companion object {
        const val BUCKET = "chat-presence"

        /**
         * Heartbeat window. A client should re-`setPresence` at least
         * once per [PRESENCE_TTL] to stay listed; missing one heartbeat
         * is fine, missing all of them within this window expires the
         * marker and the user appears offline.
         */
        val PRESENCE_TTL: Duration = EPHEMERAL_TTL
    }
}
