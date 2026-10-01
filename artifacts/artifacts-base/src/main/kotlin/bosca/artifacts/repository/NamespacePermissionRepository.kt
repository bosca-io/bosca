package bosca.artifacts.repository

import bosca.artifacts.model.NamespacePermission
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.security.model.PermissionAction
import bosca.serialization.UUID

@Repository
interface NamespacePermissionRepository {

    @Query("select * from artifacts.namespace_permissions where namespace_id = :namespaceId")
    suspend fun findByNamespace(namespaceId: UUID): List<NamespacePermission>

    @Query("select * from artifacts.namespace_permissions where namespace_id = any(:namespaceIds)")
    suspend fun findByNamespaces(namespaceIds: List<UUID>): List<NamespacePermission>

    @Query("""
        insert into artifacts.namespace_permissions (namespace_id, group_id, action)
        values (:namespaceId, :groupId, :action::permission_action)
        on conflict (namespace_id, group_id, action) do nothing
        returning *
    """)
    suspend fun grant(permission: NamespacePermission): NamespacePermission?

    @Query("""
        delete from artifacts.namespace_permissions
        where namespace_id = :namespaceId and group_id = :groupId and action = :action::permission_action
    """)
    suspend fun revoke(namespaceId: UUID, groupId: UUID, action: PermissionAction)

    @Query("delete from artifacts.namespace_permissions where namespace_id = :namespaceId")
    suspend fun deleteAll(namespaceId: UUID)
}
