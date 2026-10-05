package bosca.content.collection.repository

import bosca.content.collection.model.CollectionPermission
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.security.model.PermissionAction
import bosca.serialization.UUID

@Repository
interface CollectionPermissionRepository {

    @Query("insert into collection_permissions (collection_id, group_id, action) values (:collectionId, :groupId, (:action)::permission_action) on conflict do nothing")
    suspend fun add(collectionId: UUID, groupId: UUID, action: PermissionAction)

    @Query("select * from collection_permissions where collection_id = :id")
    suspend fun getCollectionPermissionsByCollectionId(id: UUID): List<CollectionPermission>

    @Query("select * from collection_permissions where collection_id = any(:id)")
    suspend fun getCollectionPermissionsByCollectionIds(id: List<UUID>): List<CollectionPermission>

    @Query("delete from collection_permissions where collection_id = :id and group_id = :groupId and action = (:action)::permission_action")
    suspend fun deleteByCollectionId(id: UUID, groupId: UUID, action: PermissionAction)
}