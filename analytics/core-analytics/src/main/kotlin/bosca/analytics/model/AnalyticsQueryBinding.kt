package bosca.analytics.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** One JDBC parameter as bound, in the prepared statement's one-based positional order. */
@Serializable
data class AnalyticsQueryBinding(
    val position: Int,
    val parameter: String,
    val type: QueryParameterType,
    val value: JsonElement,
)
