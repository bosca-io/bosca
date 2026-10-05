package bosca.analytics.model

import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class AnalyticsVisualizationInput(
    @Contextual
    val id: UUID = UUID.NIL,
    val key: String,
    val name: String,
    val description: String,
    @Contextual
    val queryId: UUID? = null,
    val type: AnalyticsVisualizationType,
    val configuration: JsonElement
)
