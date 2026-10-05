package bosca.analytics.model

import bosca.db.annotation.ColumnName
import bosca.security.model.EntityPermission
import bosca.security.model.PermissionAction
import bosca.serialization.UUID
import kotlinx.serialization.Serializable

@Serializable
data class AnalyticsDashboardPermission(
    @ColumnName("dashboard_id")
    override val entityId: UUID,
    @ColumnName("group_id")
    override val groupId: UUID,
    override val action: PermissionAction
) : EntityPermission
