package bosca.analytics.model

import bosca.db.annotation.ColumnName
import bosca.serialization.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class AnalyticsDashboardVisualization(
    val id: UUID,
    @ColumnName("dashboard_id")
    val dashboardId: UUID,
    @ColumnName("visualization_id")
    val visualizationId: UUID,
    val configuration: JsonElement
)
