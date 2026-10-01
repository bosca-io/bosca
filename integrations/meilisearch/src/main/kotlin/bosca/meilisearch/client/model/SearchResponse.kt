package bosca.meilisearch.client.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Search results returned by Meilisearch. The [hits] contain document
 * objects as [JsonObject] for type-safe access via kotlinx.serialization,
 * and [facetDistribution] maps facet field names to value-count pairs.
 */
@Serializable
data class SearchResponse(
    val hits: List<JsonObject> = emptyList(),
    val estimatedTotalHits: Int? = null,
    val totalHits: Int? = null,
    val facetDistribution: Map<String, Map<String, Int>>? = null,
    val offset: Int? = null,
    val limit: Int? = null,
    val processingTimeMs: Int? = null,
)
