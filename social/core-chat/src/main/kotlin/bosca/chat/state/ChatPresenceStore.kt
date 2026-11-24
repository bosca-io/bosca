package bosca.chat.state

import bosca.chat.model.*
import bosca.serialization.UUID

/** Selected persistence for chat state. */
interface ChatPresenceStore {
    /** Sets the profile presence and refreshes its expiration. */
    suspend fun setPresence(profileId: UUID, status: String)
    /** Removes the profile presence immediately. */
    suspend fun clearPresence(profileId: UUID)
    /** Returns the current presence, or null when it is absent or expired. */
    suspend fun getPresence(profileId: UUID): PresenceUpdateEvent?
    /** Returns current presence for the requested profiles, omitting absent or expired entries. */
    suspend fun getPresences(profileIds: Collection<UUID>): Map<UUID, PresenceUpdateEvent>
    companion object {
        const val BUCKET = "chat-presence"
        val PRESENCE_TTL: java.time.Duration = java.time.Duration.ofMinutes(5)
    }
}
