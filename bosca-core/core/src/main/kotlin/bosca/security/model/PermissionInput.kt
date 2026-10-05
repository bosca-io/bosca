package bosca.security.model

import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

@Serializable
data class PermissionInput(
    val action: PermissionAction,
    @Contextual
    val entityId: UUID,
    @Contextual
    val groupId: UUID
)