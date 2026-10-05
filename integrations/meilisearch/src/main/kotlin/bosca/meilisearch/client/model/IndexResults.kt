package bosca.meilisearch.client.model

import kotlinx.serialization.Serializable

/**
 * Paginated list of indexes returned by the Meilisearch indexes endpoint.
 */
@Serializable
data class IndexResults(
    val results: List<IndexInfo> = emptyList(),
    val total: Int = 0,
    val limit: Int = 20,
    val offset: Int = 0,
)
