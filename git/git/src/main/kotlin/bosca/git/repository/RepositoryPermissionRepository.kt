package bosca.git.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.git.model.RepositoryPermission
import bosca.security.model.PermissionAction
import bosca.serialization.UUID

/**
 * Database access layer for repository permission grants in
 * `git.repository_permissions`. Follows the same group-based grant
 * pattern as [MetadataPermission] and [OrganizationPermission].
 */
@Repository
interface RepositoryPermissionRepository {

    @Query("select * from git.repository_permissions where repository_id = :repositoryId")
    suspend fun findByRepository(repositoryId: UUID): List<RepositoryPermission>

    @Query("""
        select * from git.repository_permissions where repository_id = any(:repositoryIds)
    """)
    suspend fun findByRepositories(repositoryIds: List<UUID>): List<RepositoryPermission>

    @Query("""
        insert into git.repository_permissions (repository_id, group_id, action)
        values (:repositoryId, :groupId, :action::permission_action)
        on conflict (repository_id, group_id, action) do nothing
        returning *
    """)
    suspend fun grant(permission: RepositoryPermission): RepositoryPermission?

    @Query("""
        delete from git.repository_permissions
        where repository_id = :repositoryId and group_id = :groupId and action = :action::permission_action
    """)
    suspend fun revoke(repositoryId: UUID, groupId: UUID, action: PermissionAction)

    @Query("delete from git.repository_permissions where repository_id = :repositoryId")
    suspend fun deleteAll(repositoryId: UUID)
}
