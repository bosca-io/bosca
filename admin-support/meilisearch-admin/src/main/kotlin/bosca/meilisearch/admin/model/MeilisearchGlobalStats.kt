package bosca.meilisearch.admin.model

import kotlinx.serialization.Serializable

/**
 * Global statistics across all indexes on a Meilisearch instance,
 * providing a high-level overview of the instance's data footprint
 * and last modification time.
 */
@Serializable
data class MeilisearchGlobalStats(
    val numberOfIndexes: Int,
    val databaseSize: Long,
    val lastUpdate: String? = null,
)
