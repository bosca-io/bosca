package bosca.chat.state

import bosca.chat.model.*
import bosca.serialization.UUID

/** Selected persistence for chat state. */
interface ChatTypingStore {
    /** Refreshes the profile typing marker until its expiration. */
    suspend fun setTyping(channelId: UUID, profileId: UUID)
    /** Removes a typing marker immediately. */
    suspend fun clearTyping(channelId: UUID, profileId: UUID)
    /** Removes all stored state owned by a permanently deleted profile. */
    suspend fun clearProfile(profileId: UUID)
    /** Removes all stored state for a deleted channel. */
    suspend fun clearChannel(channelId: UUID)
    /** Returns typing markers that have not expired. */
    suspend fun activeTypers(channelId: UUID): List<UserTypingEvent>
    companion object {
        const val BUCKET = "chat-typing"
        val TYPING_TTL: java.time.Duration = java.time.Duration.ofMinutes(5)
    }
}
