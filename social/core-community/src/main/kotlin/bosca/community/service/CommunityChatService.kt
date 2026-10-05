package bosca.community.service

import bosca.chat.model.ChatChannel
import bosca.chat.model.ChatChannelType
import bosca.communications.model.MessageContent
import bosca.serialization.UUID
import bosca.service.Service
import kotlinx.serialization.json.JsonElement

/**
 * Community-shaped chat operations layered on top of the shared chat infrastructure.
 *
 * This is a distinct service from [bosca.chat.service.ChatService]. It composes
 * the shared chat service with community-specific behavior: relationship checks
 * for direct messages, automatic member/permission setup for community group
 * channels, and dispatching of community-specific events (e.g., to trigger Buddy
 * AI agent responses on community-group messages only).
 *
 * Code that operates on community group channels should use this service.
 * Code that operates on standalone or object-scoped channels should use the
 * shared [bosca.chat.service.ChatService] directly to avoid coupling to
 * community-specific behavior.
 */
interface CommunityChatService : Service {

    /**
     * Creates or returns a direct message channel between two profiles, requiring
     * an existing profile relationship. Dispatches a community channel-created
     * event so community-specific listeners (e.g., Buddy auto-join) can react.
     */
    suspend fun initiateDM(profileId1: UUID, profileId2: UUID): ChatChannel

    /**
     * Creates a chat channel scoped to a community group, makes [administratorProfileId] its first
     * administrator, and adds all chat-eligible current community members to the channel.
     * Dispatches a community channel-created event for downstream community listeners.
     */
    suspend fun createGroupChannel(
        groupId: UUID,
        name: String,
        type: ChatChannelType,
        attributes: JsonElement?,
        administratorProfileId: UUID,
    ): ChatChannel

    /**
     * Sends a message to a community-group channel and dispatches a community
     * message-sent event to trigger community-specific behavior (Buddy responses,
     * activity logging, etc.). The actual message persistence is handled by the
     * shared chat service.
     */
    suspend fun sendGroupMessage(
        channelId: UUID,
        senderId: UUID,
        clientId: UUID,
        content: List<MessageContent>,
        attributes: JsonElement? = null
    ): Long
}
