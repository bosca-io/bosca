package bosca.bible.providers.model

import kotlinx.serialization.Serializable

/** Server-only settings stored as an encrypted platform Configuration value. */
@Serializable
data class BibleProviderSettings(
    val name: String,
    val provider: String,
    val enabled: Boolean,
    val apiKey: String? = null,
) {
    override fun toString(): String =
        "BibleProviderSettings(provider=$provider, enabled=$enabled, apiKey=[redacted])"
}
