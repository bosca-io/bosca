package bosca.chat.graphql

import bosca.chat.model.ChatChannel
import bosca.chat.model.ChatChannelInvitation
import bosca.chat.model.ChatChannelInvitationStatus
import bosca.chat.service.ChatService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.profile.model.Profile
import bosca.profile.profile.service.ProfileService
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID

/** Field resolvers for a durable chat-channel invitation. */
@TypeController(type = "ChatChannelInvitation")
class ChatChannelInvitationController(
    private val chatService: ChatService,
    private val profileService: ProfileService,
) : GraphQLController<ChatChannelInvitation> {

    @Field
    fun id(invitation: ChatChannelInvitation): UUID = invitation.id

    @Field
    suspend fun channel(invitation: ChatChannelInvitation): ChatChannel? = chatService.getById(invitation.channelId)

    @Field
    suspend fun inviter(invitation: ChatChannelInvitation): Profile = profileService.getById(invitation.inviterProfileId)

    @Field
    suspend fun invitee(invitation: ChatChannelInvitation): Profile = profileService.getById(invitation.inviteeProfileId)

    @Field
    fun role(invitation: ChatChannelInvitation): String = invitation.role

    @Field
    fun status(invitation: ChatChannelInvitation): ChatChannelInvitationStatus = invitation.status

    @Field
    fun created(invitation: ChatChannelInvitation): OffsetDateTime = invitation.created

    @Field
    fun modified(invitation: ChatChannelInvitation): OffsetDateTime = invitation.modified
}
