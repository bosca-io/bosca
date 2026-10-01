package bosca.security.model

import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

@Serializable
data class Permission(
    @Contextual
    override val groupId: UUID,
    override val action: PermissionAction
) : EntityPermission {

    override val entityId: UUID get() = throw UnsupportedOperationException("Permission does not have an entity ID")
}