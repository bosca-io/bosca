package bosca.analytics.model

import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class AnalyticsDashboardInput(
    @Contextual
    val id: UUID = UUID.NIL,
    val key: String,
    val name: String,
    val description: String,
    val configuration: JsonElement,
    val parameters: List<AnalyticsQueryParameterInput> = emptyList(),
    val visualizations: List<AnalyticsVisualizationInstanceInput>
)

@Serializable
data class AnalyticsVisualizationInstanceInput(
    @Contextual
    val visualizationId: UUID,
    @Contextual
    val configuration: JsonElement
)