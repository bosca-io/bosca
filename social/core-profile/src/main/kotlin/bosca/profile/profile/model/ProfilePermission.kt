package bosca.profile.profile.model

import bosca.security.model.EntityPermission
import bosca.security.model.PermissionAction
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

@Serializable
class ProfilePermission(
    @Contextual
    override val entityId: UUID,
    @Contextual
    override val groupId: UUID,
    override val action: PermissionAction
) : EntityPermission