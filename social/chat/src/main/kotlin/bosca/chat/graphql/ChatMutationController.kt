package bosca.chat.graphql

import bosca.chat.model.ChatChannel
import bosca.chat.model.ChatChannelInvitation
import bosca.chat.model.ChatChannelRoles
import bosca.chat.model.ChatChannelType
import bosca.chat.model.ChatObjectType
import bosca.chat.security.ChatChannelPermissionEvaluator
import bosca.chat.security.ChatObjectPermissionEvaluator
import bosca.chat.service.ChatService
import bosca.chat.service.ChatChannelInvitationService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.communications.model.MessageContent
import bosca.communications.model.MessageContentType
import bosca.content.collection.service.CollectionService
import bosca.content.metadata.service.MetadataService
import bosca.content.security.CollectionPermissionEvaluator
import bosca.content.security.MetadataPermissionEvaluator
import bosca.profile.model.Profile
import bosca.profile.profile.service.ProfileService
import bosca.profile.security.ProfilePermissionEvaluator
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.security.service.SecurityException
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI

object ChatMutation

@TypeController
class ChatMutationController(
    private val chatService: ChatService,
    private val chatChannelPermissionEvaluator: ChatChannelPermissionEvaluator,
    private val profileService: ProfileService,
    private val profilePermissionEvaluator: ProfilePermissionEvaluator,
    private val groupEvaluator: GroupEvaluator,
    private val invitationService: ChatChannelInvitationService,
    private val metadataService: MetadataService,
    private val metadataPermissionEvaluator: MetadataPermissionEvaluator,
    private val collectionService: CollectionService,
    private val collectionPermissionEvaluator: CollectionPermissionEvaluator,
    private val chatObjectPermissionEvaluator: ChatObjectPermissionEvaluator,
) : GraphQLController<ChatMutation> {

    @Field
    suspend fun createChannel(
        authentication: AuthenticationContext,
        name: String,
        type: ChatChannelType,
        attributes: JsonElement?
    ): ChatChannel {
        val profile = authenticatedChatProfile(authentication)
        return chatService.createChannel(
            groupId = null,
            name = name,
            type = type,
            attributes = attributes,
            initialMemberProfileId = profile.id,
            initialMemberRole = ChatChannelRoles.ADMIN,
        )
    }

    @Field
    suspend fun objectChannel(
        authentication: AuthenticationContext,
        objectType: ChatObjectType,
        objectId: UUID,
        name: String
    ): ChatChannel {
        val profile = authenticatedChatProfile(authentication)
        chatObjectPermissionEvaluator.verifyAllowed(authentication, objectType, objectId)
        val channel = chatService.getOrCreateObjectChannel(objectType, objectId, name)
        // Object channels behave like Linear/Notion comment threads: anyone
        // who opens the linked object should land in the channel as a member
        // without needing a separate join step. Existing
        // memberships are preserved so opening the object cannot change a role.
        chatService.joinChannel(channel.id, profile.id, ChatChannelRoles.MEMBER)
        return channel
    }

    @Field
    suspend fun leaveChannel(
        authentication: AuthenticationContext,
        channelId: UUID,
    ): Boolean {
        val profile = authenticatedChatProfile(authentication)
        chatService.leaveChannel(channelId, profile.id)
        return true
    }

    @Field
    suspend fun setChannelMemberRole(
        authentication: AuthenticationContext,
        channelId: UUID,
        profileId: UUID,
        role: String,
    ): Boolean {
        authenticatedChatProfile(authentication)
        val channel = chatService.getById(channelId) ?: return false
        chatObjectPermissionEvaluator.verifyAllowed(authentication, channel)
        chatChannelPermissionEvaluator.verifyAllowed(authentication, channel, PermissionAction.MANAGE)
        chatService.setMemberRole(channelId, profileId, role)
        return true
    }

    @Field
    suspend fun removeChannelMember(
        authentication: AuthenticationContext,
        channelId: UUID,
        profileId: UUID,
    ): Boolean {
        authenticatedChatProfile(authentication)
        val channel = chatService.getById(channelId) ?: return false
        chatObjectPermissionEvaluator.verifyAllowed(authentication, channel)
        chatChannelPermissionEvaluator.verifyAllowed(authentication, channel, PermissionAction.MANAGE)
        chatService.removeMember(channelId, profileId)
        return true
    }

    @Field
    suspend fun inviteToChannel(
        authentication: AuthenticationContext,
        channelId: UUID,
        inviteeProfileId: UUID,
    ): ChatChannelInvitation {
        val inviter = authenticatedChatProfile(authentication)
        val invitee = profileService.getById(inviteeProfileId)
        profilePermissionEvaluator.verifyAllowed(authentication, invitee, PermissionAction.VIEW)
        val channel = chatService.getById(channelId) ?: throw NoSuchElementException("chat channel not found")
        chatObjectPermissionEvaluator.verifyAllowed(authentication, channel)
        chatChannelPermissionEvaluator.verifyAllowed(authentication, channel, PermissionAction.MANAGE)
        verifyChannelMembership(channel.id, inviter.id)
        return invitationService.invite(channelId, inviter.id, inviteeProfileId)
    }

    @Field
    suspend fun acceptChannelInvitation(
        authentication: AuthenticationContext,
        invitationId: UUID,
    ): ChatChannelInvitation {
        val profile = authenticatedChatProfile(authentication)
        val invitation = invitationService.getById(invitationId)
            ?: throw NoSuchElementException("chat channel invitation not found")
        val channel = chatService.getById(invitation.channelId)
            ?: throw NoSuchElementException("chat channel not found")
        chatObjectPermissionEvaluator.verifyAllowed(authentication, channel)
        return invitationService.accept(invitationId, profile.id)
    }

    @Field
    suspend fun declineChannelInvitation(
        authentication: AuthenticationContext,
        invitationId: UUID,
    ): ChatChannelInvitation {
        val profile = authenticatedChatProfile(authentication)
        return invitationService.decline(invitationId, profile.id)
    }

    @Field
    suspend fun cancelChannelInvitation(
        authentication: AuthenticationContext,
        invitationId: UUID,
    ): ChatChannelInvitation {
        val profile = authenticatedChatProfile(authentication)
        return invitationService.cancel(invitationId, profile.id)
    }

    @Field
    suspend fun initiateDM(
        authentication: AuthenticationContext,
        profileId: UUID
    ): ChatChannel {
        val profile1 = authenticatedChatProfile(authentication)
        val profile2 = profileService.getById(profileId)
        profilePermissionEvaluator.verifyAllowed(authentication, profile2, PermissionAction.VIEW)
        return chatService.createDirectChannel(profile1.id, profileId)
    }

    @Field
    suspend fun sendMessage(
        authentication: AuthenticationContext,
        channelId: UUID,
        clientId: UUID,
        content: List<MessageContent>,
        attributes: JsonElement?,
        parentSequence: Long?,
    ): Boolean {
        val senderProfile = authenticatedChatProfile(authentication)
        val channel = chatService.getById(channelId) ?: return false
        chatObjectPermissionEvaluator.verifyAllowed(authentication, channel)
        chatChannelPermissionEvaluator.verifyAllowed(authentication, channel, PermissionAction.EXECUTE)
        verifyChannelMembership(channel.id, senderProfile.id)
        verifyAttachmentsAllowed(authentication, content)
        chatService.sendMessage(channelId, senderProfile.id, clientId, content, attributes, parentSequence)
        return true
    }

    @Field
    suspend fun deleteMessage(
        authentication: AuthenticationContext,
        channelId: UUID,
        sequence: Long,
    ): Boolean {
        val profile = authenticatedChatProfile(authentication)
        val channel = chatService.getById(channelId) ?: return false
        chatObjectPermissionEvaluator.verifyAllowed(authentication, channel)
        chatChannelPermissionEvaluator.verifyAllowed(authentication, channel, PermissionAction.EXECUTE)
        verifyChannelMembership(channel.id, profile.id)
        val message = chatService.getMessage(channelId, sequence) ?: return false
        if (message.senderId != profile.id) {
            throw SecurityException("Chat messages can only be deleted by their sender")
        }
        return chatService.deleteMessage(channelId, sequence)
    }

    private suspend fun verifyAttachmentsAllowed(
        authentication: AuthenticationContext,
        content: List<MessageContent>,
    ) {
        for (item in content) {
            when (item.type) {
                MessageContentType.COLLECTION -> verifyCollectionAllowed(
                    authentication,
                    requiredAttachmentId(item.content),
                )
                MessageContentType.METADATA -> verifyMetadataReferenceAllowed(authentication, item.content)
                MessageContentType.IMAGE,
                MessageContentType.VIDEO,
                MessageContentType.AUDIO,
                MessageContentType.FILE -> metadataId(item)?.let { verifyMetadataAllowed(authentication, it) }
                else -> Unit
            }
        }
    }

    private suspend fun verifyMetadataReferenceAllowed(
        authentication: AuthenticationContext,
        content: String,
    ) {
        runCatching { UUID.parse(content) }.getOrNull()?.let {
            verifyMetadataAllowed(authentication, it)
            return
        }
        val reference = runCatching { Json.parseToJsonElement(content).jsonObject }
            .getOrElse { throw IllegalArgumentException("invalid chat attachment", it) }
        val id = reference["id"]?.jsonPrimitive?.contentOrNull
            ?.let { runCatching { UUID.parse(it) }.getOrNull() }
            ?: throw IllegalArgumentException("chat attachment is missing a valid id")
        when (reference["type"]?.jsonPrimitive?.contentOrNull?.lowercase()) {
            "collection" -> verifyCollectionAllowed(authentication, id)
            "metadata", "image", "video", "audio", "file" -> verifyMetadataAllowed(authentication, id)
            else -> throw IllegalArgumentException("unsupported chat attachment type")
        }
    }

    private suspend fun verifyMetadataAllowed(authentication: AuthenticationContext, id: UUID) {
        val metadata = metadataService.getById(id)
            ?: throw NoSuchElementException("metadata attachment not found")
        metadataPermissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.VIEW)
    }

    private suspend fun verifyCollectionAllowed(authentication: AuthenticationContext, id: UUID) {
        val collection = collectionService.getById(id)
            ?: throw NoSuchElementException("collection attachment not found")
        collectionPermissionEvaluator.verifyAllowed(authentication, collection, PermissionAction.VIEW)
    }

    private fun requiredAttachmentId(content: String): UUID =
        runCatching { UUID.parse(content) }.getOrNull()
            ?: throw IllegalArgumentException("chat attachment is missing a valid id")

    private fun metadataId(content: MessageContent): UUID? {
        runCatching { UUID.parse(content.content) }.getOrNull()?.let { return it }
        if (content.type != MessageContentType.IMAGE) return null
        val path = runCatching { URI(content.content).path }.getOrNull() ?: return null
        val id = path.substringAfterLast("/content/image/", missingDelimiterValue = "")
            .substringBefore('.')
        return runCatching { UUID.parse(id) }.getOrNull()
    }

    @Field
    suspend fun updateLastRead(
        authentication: AuthenticationContext,
        channelId: UUID,
        sequence: Long
    ): Boolean {
        val profile = authenticatedChatProfile(authentication)
        val channel = chatService.getById(channelId) ?: return false
        chatObjectPermissionEvaluator.verifyAllowed(authentication, channel)
        chatChannelPermissionEvaluator.verifyAllowed(authentication, channel, PermissionAction.VIEW)
        verifyChannelMembership(channel.id, profile.id)
        chatService.updateLastRead(channelId, profile.id, sequence)
        return true
    }

    @Field
    suspend fun sendTyping(
        authentication: AuthenticationContext,
        channelId: UUID,
        isTyping: Boolean
    ): Boolean {
        val profile = authenticatedChatProfile(authentication)
        val channel = chatService.getById(channelId) ?: return false
        chatObjectPermissionEvaluator.verifyAllowed(authentication, channel)
        chatChannelPermissionEvaluator.verifyAllowed(authentication, channel, PermissionAction.VIEW)
        verifyChannelMembership(channel.id, profile.id)
        chatService.sendTyping(channelId, profile.id, isTyping)
        return true
    }

    @Field
    suspend fun addReaction(
        authentication: AuthenticationContext,
        channelId: UUID,
        sequence: Long,
        emoji: String,
    ): Boolean {
        val profile = authenticatedChatProfile(authentication)
        val channel = chatService.getById(channelId) ?: return false
        chatObjectPermissionEvaluator.verifyAllowed(authentication, channel)
        chatChannelPermissionEvaluator.verifyAllowed(authentication, channel, PermissionAction.EXECUTE)
        verifyChannelMembership(channel.id, profile.id)
        chatService.addReaction(channelId, sequence, profile.id, emoji)
        return true
    }

    @Field
    suspend fun removeReaction(
        authentication: AuthenticationContext,
        channelId: UUID,
        sequence: Long,
        emoji: String,
    ): Boolean {
        val profile = authenticatedChatProfile(authentication)
        val channel = chatService.getById(channelId) ?: return false
        chatObjectPermissionEvaluator.verifyAllowed(authentication, channel)
        chatChannelPermissionEvaluator.verifyAllowed(authentication, channel, PermissionAction.EXECUTE)
        verifyChannelMembership(channel.id, profile.id)
        chatService.removeReaction(channelId, sequence, profile.id, emoji)
        return true
    }

    private suspend fun verifyChannelMembership(channelId: UUID, profileId: UUID) {
        if (chatService.getMember(channelId, profileId) == null) {
            throw SecurityException("An active channel membership is required")
        }
    }

    private suspend fun authenticatedChatProfile(authentication: AuthenticationContext): Profile {
        groupEvaluator.verifyHasMessagingAccess(authentication)
        val principal = authentication.principal()?.asPrincipal()
            ?: throw SecurityException("An authenticated chat profile is required")
        return profileService.getPrimaryProfile(principal)
            ?: throw SecurityException("An active primary chat profile is required")
    }
}
