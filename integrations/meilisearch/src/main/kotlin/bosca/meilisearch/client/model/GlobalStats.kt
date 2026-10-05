package bosca.meilisearch.client.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Global statistics for the entire Meilisearch instance including
 * database size and per-index breakdown.
 */
@Serializable
data class GlobalStats(
    val databaseSize: Long = 0,
    val lastUpdate: String? = null,
    val indexes: Map<String, JsonObject>? = null,
)
