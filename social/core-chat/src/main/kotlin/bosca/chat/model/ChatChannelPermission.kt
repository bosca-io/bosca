package bosca.chat.model

import bosca.db.annotation.ColumnName
import bosca.security.model.EntityPermission
import bosca.security.model.PermissionAction
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * Grants a specific permission action on a chat channel to a security group,
 * controlling who can view, post, manage, or administer the channel.
 */
@Serializable
data class ChatChannelPermission(
    @Contextual
    @ColumnName("channel_id")
    val channelId: UUID,
    @Contextual
    @ColumnName("group_id")
    override val groupId: UUID,
    override val action: PermissionAction
) : EntityPermission {

    @Transient
    override val entityId: UUID = channelId
}
