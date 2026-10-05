package bosca.meilisearch.client.model

import kotlinx.serialization.Serializable

/**
 * Pagination settings for a Meilisearch index controlling the
 * maximum number of total hits returned.
 */
@Serializable
data class Pagination(val maxTotalHits: Int? = null)
