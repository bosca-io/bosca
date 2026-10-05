package bosca.gateway.model

import bosca.db.annotation.ColumnName
import bosca.security.model.EntityPermission
import bosca.security.model.PermissionAction
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.time.OffsetDateTime

@Serializable
data class GatewayPermission(
    @ColumnName("gateway_id")
    @Contextual
    val gatewayId: UUID,
    @ColumnName("group_id")
    @Contextual
    override val groupId: UUID,
    override val action: PermissionAction,
    @ColumnName("granted_by")
    @Contextual
    val grantedBy: UUID,
    @ColumnName("granted_at")
    @Contextual
    val grantedAt: OffsetDateTime = OffsetDateTime.now(),
) : EntityPermission {
    @Transient
    override val entityId: UUID = gatewayId
}
