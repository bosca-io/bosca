package bosca.meilisearch.client.model

import kotlinx.serialization.Serializable

/**
 * Structured error response returned by Meilisearch when a request fails.
 * The [type] field identifies the category of error (e.g. "index_not_found")
 * and is used by callers for programmatic error matching.
 */
@Serializable
data class MeilisearchError(
    val message: String = "",
    val code: String = "",
    val type: String = "",
    val link: String? = null,
)
