package bosca.chat.service

import bosca.chat.events.ChatChannelInvitationSentEvent
import bosca.chat.events.dispatch
import bosca.chat.model.ChatChannelInvitation
import bosca.chat.model.ChatChannelInvitationStatus
import bosca.chat.model.ChatChannelRoles
import bosca.chat.model.ChatChannelType
import bosca.chat.repository.ChatChannelInvitationRepository
import bosca.db.transaction
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation

@ServiceImplementation
class ChatChannelInvitationServiceImpl(
    private val repository: ChatChannelInvitationRepository,
    private val chatService: ChatService,
) : ChatChannelInvitationService {

    override suspend fun getById(id: UUID): ChatChannelInvitation? = repository.getById(id)

    override suspend fun getIncoming(profileId: UUID, offset: Long, limit: Int): List<ChatChannelInvitation> {
        validatePage(offset, limit)
        return repository.getIncoming(profileId, offset, limit)
    }

    override suspend fun getOutgoing(profileId: UUID, offset: Long, limit: Int): List<ChatChannelInvitation> {
        validatePage(offset, limit)
        return repository.getOutgoing(profileId, offset, limit)
    }

    override suspend fun invite(
        channelId: UUID,
        inviterProfileId: UUID,
        inviteeProfileId: UUID,
    ): ChatChannelInvitation = transaction {
        require(inviterProfileId != inviteeProfileId) { "a profile cannot invite itself" }
        val channel = checkNotNull(chatService.getById(channelId)) { "chat channel not found" }
        require(channel.type != ChatChannelType.DIRECT) { "direct channel participants cannot be changed" }
        require(channel.objectType == null && channel.objectId == null) {
            "object channel membership is granted by opening an authorized object"
        }
        check(chatService.getMember(channelId, inviterProfileId) != null) {
            "inviter is not a channel member"
        }
        check(chatService.getMember(channelId, inviteeProfileId) == null) { "invitee is already a channel member" }
        check(chatService.canParticipate(inviteeProfileId)) {
            "invitee is not eligible to participate in chat"
        }
        val invitation = repository.add(
            channelId,
            inviterProfileId,
            inviteeProfileId,
            ChatChannelRoles.MEMBER,
        )
        ChatChannelInvitationSentEvent(
            invitation.id,
            invitation.channelId,
            invitation.inviterProfileId,
            invitation.inviteeProfileId,
            invitation.role,
        ).dispatch()
        invitation
    }

    override suspend fun accept(id: UUID, inviteeProfileId: UUID): ChatChannelInvitation = transaction {
        val invitation = pendingInvitation(id)
        check(invitation.inviteeProfileId == inviteeProfileId) { "invitation belongs to another profile" }
        val accepted = transition(invitation, ChatChannelInvitationStatus.ACCEPTED)
        if (chatService.getMember(accepted.channelId, accepted.inviteeProfileId) == null) {
            chatService.joinChannel(accepted.channelId, accepted.inviteeProfileId, ChatChannelRoles.MEMBER)
        }
        accepted
    }

    override suspend fun decline(id: UUID, inviteeProfileId: UUID): ChatChannelInvitation {
        val invitation = pendingInvitation(id)
        check(invitation.inviteeProfileId == inviteeProfileId) { "invitation belongs to another profile" }
        return transition(invitation, ChatChannelInvitationStatus.DECLINED)
    }

    override suspend fun cancel(id: UUID, inviterProfileId: UUID): ChatChannelInvitation {
        val invitation = pendingInvitation(id)
        check(invitation.inviterProfileId == inviterProfileId) { "invitation was sent by another profile" }
        return transition(invitation, ChatChannelInvitationStatus.CANCELLED)
    }

    private suspend fun pendingInvitation(id: UUID): ChatChannelInvitation {
        val invitation = repository.getById(id) ?: throw NoSuchElementException("chat channel invitation not found")
        check(invitation.status == ChatChannelInvitationStatus.PENDING) { "chat channel invitation is not pending" }
        return invitation
    }

    private suspend fun transition(
        invitation: ChatChannelInvitation,
        status: ChatChannelInvitationStatus,
    ): ChatChannelInvitation = repository.transition(invitation.id, invitation.version, status)
        ?: error("chat channel invitation was modified concurrently")

    private fun validatePage(offset: Long, limit: Int) {
        require(offset >= 0) { "offset must not be negative" }
        require(limit >= 0) { "limit must not be negative" }
        require(limit <= MAX_PAGE_SIZE) { "limit must not exceed $MAX_PAGE_SIZE" }
    }

    private companion object {
        const val MAX_PAGE_SIZE = 100
    }
}
