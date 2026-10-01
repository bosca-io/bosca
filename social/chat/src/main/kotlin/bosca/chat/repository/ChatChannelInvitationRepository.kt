package bosca.chat.repository

import bosca.chat.model.ChatChannelInvitation
import bosca.chat.model.ChatChannelInvitationStatus
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID

@Repository
interface ChatChannelInvitationRepository {

    @Query("select * from chat.channel_invitations where id = :id")
    suspend fun getById(id: UUID): ChatChannelInvitation?

    @Query(
        """
        select * from chat.channel_invitations
        where invitee_profile_id = :profileId and status = 'pending'
        order by created desc offset :offset limit :limit
        """
    )
    suspend fun getIncoming(profileId: UUID, offset: Long, limit: Int): List<ChatChannelInvitation>

    @Query(
        """
        select * from chat.channel_invitations
        where inviter_profile_id = :profileId and status = 'pending'
        order by created desc offset :offset limit :limit
        """
    )
    suspend fun getOutgoing(profileId: UUID, offset: Long, limit: Int): List<ChatChannelInvitation>

    @Query(
        """
        insert into chat.channel_invitations
            (channel_id, inviter_profile_id, invitee_profile_id, role)
        values (:channelId, :inviterProfileId, :inviteeProfileId, :role)
        returning *
        """
    )
    suspend fun add(
        channelId: UUID,
        inviterProfileId: UUID,
        inviteeProfileId: UUID,
        role: String,
    ): ChatChannelInvitation

    @Query(
        """
        update chat.channel_invitations
        set status = :status::chat.channel_invitation_status,
            version = version + 1,
            modified = now()
        where id = :id and version = :version and status = 'pending'
        returning *
        """
    )
    suspend fun transition(
        id: UUID,
        version: Long,
        status: ChatChannelInvitationStatus,
    ): ChatChannelInvitation?

    /** Cancels every pending invitation sent by or addressed to a profile that has lost its identity. */
    @Query(
        """
        update chat.channel_invitations
        set status = 'cancelled',
            version = version + 1,
            modified = now()
        where status = 'pending'
          and (inviter_profile_id = :profileId or invitee_profile_id = :profileId)
        """,
        returnUpdateCount = true,
    )
    suspend fun cancelPendingByProfile(profileId: UUID): Int
}
