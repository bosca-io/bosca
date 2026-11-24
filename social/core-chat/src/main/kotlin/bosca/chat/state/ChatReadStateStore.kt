package bosca.chat.state

import bosca.chat.model.*
import bosca.serialization.UUID

/** Selected persistence for chat state. */
interface ChatReadStateStore {
    /** Records the last message sequence read by this profile in the channel. */
    suspend fun set(channelId: UUID, profileId: UUID, sequence: Long)
    /** Returns the last-read sequence or null when no read position exists. */
    suspend fun get(channelId: UUID, profileId: UUID): Long?
    /** Returns the existing read positions for the requested memberships. */
    suspend fun getAll(
        memberships: Collection<Pair<UUID, UUID>>,
    ): Map<Pair<UUID, UUID>, Long>
    /** Removes one membership read position. */
    suspend fun clear(channelId: UUID, profileId: UUID)
    /** Removes all stored state owned by a permanently deleted profile. */
    suspend fun clearProfile(profileId: UUID)
    /** Removes all stored state for a deleted channel. */
    suspend fun clearChannel(channelId: UUID)
    companion object {
        const val BUCKET = "chat-read-state"
    }
}
