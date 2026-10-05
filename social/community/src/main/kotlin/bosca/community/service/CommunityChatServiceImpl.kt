package bosca.community.service

import bosca.chat.model.ChatChannel
import bosca.chat.model.ChatChannelRoles
import bosca.chat.model.ChatChannelType
import bosca.chat.service.ChatService
import bosca.chat.events.ChatChannelCreatedEvent as SharedChatChannelCreatedEvent
import bosca.chat.events.dispatch as dispatchShared
import bosca.community.events.ChatChannelCreatedEvent
import bosca.community.events.ChatMessageSentEvent
import bosca.community.events.dispatch
import bosca.db.transaction
import bosca.di.ObjectProvider
import bosca.communications.model.MessageContent
import bosca.profile.relationship.service.ProfileRelationshipService
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import kotlinx.serialization.json.JsonElement

/**
 * Composes the shared [ChatService] with community-specific behavior. Dispatches
 * community events that trigger Buddy AI agent jobs (auto-join on channel create,
 * respond on message sent), enforces profile relationship requirements for DMs,
 * and configures membership when creating community group channels.
 */
@ServiceImplementation
class CommunityChatServiceImpl(
    private val chatService: ChatService,
    private val communityService: ObjectProvider<CommunityService>,
    private val profileRelationshipService: ProfileRelationshipService,
) : CommunityChatService {

    override suspend fun initiateDM(profileId1: UUID, profileId2: UUID): ChatChannel {
        val relationships = profileRelationshipService.getRelationships(profileId1)
        if (relationships.none { it.profileId1 == profileId2 || it.profileId2 == profileId2 }) {
            val relationships2 = profileRelationshipService.getRelationships(profileId2)
            if (relationships2.none { it.profileId1 == profileId1 || it.profileId2 == profileId1 }) {
                throw IllegalStateException("Profiles must have a relationship to initiate a DM")
            }
        }

        val channel = chatService.createDirectChannel(profileId1, profileId2)
        ChatChannelCreatedEvent(
            channelId = channel.id,
            groupId = null,
            name = "DM",
            type = ChatChannelType.DIRECT
        ).dispatch()
        return channel
    }

    override suspend fun createGroupChannel(
        groupId: UUID,
        name: String,
        type: ChatChannelType,
        attributes: JsonElement?,
        administratorProfileId: UUID,
    ): ChatChannel = transaction {
        val channel = chatService.createChannel(
            groupId = groupId,
            name = name,
            type = type,
            attributes = attributes,
            dispatchCreatedEvent = false,
            initialMemberProfileId = administratorProfileId,
            initialMemberRole = ChatChannelRoles.ADMIN,
        )
        communityService.get().getMembers(groupId)
            .asSequence()
            .map { it.profileId }
            .filterNot { it == administratorProfileId }
            .forEach { profileId ->
                if (chatService.canParticipate(profileId)) {
                    chatService.joinChannel(
                        channelId = channel.id,
                        profileId = profileId,
                        role = ChatChannelRoles.MEMBER,
                        notifyExistingMembers = false,
                    )
                }
            }
        SharedChatChannelCreatedEvent(
            channelId = channel.id,
            groupId = groupId,
            name = name,
            type = type,
        ).dispatchShared()
        ChatChannelCreatedEvent(
            channelId = channel.id,
            groupId = groupId,
            name = name,
            type = type
        ).dispatch()
        channel
    }

    override suspend fun sendGroupMessage(
        channelId: UUID,
        senderId: UUID,
        clientId: UUID,
        content: List<MessageContent>,
        attributes: JsonElement?
    ): Long {
        val result = chatService.sendMessage(channelId, senderId, clientId, content, attributes)
        if (!result.duplicate) {
            ChatMessageSentEvent(
                channelId = channelId,
                senderId = senderId,
                sequence = result.sequence,
                content = content,
                attributes = attributes
            ).dispatch()
        }
        return result.sequence
    }
}
