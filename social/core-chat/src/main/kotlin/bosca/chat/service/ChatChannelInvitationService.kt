package bosca.chat.service

import bosca.chat.model.ChatChannelInvitation
import bosca.serialization.UUID
import bosca.service.Service

/** Manages durable invitations to join chat channels. */
interface ChatChannelInvitationService : Service {

    /** Returns one invitation, or null when [id] does not exist. */
    suspend fun getById(id: UUID): ChatChannelInvitation?

    /** Returns pending invitations addressed to [profileId]. */
    suspend fun getIncoming(profileId: UUID, offset: Long = 0, limit: Int = 50): List<ChatChannelInvitation>

    /** Returns pending invitations sent by [profileId]. */
    suspend fun getOutgoing(profileId: UUID, offset: Long = 0, limit: Int = 50): List<ChatChannelInvitation>

    /**
     * Creates an invitation for [inviteeProfileId] to join [channelId] as a member.
     * [inviterProfileId] must be a current member of the channel.
     */
    suspend fun invite(
        channelId: UUID,
        inviterProfileId: UUID,
        inviteeProfileId: UUID,
    ): ChatChannelInvitation

    /** Accepts [id] as [inviteeProfileId] and adds that profile to the channel. */
    suspend fun accept(id: UUID, inviteeProfileId: UUID): ChatChannelInvitation

    /** Declines [id] as [inviteeProfileId]. */
    suspend fun decline(id: UUID, inviteeProfileId: UUID): ChatChannelInvitation

    /** Cancels [id] as its [inviterProfileId]. */
    suspend fun cancel(id: UUID, inviterProfileId: UUID): ChatChannelInvitation
}
