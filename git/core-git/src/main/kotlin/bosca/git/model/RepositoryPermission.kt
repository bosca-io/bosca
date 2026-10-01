package bosca.git.model

import bosca.db.annotation.ColumnName
import bosca.security.model.EntityPermission
import bosca.security.model.PermissionAction
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * A single permission grant linking a repository to a security group with a
 * specific action. Follows the same pattern as [OrganizationPermission] and
 * [MetadataPermission] — permissions are always group-based, never per-profile.
 */
@Serializable
data class RepositoryPermission(
    @Contextual
    @ColumnName("repository_id")
    val repositoryId: UUID,
    @Contextual
    @ColumnName("group_id")
    override val groupId: UUID,
    override val action: PermissionAction
) : EntityPermission {

    override val entityId: UUID
        get() = repositoryId
}
