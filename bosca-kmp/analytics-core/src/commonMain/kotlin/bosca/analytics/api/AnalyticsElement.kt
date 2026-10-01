package bosca.analytics.api

import kotlinx.serialization.Serializable

/** The application element an event describes. */
@Serializable
data class AnalyticsElement(
    val id: String,
    val type: String,
    val content: List<ContentElement> = emptyList(),
    val extras: Map<String, String> = emptyMap(),
)
