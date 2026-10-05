package bosca.community.repository

import bosca.community.model.PrayerPermission
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.security.model.PermissionAction
import bosca.serialization.UUID

@Repository
interface PrayerPermissionRepository {

    @Query("insert into community.prayer_permissions (prayer_id, group_id, action) values (:prayerId, :groupId, :action) on conflict do nothing")
    suspend fun addPermission(prayerId: UUID, groupId: UUID, action: PermissionAction)

    @Query("delete from community.prayer_permissions where prayer_id = :prayerId and group_id = :groupId and action = (:action)::permission_action")
    suspend fun deletePermission(prayerId: UUID, groupId: UUID, action: PermissionAction)

    @Query("select * from community.prayer_permissions where prayer_id = :prayerId")
    suspend fun getPermissionsByPrayerId(prayerId: UUID): List<PrayerPermission>

    @Query("select * from community.prayer_permissions where prayer_id = any(:prayerIds)")
    suspend fun getPermissionsByPrayerIds(prayerIds: List<UUID>): List<PrayerPermission>
}
