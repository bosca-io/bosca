package bosca.meilisearch.admin.model

import kotlinx.serialization.Serializable

/**
 * An API key registered on the Meilisearch instance with its associated
 * permissions. Shows which actions the key can perform and on which indexes,
 * along with creation, update, and expiration timestamps.
 */
@Serializable
data class MeilisearchKey(
    val name: String? = null,
    val description: String,
    val uid: String,
    val key: String,
    val actions: List<String>,
    val indexes: List<String>,
    val expiresAt: String? = null,
    val createdAt: String,
    val updatedAt: String,
)
