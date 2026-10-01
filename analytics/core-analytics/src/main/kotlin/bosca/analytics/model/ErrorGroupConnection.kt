package bosca.analytics.model

import kotlinx.serialization.Serializable

/**
 * A page of [ErrorGroup] aggregates plus the total count of groups
 * matching the same filter (across all pages). Mirrors the existing
 * connection-style result types used elsewhere in the analytics API.
 */
@Serializable
data class ErrorGroupConnection(
    val edges: List<ErrorGroup>,
    val total: Long,
)
