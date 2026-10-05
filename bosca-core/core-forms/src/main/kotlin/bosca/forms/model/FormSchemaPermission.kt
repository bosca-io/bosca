package bosca.forms.model

import bosca.db.annotation.ColumnName
import bosca.security.model.EntityPermission
import bosca.security.model.PermissionAction
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * A single permission grant linking a form schema to a security group
 * with a specific action (view, edit, delete, manage).
 */
@Serializable
data class FormSchemaPermission(
    @Contextual
    @ColumnName("form_schema_id")
    val formSchemaId: UUID,
    @Contextual
    @ColumnName("group_id")
    override val groupId: UUID,
    override val action: PermissionAction,
) : EntityPermission {

    override val entityId: UUID
        get() = formSchemaId
}
