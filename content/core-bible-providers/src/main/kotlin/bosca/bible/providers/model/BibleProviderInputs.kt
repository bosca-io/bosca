package bosca.bible.providers.model

import bosca.bible.BibleContentUse
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/** Administrator input. A null [apiKey] preserves the saved key when updating a configuration. */
@Serializable
data class BibleProviderConfigurationInput(
    val name: String,
    val provider: String,
    val enabled: Boolean,
    @Contextual val id: UUID? = null,
    val apiKey: String? = null,
) {
    override fun toString(): String =
        "BibleProviderConfigurationInput(id=$id, provider=$provider, enabled=$enabled, apiKey=[redacted])"
}

/** A selected provider text Bible and its reviewed uses. An empty set grants no use. */
@Serializable
data class AddProviderBibleInput(
    @Contextual val configurationId: UUID,
    val providerBibleId: String,
    val variant: String,
    val allowedUses: Set<BibleContentUse>,
)

/** A selected recording and its reviewed uses; audio sources may permit only PLAYBACK. */
@Serializable
data class AddProviderAudioInput(
    @Contextual val configurationId: UUID,
    val providerBibleId: String,
    val allowedUses: Set<BibleContentUse>,
)
