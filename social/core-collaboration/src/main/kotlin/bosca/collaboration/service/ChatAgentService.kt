package bosca.collaboration.service

import bosca.serialization.UUID
import bosca.service.Service

/**
 * Interface for invoking an AI agent within a chat channel context. Implementations
 * (e.g., backed by Kit) process a user's message with full channel conversation
 * history and optionally a slash command, posting the response back to the channel.
 */
interface ChatAgentService : Service {

    /**
     * Processes a message in a chat channel through the AI agent and posts the
     * response back to the same channel.
     *
     * @param channelId the channel where the interaction occurs
     * @param senderId the profile that triggered the agent
     * @param sequence the sequence number of the triggering message
     * @param slashCommand optional slash command (e.g., "summarize", "translate fr")
     */
    suspend fun processAndRespond(
        channelId: UUID,
        senderId: UUID,
        sequence: Long,
        slashCommand: String? = null
    )
}
