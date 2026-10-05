package bosca.meilisearch.client.model

import kotlinx.serialization.Serializable

/**
 * Statistics for a single Meilisearch index including document count,
 * indexing status, and field distribution across documents.
 */
@Serializable
data class IndexStats(
    val numberOfDocuments: Long = 0,
    val isIndexing: Boolean = false,
    val fieldDistribution: Map<String, Int>? = null,
)
