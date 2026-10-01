package bosca.meilisearch.admin.model

import bosca.db.annotation.ColumnName
import bosca.serialization.UUID
import bosca.storage.model.StorageSystemType
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Represents a Meilisearch instance in the multi-node topology. Each node
 * has its own URL, API key, and declared types indicating what search
 * operations it supports (term search, vector search, or both). Nodes are
 * assigned to storage systems via the storage_system_nodes join table.
 *
 * The API key is stored encrypted at rest using AES-GCM. The [apiKey] and
 * [apiKeyNonce] fields hold the ciphertext and nonce respectively.
 */
@Serializable
data class MeilisearchNode(
    @Contextual
    val id: UUID = UUID.NIL,
    val name: String,
    val description: String = "",
    val url: String,
    @ColumnName("api_key")
    val apiKey: ByteArray? = null,
    @ColumnName("api_key_nonce")
    val apiKeyNonce: ByteArray? = null,
    val types: List<StorageSystemType>,
    @Contextual
    val configuration: JsonElement = JsonObject(emptyMap()),
) {
    /**
     * The decrypted API key, populated by the service layer after decryption.
     * Not persisted to the database.
     *
     * WARNING: This field is excluded from [copy], [toString], and [componentN] functions
     * because it is declared outside the primary constructor. Always call
     * [withDecryptedKey] or re-decrypt after using [copy].
     */
    @Transient
    internal var decryptedApiKey: String = ""
        private set

    /**
     * Returns this node with the decrypted API key set. Prefer this over direct
     * assignment to make key propagation explicit and auditable.
     */
    internal fun withDecryptedKey(key: String): MeilisearchNode {
        decryptedApiKey = key
        return this
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MeilisearchNode) return false
        return id == other.id &&
            name == other.name &&
            description == other.description &&
            url == other.url &&
            apiKey.contentEquals(other.apiKey) &&
            apiKeyNonce.contentEquals(other.apiKeyNonce) &&
            types == other.types &&
            configuration == other.configuration
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + description.hashCode()
        result = 31 * result + url.hashCode()
        result = 31 * result + (apiKey?.contentHashCode() ?: 0)
        result = 31 * result + (apiKeyNonce?.contentHashCode() ?: 0)
        result = 31 * result + types.hashCode()
        result = 31 * result + configuration.hashCode()
        return result
    }
}

/**
 * A remote Meilisearch instance registered in the federation network
 * configuration, enabling distributed search across multiple nodes
 * via the /network route.
 */
@Serializable
data class MeilisearchNetworkRemote(
    val name: String,
    val url: String,
)
