package bosca.profile.organization.model

import bosca.db.annotation.ColumnName
import bosca.security.model.EntityPermission
import bosca.security.model.PermissionAction
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

@Serializable
data class OrganizationPermission(
    @Contextual
    @ColumnName("organization_id")
    val organizationId: UUID,
    @Contextual
    @ColumnName("group_id")
    override val groupId: UUID,
    override val action: PermissionAction
) : EntityPermission {

    override val entityId: UUID
        get() = organizationId
}