package bosca.meilisearch.admin.model

import kotlinx.serialization.Serializable

/**
 * Basic metadata about a Meilisearch index including its identifier,
 * primary key, and timestamps. Used as the entry point for querying
 * nested index details such as stats, settings, and documents.
 */
@Serializable
data class MeilisearchIndexInfo(
    val uid: String,
    val primaryKey: String? = null,
    val createdAt: String,
    val updatedAt: String,
)
