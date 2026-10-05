package bosca.chat.repository

import bosca.chat.model.ChatChannelPermission
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.security.model.PermissionAction
import bosca.serialization.UUID

/**
 * Provides data access operations for chat channel permission records,
 * linking security groups to permission actions on specific channels.
 */
@Repository
interface ChatChannelPermissionRepository {

    @Query(
        "insert into chat.channel_permissions (channel_id, group_id, action) " +
            "values (:channelId, :groupId, (:action)::permission_action) on conflict do nothing"
    )
    suspend fun addPermission(channelId: UUID, groupId: UUID, action: PermissionAction)

    @Query(
        "delete from chat.channel_permissions where group_id = :groupId " +
            "and channel_id = :channelId and action = (:action)::permission_action"
    )
    suspend fun deletePermission(channelId: UUID, groupId: UUID, action: PermissionAction)

    @Query("select channel_id, group_id, action from chat.channel_permissions where channel_id = :id")
    suspend fun getPermissionsByChannelId(id: UUID): List<ChatChannelPermission>

    @Query("select channel_id, group_id, action from chat.channel_permissions where channel_id = any(:ids)")
    suspend fun getPermissionsByChannelIds(ids: List<UUID>): List<ChatChannelPermission>
}
