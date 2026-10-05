package bosca.analytics.model

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class AnalyticsDashboardParameter(
    val parameter: String,
    val name: String,
    val description: String,
    val type: QueryParameterType,
    val arrayType: QueryParameterType?,
    @Contextual
    val defaultValue: JsonElement? = null,
    val required: Boolean = false
)
