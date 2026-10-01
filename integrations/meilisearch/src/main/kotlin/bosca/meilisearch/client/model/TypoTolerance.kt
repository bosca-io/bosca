package bosca.meilisearch.client.model

import kotlinx.serialization.Serializable

/**
 * Typo tolerance settings controlling how Meilisearch handles
 * misspellings in search queries.
 */
@Serializable
data class TypoTolerance(
    val enabled: Boolean? = null,
    val minWordSizeForTypos: Map<String, Int>? = null,
    val disableOnWords: List<String>? = null,
    val disableOnAttributes: List<String>? = null,
)
