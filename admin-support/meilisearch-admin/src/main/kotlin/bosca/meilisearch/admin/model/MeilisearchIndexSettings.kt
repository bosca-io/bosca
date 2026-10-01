package bosca.meilisearch.admin.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Complete settings of a Meilisearch index, including ranking rules,
 * attribute configurations, typo tolerance, pagination limits, faceting,
 * and embedder configurations for semantic search. Mirrors the Meilisearch
 * settings API response structure.
 */
@Serializable
data class MeilisearchIndexSettings(
    val rankingRules: List<String> = emptyList(),
    val searchableAttributes: List<String> = emptyList(),
    val displayedAttributes: List<String> = emptyList(),
    val filterableAttributes: List<String> = emptyList(),
    val sortableAttributes: List<String> = emptyList(),
    val stopWords: List<String> = emptyList(),
    val synonyms: List<MeilisearchSynonym> = emptyList(),
    val distinctAttribute: String? = null,
    val typoTolerance: MeilisearchTypoTolerance? = null,
    val pagination: MeilisearchPagination? = null,
    val faceting: MeilisearchFaceting? = null,
    val embedders: List<MeilisearchEmbedderEntry> = emptyList(),
    val proximityPrecision: String? = null,
    val searchCutoffMs: Int? = null,
)

/**
 * A synonym mapping that associates a word with its alternative forms,
 * allowing search queries to match documents using any of the synonym terms.
 */
@Serializable
data class MeilisearchSynonym(
    val word: String,
    val synonyms: List<String>,
)

/**
 * Typo tolerance configuration controlling how aggressively Meilisearch
 * corrects misspelled search terms, including minimum word sizes for
 * one- and two-typo corrections and exclusion lists.
 */
@Serializable
data class MeilisearchTypoTolerance(
    val enabled: Boolean = true,
    val minWordSizeForTypos: MeilisearchMinWordSize? = null,
    val disableOnWords: List<String>? = null,
    val disableOnAttributes: List<String>? = null,
)

/**
 * Minimum word length thresholds for activating typo correction.
 * Words shorter than these thresholds will not have typos corrected.
 */
@Serializable
data class MeilisearchMinWordSize(
    val oneTypo: Int = 5,
    val twoTypos: Int = 9,
)

/**
 * Pagination limits controlling the maximum number of total hits
 * that Meilisearch will return for a single search query.
 */
@Serializable
data class MeilisearchPagination(
    val maxTotalHits: Int = 1000,
)

/**
 * Faceting configuration controlling the maximum number of distinct
 * values returned per facet in search results.
 */
@Serializable
data class MeilisearchFaceting(
    val maxValuesPerFacet: Int = 100,
)

/**
 * Configuration for a named embedder on a Meilisearch index, specifying
 * the embedding source (e.g., OpenAI, HuggingFace), model, vector dimensions,
 * and document template for generating embeddings used in semantic search.
 * For REST embedders, includes the provider URL, request/response mapping,
 * and custom HTTP headers.
 */
@Serializable
data class MeilisearchEmbedderEntry(
    val name: String,
    val source: String? = null,
    val model: String? = null,
    val dimensions: Int? = null,
    val documentTemplate: String? = null,
    val documentTemplateMaxBytes: Int? = null,
    val url: String? = null,
    val request: JsonElement? = null,
    val response: JsonElement? = null,
    val headers: JsonElement? = null,
)
