package bosca.meilisearch.admin.model

import kotlinx.serialization.Serializable

/**
 * Health status of a Meilisearch instance, indicating whether
 * it is operational and accepting requests.
 */
@Serializable
data class MeilisearchHealth(
    val healthy: Boolean,
)
