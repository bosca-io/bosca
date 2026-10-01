package bosca.meilisearch.client.model

import kotlinx.serialization.Serializable

/**
 * Paginated list of tasks returned by the Meilisearch tasks endpoint.
 */
@Serializable
data class TaskResults(
    val results: List<Task> = emptyList(),
    val total: Int = 0,
    val limit: Int = 20,
    val from: Int? = null,
    val next: Int? = null,
)
