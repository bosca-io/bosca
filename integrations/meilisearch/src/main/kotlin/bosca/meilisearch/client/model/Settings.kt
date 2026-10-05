package bosca.meilisearch.client.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Complete set of configurable settings for a Meilisearch index.
 * All fields are nullable to allow partial updates via PATCH semantics.
 */
@Serializable
data class Settings(
    val rankingRules: List<String>? = null,
    val searchableAttributes: List<String>? = null,
    val displayedAttributes: List<String>? = null,
    val filterableAttributes: List<String>? = null,
    val sortableAttributes: List<String>? = null,
    val stopWords: List<String>? = null,
    val synonyms: Map<String, List<String>>? = null,
    val distinctAttribute: String? = null,
    val typoTolerance: TypoTolerance? = null,
    val pagination: Pagination? = null,
    val faceting: Faceting? = null,
    val proximityPrecision: String? = null,
    val searchCutoffMs: Int? = null,
    val embedders: Map<String, JsonObject>? = null,
)
