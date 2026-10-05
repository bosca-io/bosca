package bosca.meilisearch.admin.model

import kotlinx.serialization.Serializable

/**
 * Per-index statistics from Meilisearch showing the document count,
 * indexing status, and field distribution across documents. Useful for
 * monitoring index health and understanding data structure.
 */
@Serializable
data class MeilisearchIndexStats(
    val numberOfDocuments: Long,
    val isIndexing: Boolean,
    val fieldDistribution: List<MeilisearchFieldDistribution>,
)

/**
 * Describes the occurrence of a single field across documents in an index,
 * showing how many documents contain this field.
 */
@Serializable
data class MeilisearchFieldDistribution(
    val field: String,
    val count: Long,
)
