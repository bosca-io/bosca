package bosca.meilisearch.client.model

import kotlinx.serialization.Serializable

/**
 * Search query parameters sent to the Meilisearch search endpoint.
 * All fields are optional — Meilisearch applies sensible defaults
 * for any omitted parameter.
 */
@Serializable
data class SearchRequest(
    val q: String? = null,
    val offset: Int? = null,
    val limit: Int? = null,
    val filter: List<String>? = null,
    val facets: List<String>? = null,
    val sort: List<String>? = null,
    val attributesToRetrieve: List<String>? = null,
    val hybrid: HybridSearch? = null,
    val vector: List<Double>? = null,
)
