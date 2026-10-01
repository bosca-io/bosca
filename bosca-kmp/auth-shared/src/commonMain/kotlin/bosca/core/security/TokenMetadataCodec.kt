package bosca.core.security

import bosca.core.security.model.TokenMetadata
import kotlinx.serialization.json.Json

/**
 * Serializes [TokenMetadata] to/from the compact JSON string that the four
 * platform [TokenStorage] implementations persist under their metadata key.
 * Centralized so every platform stores an identical format and decode failures
 * (corrupt/legacy values) degrade to `null` rather than throwing.
 */
internal object TokenMetadataCodec {

    private val json = Json { ignoreUnknownKeys = true }

    fun encode(metadata: TokenMetadata): String =
        json.encodeToString(TokenMetadata.serializer(), metadata)

    /** Decodes stored metadata; returns `null` for absent/blank/corrupt values. */
    fun decode(raw: String?): TokenMetadata? {
        if (raw.isNullOrBlank()) return null
        return try {
            json.decodeFromString(TokenMetadata.serializer(), raw)
        } catch (e: Exception) {
            null
        }
    }
}
