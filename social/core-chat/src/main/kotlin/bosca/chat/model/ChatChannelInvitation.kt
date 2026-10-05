package bosca.chat.model

import bosca.db.annotation.ColumnName
import bosca.db.annotation.DbMapper
import bosca.db.mapper.EnumMapper
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/** Lifecycle state of an invitation to join a chat channel. */
@DbMapper(ChatChannelInvitationStatusMapper::class)
@Serializable
enum class ChatChannelInvitationStatus {
    PENDING,
    ACCEPTED,
    DECLINED,
    CANCELLED,
}

/** Maps channel-invitation status values between PostgreSQL and Kotlin. */
object ChatChannelInvitationStatusMapper :
    EnumMapper<ChatChannelInvitationStatus>({ ChatChannelInvitationStatus.valueOf(it.uppercase()) })

/**
 * A durable invitation from [inviterProfileId] for [inviteeProfileId] to join [channelId].
 */
@Serializable
data class ChatChannelInvitation(
    @Contextual
    val id: UUID,
    @ColumnName("channel_id")
    @Contextual
    val channelId: UUID,
    @ColumnName("inviter_profile_id")
    @Contextual
    val inviterProfileId: UUID,
    @ColumnName("invitee_profile_id")
    @Contextual
    val inviteeProfileId: UUID,
    val role: String,
    val status: ChatChannelInvitationStatus,
    val version: Long,
    val created: OffsetDateTime,
    val modified: OffsetDateTime,
)
