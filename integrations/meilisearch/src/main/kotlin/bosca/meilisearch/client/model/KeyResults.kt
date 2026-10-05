package bosca.meilisearch.client.model

import kotlinx.serialization.Serializable

/**
 * List of API keys returned by the Meilisearch keys endpoint.
 */
@Serializable
data class KeyResults(
    val results: List<Key> = emptyList(),
)

/**
 * A Meilisearch API key with its permissions, associated indexes,
 * and lifecycle timestamps.
 */
@Serializable
data class Key(
    val name: String? = null,
    val description: String? = null,
    val uid: String? = null,
    val key: String? = null,
    val actions: List<String>? = null,
    val indexes: List<String>? = null,
    val expiresAt: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
)
