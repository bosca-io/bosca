package bosca.forms.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.forms.model.FormSchemaPermission
import bosca.security.model.PermissionAction
import bosca.serialization.UUID

/**
 * Data access for form schema permission grants stored in
 * the form_schema_permissions table.
 */
@Repository
interface FormSchemaPermissionRepository {

    @Query("select * from form_schema_permissions where form_schema_id = :id")
    suspend fun getByFormSchemaId(id: UUID): List<FormSchemaPermission>

    @Query("select * from form_schema_permissions where form_schema_id = any(:ids)")
    suspend fun getByFormSchemaIds(ids: List<UUID>): List<FormSchemaPermission>

    @Query(
        """
        insert into form_schema_permissions (form_schema_id, group_id, action)
        values (:formSchemaId, :groupId, (:action)::permission_action)
        on conflict do nothing
        """
    )
    suspend fun add(formSchemaId: UUID, groupId: UUID, action: PermissionAction)

    @Query(
        """
        delete from form_schema_permissions
        where form_schema_id = :formSchemaId
          and group_id = :groupId
          and action = (:action)::permission_action
        """
    )
    suspend fun delete(formSchemaId: UUID, groupId: UUID, action: PermissionAction)
}
