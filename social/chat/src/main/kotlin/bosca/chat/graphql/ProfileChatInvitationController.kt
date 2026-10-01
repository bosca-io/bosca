package bosca.chat.graphql

import bosca.chat.model.ChatChannelInvitation
import bosca.chat.service.ChatChannelInvitationService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.profile.profile.graphql.ProfileChat
import bosca.security.service.AuthenticationContext

/** Resolves incoming and outgoing channel invitations for a profile's chat view. */
@TypeController(type = "ProfileChat")
class ProfileChatInvitationController(
    private val invitationService: ChatChannelInvitationService,
) : GraphQLController<ProfileChat> {

    @Field
    suspend fun incomingInvitations(
        authentication: AuthenticationContext,
        chat: ProfileChat,
        offset: Long?,
        limit: Int?,
    ): List<ChatChannelInvitation> {
        return invitationService.getIncoming(chat.profile.id, offset ?: 0, limit ?: 50)
    }

    @Field
    suspend fun outgoingInvitations(
        authentication: AuthenticationContext,
        chat: ProfileChat,
        offset: Long?,
        limit: Int?,
    ): List<ChatChannelInvitation> {
        return invitationService.getOutgoing(chat.profile.id, offset ?: 0, limit ?: 50)
    }
}
