@file:OptIn(ExperimentalUuidApi::class)

package bosca.localization.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.localization.model.LocalizationProjectPermission
import bosca.security.model.PermissionAction
import bosca.serialization.UUID
import kotlin.uuid.ExperimentalUuidApi

/**
 * Data access for `localization.project_permissions`, which implements the per-project
 * ACL surface consumed by [bosca.localization.security.LocalizationProjectPermissionEvaluator].
 *
 * The `action` column is a plain `varchar` holding the lowercase
 * [PermissionAction] name; [bosca.security.model.PermissionActionMapper] handles the
 * case conversion on read and write.
 */
@Repository
interface LocalizationProjectPermissionRepository {

    @Query("insert into localization.project_permissions (project_id, group_id, action) values (:projectId, :groupId, :action) on conflict do nothing")
    suspend fun addPermission(projectId: UUID, groupId: UUID, action: PermissionAction)

    @Query("delete from localization.project_permissions where project_id = :projectId and group_id = :groupId and action = :action")
    suspend fun deletePermission(projectId: UUID, groupId: UUID, action: PermissionAction)

    @Query("delete from localization.project_permissions where project_id = :projectId")
    suspend fun deleteByProjectId(projectId: UUID)

    @Query("select * from localization.project_permissions where project_id = :projectId")
    suspend fun getByProjectId(projectId: UUID): List<LocalizationProjectPermission>

    @Query("select * from localization.project_permissions where project_id = any(:projectIds)")
    suspend fun getByProjectIds(projectIds: List<UUID>): List<LocalizationProjectPermission>
}
