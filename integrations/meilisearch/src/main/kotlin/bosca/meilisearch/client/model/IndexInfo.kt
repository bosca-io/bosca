package bosca.meilisearch.client.model

import kotlinx.serialization.Serializable

/**
 * Metadata about a Meilisearch index including its unique identifier
 * and the configured primary key for document deduplication.
 */
@Serializable
data class IndexInfo(
    val uid: String,
    val primaryKey: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
)
