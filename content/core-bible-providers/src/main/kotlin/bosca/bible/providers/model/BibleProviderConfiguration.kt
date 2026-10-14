package bosca.bible.providers.model

import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/** Configuration details safe to return to administrators; credentials are stored separately. */
@Serializable
data class BibleProviderConfiguration(
    @Contextual val id: UUID,
    val name: String,
    val provider: String,
    val enabled: Boolean,
    val hasApiKey: Boolean,
)
