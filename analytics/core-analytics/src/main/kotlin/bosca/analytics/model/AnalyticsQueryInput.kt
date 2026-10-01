package bosca.analytics.model

import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class AnalyticsQueryInput(
    @Contextual
    val id: UUID = UUID.NIL,
    val key: String,
    val name: String,
    val description: String,
    val query: String,
    val parameters: List<AnalyticsQueryParameterInput> = emptyList(),
    val configuration: JsonElement? = null,
    /**
     * When set, cached query results become eligible for background refresh after
     * [refreshIntervalSeconds] seconds; actual timing also depends on the configured
     * sweep cadence. Values must be at least 60; when `null`, results are never cached.
     */
    val refreshIntervalSeconds: Int? = null,
)
