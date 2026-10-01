package bosca.analytics.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
class AnalyticsQueryExecutionParameterInput(
    val parameter: String,
    val value: JsonElement
)
