package bosca.analytics.model

import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class AnalyticsVisualizationInstance(
    @Contextual
    val id: UUID,
    @Contextual
    val configuration: JsonElement,
    val visualization: AnalyticsVisualization
)
