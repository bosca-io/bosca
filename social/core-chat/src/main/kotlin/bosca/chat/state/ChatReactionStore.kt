package bosca.chat.state

import bosca.chat.model.*
import bosca.serialization.UUID

/** Selected persistence for chat state. */
interface ChatReactionStore {
    /** Adds one emoji reaction once per message and profile; returns whether it was newly added. */
    suspend fun add(
        channelId: UUID,
        sequence: Long,
        profileId: UUID,
        reactionId: UUID,
        emoji: String,
    ): Boolean
    /** Removes the specified emoji reaction for a profile. */
    suspend fun remove(channelId: UUID, sequence: Long, profileId: UUID, emoji: String)
    /** Returns the reactions for one message. */
    suspend fun getForMessage(channelId: UUID, sequence: Long): List<MessageReaction>
    /** Returns reactions grouped by the requested message sequences. */
    suspend fun getForMessages(
        channelId: UUID,
        sequences: Collection<Long>,
    ): Map<Long, List<MessageReaction>>
    /** Removes all reactions for a deleted message. */
    suspend fun clearMessage(channelId: UUID, sequence: Long)
    /** Removes all stored state for a deleted channel. */
    suspend fun clearChannel(channelId: UUID)
    /** Removes all stored state owned by a permanently deleted profile. */
    suspend fun clearProfile(profileId: UUID)
    companion object {
        const val BUCKET = "chat-reactions"
    }
}
