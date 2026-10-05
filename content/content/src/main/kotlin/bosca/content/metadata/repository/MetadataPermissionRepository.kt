package bosca.content.metadata.repository

import bosca.content.metadata.model.MetadataPermission
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.security.model.PermissionAction
import bosca.serialization.UUID

@Repository
interface MetadataPermissionRepository {

    @Query("insert into metadata_permissions (group_id, metadata_id, action) values (:groupId, :metadataId, (:action)::permission_action) on conflict do nothing")
    suspend fun addPermission(metadataId: UUID, groupId: UUID, action: PermissionAction)

    @Query("delete from metadata_permissions where group_id = :groupId and metadata_id = :metadataId and action = (:action)::permission_action")
    suspend fun deletePermission(metadataId: UUID, groupId: UUID, action: PermissionAction)

    @Query("select * from metadata_permissions where metadata_id = :id")
    suspend fun getMetadataPermissionsByMetadataId(id: UUID): List<MetadataPermission>

    @Query("select * from metadata_permissions where metadata_id = any(:id)")
    suspend fun getMetadataPermissionsByMetadataIds(id: List<UUID>): List<MetadataPermission>
}